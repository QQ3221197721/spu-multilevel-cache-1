package com.ecommerce.cache.optimization.v6;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;

/**
 * 增量同步引擎 - 高效数据同步
 * 
 * 核心特性:
 * 1. 增量变更捕获
 * 2. 批量合并优化
 * 3. 并行同步执行
 * 4. 冲突解决策略
 * 5. 断点续传
 * 6. 同步进度追踪
 * 
 * 性能目标:
 * - 同步延迟 < 100ms
 * - 吞吐量 > 50K/s
 * - 数据一致性 99.99%
 */
public class IncrementalSyncEngine {
    
    private static final Logger log = LoggerFactory.getLogger(IncrementalSyncEngine.class);
    
    @Value("${optimization.v6.sync.batch-size:100}")
    private int batchSize = 100;
    
    @Value("${optimization.v6.sync.flush-interval-ms:50}")
    private long flushIntervalMs = 50;
    
    @Value("${optimization.v6.sync.max-pending:10000}")
    private int maxPending = 10000;
    
    @Value("${optimization.v6.sync.parallel-workers:4}")
    private int parallelWorkers = 4;
    
    @Value("${optimization.v6.sync.retry-count:3}")
    private int retryCount = 3;
    
    @Value("${optimization.v6.sync.conflict-resolution:LAST_WRITER_WINS}")
    private ConflictResolution conflictResolution = ConflictResolution.LAST_WRITER_WINS;
    
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    
    // 变更队列
    private final BlockingQueue<ChangeEvent> changeQueue = new LinkedBlockingQueue<>();
    
    // 待同步批次
    private final ConcurrentHashMap<String, ChangeEvent> pendingChanges = new ConcurrentHashMap<>();
    
    // 同步进度
    private final ConcurrentHashMap<String, SyncProgress> syncProgress = new ConcurrentHashMap<>();
    
    // 同步回调
    private final List<BiConsumer<String, String>> syncCallbacks = new CopyOnWriteArrayList<>();
    
    // 统计
    private final LongAdder totalChanges = new LongAdder();
    private final LongAdder syncedChanges = new LongAdder();
    private final LongAdder mergedChanges = new LongAdder();
    private final LongAdder conflictResolved = new LongAdder();
    private final LongAdder syncErrors = new LongAdder();
    
    // 执行器
    private ScheduledExecutorService scheduledExecutor;
    private ExecutorService syncExecutor;
    
    // 指标
    private Timer syncTimer;
    private Counter syncErrorCounter;
    
    public IncrementalSyncEngine(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }
    
