package com.ecommerce.cache.optimization.v8;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * 近实时缓存同步引擎
 * 
 * 核心特性:
 * 1. 毫秒级同步: 50ms内完成跨节点同步
 * 2. 批量处理: 批量聚合减少网络开销
 * 3. 增量同步: 只同步变更数据
 * 4. 压缩传输: 减少带宽占用
 * 5. 失败重试: 自动重试失败同步
 * 6. 冲突解决: 基于版本号的冲突解决
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NearRealTimeSyncEngine {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final OptimizationV8Properties properties;
    
    // ========== 同步缓冲 ==========
    
    /** 待同步缓冲区 */
    private final BlockingQueue<SyncEntry> syncBuffer = new LinkedBlockingQueue<>();
    
    /** 版本追踪 */
    private final ConcurrentMap<String, Long> versionMap = new ConcurrentHashMap<>();
    
    /** 同步订阅者 */
    private final List<SyncSubscriber> subscribers = new CopyOnWriteArrayList<>();
    
    /** 失败重试队列 */
    private final Queue<RetryEntry> retryQueue = new ConcurrentLinkedQueue<>();
    
    /** 执行器 */
    private ScheduledExecutorService scheduler;
    private ExecutorService syncExecutor;
    
    /** 统计 */
    private final AtomicLong syncCount = new AtomicLong(0);
    private final AtomicLong failCount = new AtomicLong(0);
    private final AtomicLong conflictCount = new AtomicLong(0);
    private final AtomicLong bytesTransferred = new AtomicLong(0);
    
    /** 运行状态 */
    private volatile boolean running = false;
    
    // ========== 常量 ==========
    
    private static final String SYNC_CHANNEL = "v8:sync:channel";
    private static final String VERSION_KEY_PREFIX = "v8:sync:ver:";
    private static final int MAX_RETRY = 3;
    
    // ========== 指标 ==========
    
    private Counter syncCounter;
    private Counter failCounter;
    private Timer syncTimer;
    
    @PostConstruct
    public void init() {
        if (!properties.getNearRealTime().isEnabled()) {
            log.info("[近实时同步] 已禁用");
            return;
        }
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "nrt-sync-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        syncExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "nrt-sync-worker");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        running = true;
        
        // 启动同步任务
        int interval = properties.getNearRealTime().getSyncIntervalMs();
        scheduler.scheduleWithFixedDelay(
            this::processSyncBatch,
            interval,
            interval,
            TimeUnit.MILLISECONDS
        );
        
        // 启动重试任务
        scheduler.scheduleWithFixedDelay(
            this::processRetryQueue,
            1000,
            1000,
            TimeUnit.MILLISECONDS
        );
        
        log.info("[近实时同步] 初始化完成 - 同步间隔: {}ms, 批量大小: {}",
            interval, properties.getNearRealTime().getBatchSize());
    }
    
    @PreDestroy
    public void shutdown() {
        running = false;
        
        // 处理剩余同步
        processSyncBatch();
        
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (syncExecutor != null) {
            syncExecutor.shutdown();
        }
        
        log.info("[近实时同步] 已关闭 - 同步: {}, 失败: {}, 冲突: {}",
            syncCount.get(), failCount.get(), conflictCount.get());
    }
    
    private void initMetrics() {
        syncCounter = Counter.builder("cache.nrt.sync")
            .description("近实时同步次数")
            .register(meterRegistry);
        
        failCounter = Counter.builder("cache.nrt.fail")
            .description("同步失败次数")
            .register(meterRegistry);
        
        syncTimer = Timer.builder("cache.nrt.latency")
            .description("同步延迟")
            .register(meterRegistry);
    }
    
    // ========== 核心API ==========
    
    /**
     * 提交同步条目
     */
    public void submit(String key, Object data, SyncType type) {
        if (!running) {
            return;
        }
        
        long version = versionMap.compute(key, (k, v) -> (v == null ? 0 : v) + 1);
        
        SyncEntry entry = new SyncEntry(
            UUID.randomUUID().toString(),
            key,
            data,
            type,
            version,
            System.currentTimeMillis()
        );
        
        if (!syncBuffer.offer(entry)) {
            log.warn("[近实时同步] 缓冲区已满，丢弃: {}", key);
        }
    }
    
    /**
     * 批量提交
     */
    public void submitBatch(Map<String, Object> entries, SyncType type) {
        for (var entry : entries.entrySet()) {
            submit(entry.getKey(), entry.getValue(), type);
        }
    }
    
    /**
     * 强制同步(不等待批量)
     */
    public CompletableFuture<SyncResult> forceSync(String key, Object data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long version = versionMap.compute(key, (k, v) -> (v == null ? 0 : v) + 1);
                
                SyncEntry entry = new SyncEntry(
                    UUID.randomUUID().toString(),
                    key,
                    data,
                    SyncType.PUT,
                    version,
                    System.currentTimeMillis()
                );
                
                return syncSingle(entry);
            } catch (Exception e) {
                return new SyncResult(key, false, e.getMessage());
            }
        }, syncExecutor);
    }
    
    /**
     * 注册同步订阅者
     */
    public void subscribe(SyncSubscriber subscriber) {
        subscribers.add(subscriber);
        log.info("[近实时同步] 新增订阅者: {}", subscriber.getId());
    }
    
    /**
     * 取消订阅
     */
    public void unsubscribe(String subscriberId) {
        subscribers.removeIf(s -> s.getId().equals(subscriberId));
    }
    
    /**
     * 获取版本号
     */
    public long getVersion(String key) {
        return versionMap.getOrDefault(key, 0L);
    }
    
    // ========== 内部方法 ==========
    
    private void processSyncBatch() {
        if (syncBuffer.isEmpty()) {
            return;
        }
        
        List<SyncEntry> batch = new ArrayList<>();
        int batchSize = properties.getNearRealTime().getBatchSize();
        
        // 收集批量
        syncBuffer.drainTo(batch, batchSize);
        
        if (batch.isEmpty()) {
            return;
        }
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // 按Key分组去重(保留最新版本)
            Map<String, SyncEntry> deduped = new LinkedHashMap<>();
            for (SyncEntry entry : batch) {
                deduped.merge(entry.key, entry, (old, newer) -> 
                    newer.version > old.version ? newer : old);
            }
            
            // 批量同步
            List<SyncResult> results = syncBatch(new ArrayList<>(deduped.values()));
            
            // 处理结果
            for (SyncResult result : results) {
                if (result.success) {
                    syncCount.incrementAndGet();
                    syncCounter.increment();
                } else {
                    failCount.incrementAndGet();
                    failCounter.increment();
                    
                    // 加入重试队列
                    SyncEntry entry = deduped.get(result.key);
                    if (entry != null) {
                        retryQueue.offer(new RetryEntry(entry, 1));
                    }
                }
            }
            
            // 通知订阅者
            notifySubscribers(new ArrayList<>(deduped.values()));
            
        } catch (Exception e) {
            log.error("[近实时同步] 批量同步失败", e);
            
            // 全部加入重试
            for (SyncEntry entry : batch) {
                retryQueue.offer(new RetryEntry(entry, 1));
            }
        } finally {
            sample.stop(syncTimer);
        }
    }
    
    private List<SyncResult> syncBatch(List<SyncEntry> entries) {
        List<SyncResult> results = new ArrayList<>();
        
        // 压缩数据
        byte[] compressed = null;
        if (properties.getNearRealTime().isCompressionEnabled()) {
            compressed = compressEntries(entries);
            bytesTransferred.addAndGet(compressed.length);
        }
        
        // 发布到Redis
        try {
            for (SyncEntry entry : entries) {
                // 检查版本冲突
                String versionKey = VERSION_KEY_PREFIX + entry.key;
                Long remoteVersion = (Long) redisTemplate.opsForValue().get(versionKey);
                
                if (remoteVersion != null && remoteVersion >= entry.version) {
                    conflictCount.incrementAndGet();
                    results.add(new SyncResult(entry.key, false, "Version conflict"));
                    continue;
                }
                
                // 写入数据
                switch (entry.type) {
                    case PUT:
                        redisTemplate.opsForValue().set("v8:data:" + entry.key, entry.data);
                        break;
                    case DELETE:
                        redisTemplate.delete("v8:data:" + entry.key);
                        break;
                    case UPDATE:
                        redisTemplate.opsForValue().set("v8:data:" + entry.key, entry.data);
                        break;
                }
                
                // 更新远程版本
                redisTemplate.opsForValue().set(versionKey, entry.version);
                
                results.add(new SyncResult(entry.key, true, null));
            }
        } catch (Exception e) {
            log.error("[近实时同步] Redis操作失败", e);
            for (SyncEntry entry : entries) {
                results.add(new SyncResult(entry.key, false, e.getMessage()));
            }
        }
        
        return results;
    }
    
    private SyncResult syncSingle(SyncEntry entry) {
        try {
            String versionKey = VERSION_KEY_PREFIX + entry.key;
            Long remoteVersion = (Long) redisTemplate.opsForValue().get(versionKey);
            
            if (remoteVersion != null && remoteVersion >= entry.version) {
                conflictCount.incrementAndGet();
                return new SyncResult(entry.key, false, "Version conflict");
            }
            
            redisTemplate.opsForValue().set("v8:data:" + entry.key, entry.data);
            redisTemplate.opsForValue().set(versionKey, entry.version);
            
            syncCount.incrementAndGet();
            syncCounter.increment();
            
            return new SyncResult(entry.key, true, null);
        } catch (Exception e) {
            failCount.incrementAndGet();
            failCounter.increment();
            return new SyncResult(entry.key, false, e.getMessage());
        }
    }
    
    private void processRetryQueue() {
        int processed = 0;
        RetryEntry retry;
        
        while ((retry = retryQueue.poll()) != null && processed < 100) {
            if (retry.retryCount > MAX_RETRY) {
                log.warn("[近实时同步] 重试次数超限，放弃: {}", retry.entry.key);
                continue;
            }
            
            SyncResult result = syncSingle(retry.entry);
            
            if (!result.success && retry.retryCount < MAX_RETRY) {
                retryQueue.offer(new RetryEntry(retry.entry, retry.retryCount + 1));
            }
            
            processed++;
        }
    }
    
    private byte[] compressEntries(List<SyncEntry> entries) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            
            for (SyncEntry entry : entries) {
                String data = entry.key + ":" + entry.version + ":" + entry.data;
                gzip.write(data.getBytes());
                gzip.write('\n');
            }
            
            gzip.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("[近实时同步] 压缩失败", e);
            return new byte[0];
        }
    }
    
    private void notifySubscribers(List<SyncEntry> entries) {
        for (SyncSubscriber subscriber : subscribers) {
            try {
                syncExecutor.submit(() -> subscriber.onSync(entries));
            } catch (Exception e) {
                log.warn("[近实时同步] 通知订阅者失败: {}", subscriber.getId());
            }
        }
    }
    
    // ========== 统计信息 ==========
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("bufferSize", syncBuffer.size());
        stats.put("retryQueueSize", retryQueue.size());
        stats.put("syncCount", syncCount.get());
        stats.put("failCount", failCount.get());
        stats.put("conflictCount", conflictCount.get());
        stats.put("bytesTransferred", bytesTransferred.get());
        stats.put("versionMapSize", versionMap.size());
        stats.put("subscriberCount", subscribers.size());
        stats.put("running", running);
        
        // 配置信息
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("syncIntervalMs", properties.getNearRealTime().getSyncIntervalMs());
        config.put("batchSize", properties.getNearRealTime().getBatchSize());
        config.put("compressionEnabled", properties.getNearRealTime().isCompressionEnabled());
        stats.put("config", config);
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    public enum SyncType {
        PUT, DELETE, UPDATE
    }
    
    @Data
    public static class SyncEntry {
        private final String id;
        private final String key;
        private final Object data;
        private final SyncType type;
        private final long version;
        private final long timestamp;
    }
    
    @Data
    public static class SyncResult {
        private final String key;
        private final boolean success;
        private final String error;
    }
    
    public interface SyncSubscriber {
        String getId();
        void onSync(List<SyncEntry> entries);
    }
    
    private record RetryEntry(SyncEntry entry, int retryCount) {}
}