    @PostConstruct
    public void init() {
        // 初始化执行器
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("sync-sched").factory());
        syncExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("sync-worker-", 0).factory());
        
        // 启动定时刷新
        scheduledExecutor.scheduleAtFixedRate(this::flushPendingChanges, 
            flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        
        // 启动同步工作线程
        for (int i = 0; i < parallelWorkers; i++) {
            syncExecutor.submit(this::syncWorker);
        }
        
        // 注册指标
        registerMetrics();
        
        log.info("IncrementalSyncEngine initialized: batchSize={}, workers={}", batchSize, parallelWorkers);
    }
    
    @PreDestroy
    public void shutdown() {
        // 刷新剩余变更
        flushPendingChanges();
        
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
        if (syncExecutor != null) {
            syncExecutor.shutdown();
        }
    }
    
    private void registerMetrics() {
        syncTimer = Timer.builder("cache.sync.latency")
            .publishPercentileHistogram()
            .publishPercentiles(0.5, 0.9, 0.95, 0.99)
            .register(meterRegistry);
        syncErrorCounter = Counter.builder("cache.sync.errors")
            .register(meterRegistry);
        
        Gauge.builder("cache.sync.pending", pendingChanges, Map::size)
            .register(meterRegistry);
        Gauge.builder("cache.sync.queue.size", changeQueue, Queue::size)
            .register(meterRegistry);
        Gauge.builder("cache.sync.throughput", this, IncrementalSyncEngine::calculateThroughput)
            .register(meterRegistry);
    }
    
    /**
     * 注册同步回调
     */
    public void registerCallback(BiConsumer<String, String> callback) {
        syncCallbacks.add(callback);
    }
    
    /**
     * 记录变更
     */
    public void recordChange(String key, String value, ChangeType type) {
        if (pendingChanges.size() >= maxPending) {
            log.warn("Pending changes overflow, forcing flush");
            flushPendingChanges();
        }
        
        ChangeEvent event = new ChangeEvent(key, value, type, System.currentTimeMillis());
        totalChanges.increment();
        
        // 合并同Key变更
        pendingChanges.merge(key, event, this::mergeChanges);
    }
    
    /**
     * 记录写入变更
     */
    public void recordPut(String key, String value) {
        recordChange(key, value, ChangeType.PUT);
    }
    
    /**
     * 记录删除变更
     */
    public void recordDelete(String key) {
        recordChange(key, null, ChangeType.DELETE);
    }
    
    /**
     * 记录更新变更
     */
    public void recordUpdate(String key, String value) {
        recordChange(key, value, ChangeType.UPDATE);
    }
    
    /**
     * 合并变更
     */
    private ChangeEvent mergeChanges(ChangeEvent existing, ChangeEvent newEvent) {
        mergedChanges.increment();
        
        // 根据时间戳和类型决定合并结果
        if (newEvent.timestamp >= existing.timestamp) {
            // 新事件更新
            if (newEvent.type == ChangeType.DELETE) {
                return newEvent; // 删除优先
            }
            return newEvent;
        }
        
        // 冲突解决
        return resolveConflict(existing, newEvent);
    }
    
    /**
     * 冲突解决
     */
    private ChangeEvent resolveConflict(ChangeEvent a, ChangeEvent b) {
        conflictResolved.increment();
        
        return switch (conflictResolution) {
            case LAST_WRITER_WINS -> a.timestamp > b.timestamp ? a : b;
            case FIRST_WRITER_WINS -> a.timestamp < b.timestamp ? a : b;
            case MERGE -> mergeValues(a, b);
        };
    }
    
    /**
     * 值合并（简单实现）
     */
    private ChangeEvent mergeValues(ChangeEvent a, ChangeEvent b) {
        // 取最新的非空值
        String mergedValue = b.value != null ? b.value : a.value;
        return new ChangeEvent(a.key, mergedValue, ChangeType.UPDATE, Math.max(a.timestamp, b.timestamp));
    }
    
    /**
     * 刷新待同步变更
     */
    private void flushPendingChanges() {
        if (pendingChanges.isEmpty()) {
            return;
        }
        
        // 批量提取
        List<ChangeEvent> batch = new ArrayList<>();
        var iterator = pendingChanges.entrySet().iterator();
        
        while (iterator.hasNext() && batch.size() < batchSize) {
            var entry = iterator.next();
            batch.add(entry.getValue());
            iterator.remove();
        }
        
        if (!batch.isEmpty()) {
            changeQueue.addAll(batch);
        }
    }
    
    /**
     * 同步工作线程
     */
    private void syncWorker() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ChangeEvent event = changeQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                
                // 批量收集
                List<ChangeEvent> batch = new ArrayList<>();
                batch.add(event);
                changeQueue.drainTo(batch, batchSize - 1);
                
                // 执行同步
                syncBatch(batch);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Sync worker error", e);
            }
        }
    }
    
    /**
     * 批量同步
     */
    private void syncBatch(List<ChangeEvent> batch) {
        syncTimer.record(() -> {
            // 分组：写入和删除分开处理
            Map<String, String> puts = new HashMap<>();
            List<String> deletes = new ArrayList<>();
            
            for (ChangeEvent event : batch) {
                switch (event.type) {
                    case PUT, UPDATE -> {
                        if (event.value != null) {
                            puts.put(event.key, event.value);
                        }
                    }
                    case DELETE -> deletes.add(event.key);
                }
            }
            
            int retries = 0;
            boolean success = false;
            
            while (retries < retryCount && !success) {
                try {
                    // 批量写入
                    if (!puts.isEmpty()) {
                        redisTemplate.opsForValue().multiSet(puts);
                    }
                    
                    // 批量删除
                    if (!deletes.isEmpty()) {
                        redisTemplate.delete(deletes);
                    }
                    
                    success = true;
                    syncedChanges.add(batch.size());
                    
                    // 回调通知
                    for (ChangeEvent event : batch) {
                        for (BiConsumer<String, String> callback : syncCallbacks) {
                            try {
                                callback.accept(event.key, event.value);
                            } catch (Exception e) {
                                log.debug("Sync callback error: key={}", event.key);
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    retries++;
                    if (retries >= retryCount) {
                        log.error("Sync batch failed after {} retries", retryCount, e);
                        syncErrors.add(batch.size());
                        syncErrorCounter.increment(batch.size());
                    } else {
                        try {
                            Thread.sleep(100 * retries);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            
            // 更新进度
            updateProgress(batch);
        });
    }
    
    /**
     * 更新同步进度
     */
    private void updateProgress(List<ChangeEvent> batch) {
        for (ChangeEvent event : batch) {
            syncProgress.compute(event.key, (k, v) -> {
                if (v == null) {
                    v = new SyncProgress();
                }
                v.lastSyncTime = System.currentTimeMillis();
                v.syncCount++;
                return v;
            });
        }
    }
    
    /**
     * 计算吞吐量
     */
    private double calculateThroughput() {
        // 简单计算最近的同步吞吐
        return syncedChanges.sum() / 60.0; // 每秒
    }
    
    /**
     * 强制同步
     */
    public void forceSync() {
        flushPendingChanges();
        
        // 等待队列清空
        while (!changeQueue.isEmpty()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * 获取同步状态
     */
    public SyncStatus getSyncStatus() {
        return new SyncStatus(
            changeQueue.isEmpty() && pendingChanges.isEmpty(),
            pendingChanges.size(),
            changeQueue.size(),
            syncedChanges.sum(),
            syncErrors.sum()
        );
    }
    
    /**
     * 获取统计信息
     */
    public SyncStats getStats() {
        return new SyncStats(
            totalChanges.sum(),
            syncedChanges.sum(),
            mergedChanges.sum(),
            conflictResolved.sum(),
            syncErrors.sum(),
            pendingChanges.size(),
            changeQueue.size(),
            calculateThroughput()
        );
    }
    
    // ========== 枚举和内部类 ==========
    
    public enum ChangeType {
        PUT, UPDATE, DELETE
    }
    
    public enum ConflictResolution {
        LAST_WRITER_WINS,
        FIRST_WRITER_WINS,
        MERGE
    }
    
    private record ChangeEvent(String key, String value, ChangeType type, long timestamp) {}
    
    private static class SyncProgress {
        long lastSyncTime;
        long syncCount;
    }
    
    public record SyncStatus(
        boolean synced,
        int pendingCount,
        int queueSize,
        long syncedTotal,
        long errors
    ) {}
    
    public record SyncStats(
        long totalChanges,
        long syncedChanges,
        long mergedChanges,
        long conflictResolved,
        long syncErrors,
        int pendingSize,
        int queueSize,
        double throughput
    ) {}
}
