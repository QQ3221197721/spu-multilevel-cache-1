package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 高级缓存协调器 - 智能多级缓存路由与协调
 * 
 * 核心特性：
 * 1. 智能路由 - 根据数据特征自动选择最优缓存层
 * 2. 自适应策略 - 根据访问模式动态调整缓存策略
 * 3. 并行读取优化 - First-Win 策略减少延迟
 * 4. 写入策略优化 - Write-Behind/Write-Through 自动选择
 * 5. 一致性保证 - 多级缓存一致性协议
 * 6. 故障转移 - 自动降级与恢复
 * 
 * 目标：TP99 < 30ms，总命中率 > 98%
 */
@Service
public class CacheCoordinator {
    
    private static final Logger log = LoggerFactory.getLogger(CacheCoordinator.class);
    
    // 缓存层枚举
    public enum CacheLayer {
        L0_ULTRA_HOT(0, "L0-UltraHot", 100),      // 亚微秒级
        L1_LOCAL(1, "L1-Local", 500),              // 微秒级
        L2_REDIS(2, "L2-Redis", 2000),             // 毫秒级
        L3_MEMCACHED(3, "L3-Memcached", 5000),     // 毫秒级
        DB(4, "Database", 50000);                   // 十毫秒级
        
        final int level;
        final String name;
        final int expectedLatencyUs;
        
        CacheLayer(int level, String name, int expectedLatencyUs) {
            this.level = level;
            this.name = name;
            this.expectedLatencyUs = expectedLatencyUs;
        }
    }
    
    // 写入策略
    public enum WriteStrategy {
        WRITE_THROUGH,      // 同步写入所有层
        WRITE_BEHIND,       // 异步批量写入
        WRITE_AROUND        // 绕过缓存直接写DB
    }
    
    // 依赖注入
    private final UltraHotCacheLayer l0Cache;
    private final MeterRegistry meterRegistry;
    
    // 缓存层状态
    private final ConcurrentHashMap<CacheLayer, LayerStatus> layerStatuses = new ConcurrentHashMap<>();
    
    // 路由决策缓存
    private final ConcurrentHashMap<String, RouteDecision> routeDecisionCache = new ConcurrentHashMap<>();
    
    // 写入队列（Write-Behind）
    private final BlockingQueue<WriteTask> writeBehindQueue = new LinkedBlockingQueue<>(10000);
    
    // 写入批次聚合器
    private final ConcurrentHashMap<String, WriteTask> pendingWrites = new ConcurrentHashMap<>();
    
    // 并行读取执行器
    private final ExecutorService parallelReadExecutor;
    
    // 配置
    @Value("${optimization.coordinator.parallel-read-timeout-ms:50}")
    private long parallelReadTimeoutMs;
    
    @Value("${optimization.coordinator.write-behind-enabled:true}")
    private boolean writeBehindEnabled;
    
    @Value("${optimization.coordinator.write-behind-batch-size:100}")
    private int writeBehindBatchSize;
    
    @Value("${optimization.coordinator.write-behind-interval-ms:100}")
    private long writeBehindIntervalMs;
    
    @Value("${optimization.coordinator.adaptive-routing-enabled:true}")
    private boolean adaptiveRoutingEnabled;
    
    // 指标
    private Timer coordinatorReadTimer;
    private Timer coordinatorWriteTimer;
    private Counter parallelReadWinCounter;
    private Counter writeBehindCounter;
    private Counter routeDecisionCounter;
    
    // 统计
    private final ConcurrentHashMap<CacheLayer, LongAdder> layerHitCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CacheLayer, LongAdder> layerLatencySum = new ConcurrentHashMap<>();
    
    public CacheCoordinator(UltraHotCacheLayer l0Cache, MeterRegistry meterRegistry) {
        this.l0Cache = l0Cache;
        this.meterRegistry = meterRegistry;
        
        // 初始化虚拟线程执行器
        this.parallelReadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        // 初始化缓存层状态
        for (CacheLayer layer : CacheLayer.values()) {
            layerStatuses.put(layer, new LayerStatus(layer));
            layerHitCounters.put(layer, new LongAdder());
            layerLatencySum.put(layer, new LongAdder());
        }
    }
    
    @PostConstruct
    public void init() {
        // 注册指标
        coordinatorReadTimer = Timer.builder("cache.coordinator.read.latency")
            .publishPercentileHistogram()
            .publishPercentiles(0.5, 0.9, 0.95, 0.99)
            .register(meterRegistry);
        coordinatorWriteTimer = Timer.builder("cache.coordinator.write.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        parallelReadWinCounter = Counter.builder("cache.coordinator.parallel.wins")
            .register(meterRegistry);
        writeBehindCounter = Counter.builder("cache.coordinator.write.behind")
            .register(meterRegistry);
        routeDecisionCounter = Counter.builder("cache.coordinator.route.decisions")
            .register(meterRegistry);
        
        // 层级命中率指标
        for (CacheLayer layer : CacheLayer.values()) {
            Gauge.builder("cache.coordinator.layer.hits", layerHitCounters.get(layer), LongAdder::sum)
                .tag("layer", layer.name)
                .register(meterRegistry);
        }
        
        // 启动 Write-Behind 处理器
        if (writeBehindEnabled) {
            startWriteBehindProcessor();
        }
        
        log.info("CacheCoordinator initialized: parallelTimeout={}ms, writeBehind={}", 
            parallelReadTimeoutMs, writeBehindEnabled);
    }
    
    /**
     * 智能读取 - 自动选择最优路径
     */
    public <T> T smartGet(String key, Function<String, T> dbLoader, Class<T> type) {
        return coordinatorReadTimer.record(() -> {
            // 获取路由决策
            RouteDecision decision = getRouteDecision(key);
            
            // 按优先级尝试各缓存层
            for (CacheLayer layer : decision.readPath()) {
                LayerStatus status = layerStatuses.get(layer);
                if (!status.isHealthy()) continue;
                
                long startTime = System.nanoTime();
                try {
                    T value = readFromLayer(key, layer, type);
                    if (value != null) {
                        long latencyNs = System.nanoTime() - startTime;
                        recordLayerHit(layer, latencyNs);
                        
                        // 考虑升级到更高层级
                        considerPromotion(key, value, layer);
                        
                        return value;
                    }
                } catch (Exception e) {
                    status.recordFailure();
                    log.warn("Read from {} failed: {}", layer, e.getMessage());
                }
            }
            
            // 所有缓存未命中，从 DB 加载
            return loadFromDbAndCache(key, dbLoader, decision);
        });
    }
    
    /**
     * 并行读取 - First-Win 策略
     */
    public <T> T parallelGet(String key, List<CacheLayer> layers, Class<T> type) {
        if (layers.isEmpty()) return null;
        
        // 创建并行读取任务
        List<CompletableFuture<LayerReadResult<T>>> futures = layers.stream()
            .filter(layer -> layerStatuses.get(layer).isHealthy())
            .map(layer -> CompletableFuture.supplyAsync(() -> {
                long start = System.nanoTime();
                try {
                    T value = readFromLayer(key, layer, type);
                    return new LayerReadResult<>(layer, value, System.nanoTime() - start, null);
                } catch (Exception e) {
                    return new LayerReadResult<>(layer, null, System.nanoTime() - start, e);
                }
            }, parallelReadExecutor))
            .toList();
        
        // First-Win 策略
        try {
            CompletableFuture<LayerReadResult<T>> winner = CompletableFuture.anyOf(
                futures.stream()
                    .map(f -> f.thenCompose(result -> 
                        result.value != null ? CompletableFuture.completedFuture(result) : 
                            new CompletableFuture<>()))
                    .toArray(CompletableFuture[]::new)
            ).thenApply(obj -> {
                @SuppressWarnings("unchecked")
                LayerReadResult<T> result = (LayerReadResult<T>) obj;
                return result;
            });
            
            LayerReadResult<T> result = winner.get(parallelReadTimeoutMs, TimeUnit.MILLISECONDS);
            if (result != null && result.value != null) {
                parallelReadWinCounter.increment();
                recordLayerHit(result.layer, result.latencyNs);
                return result.value;
            }
        } catch (TimeoutException e) {
            log.debug("Parallel read timeout for key: {}", key);
        } catch (Exception e) {
            log.warn("Parallel read error for key: {}", key, e);
        }
        
        return null;
    }
    
    /**
     * 智能写入
     */
    public <T> void smartPut(String key, T value, long ttlSeconds) {
        coordinatorWriteTimer.record(() -> {
            RouteDecision decision = getRouteDecision(key);
            WriteStrategy strategy = determineWriteStrategy(key, value);
            
            switch (strategy) {
                case WRITE_THROUGH -> writeThrough(key, value, ttlSeconds, decision);
                case WRITE_BEHIND -> writeBehind(key, value, ttlSeconds, decision);
                case WRITE_AROUND -> writeAround(key, value);
            }
        });
    }
    
    /**
     * Write-Through 策略
     */
    private <T> void writeThrough(String key, T value, long ttlSeconds, RouteDecision decision) {
        String serialized = serialize(value);
        
        for (CacheLayer layer : decision.writePath()) {
            try {
                writeToLayer(key, serialized, ttlSeconds, layer);
            } catch (Exception e) {
                log.warn("Write-through to {} failed: {}", layer, e.getMessage());
            }
        }
    }
    
    /**
     * Write-Behind 策略
     */
    private <T> void writeBehind(String key, T value, long ttlSeconds, RouteDecision decision) {
        // 立即写入最高层级
        String serialized = serialize(value);
        CacheLayer topLayer = decision.writePath().get(0);
        writeToLayer(key, serialized, ttlSeconds, topLayer);
        
        // 其他层级加入异步队列
        WriteTask task = new WriteTask(key, serialized, ttlSeconds, 
            decision.writePath().subList(1, decision.writePath().size()));
        pendingWrites.put(key, task);
        writeBehindQueue.offer(task);
        writeBehindCounter.increment();
    }
    
    /**
     * Write-Around 策略
     */
    private <T> void writeAround(String key, T value) {
        // 只删除缓存，不写入
        invalidateAll(key);
    }
    
    /**
     * 失效所有层级缓存
     */
    public void invalidateAll(String key) {
        for (CacheLayer layer : CacheLayer.values()) {
            if (layer == CacheLayer.DB) continue;
            try {
                invalidateLayer(key, layer);
            } catch (Exception e) {
                log.warn("Invalidate {} failed: {}", layer, e.getMessage());
            }
        }
    }
    
    /**
     * 延迟双删
     */
    public void delayedDoubleDelete(String key, long delayMs) {
        // 第一次删除
        invalidateAll(key);
        
        // 延迟第二次删除
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
            .execute(() -> invalidateAll(key));
    }
    
    /**
     * 获取路由决策
     */
    private RouteDecision getRouteDecision(String key) {
        if (!adaptiveRoutingEnabled) {
            return getDefaultRouteDecision();
        }
        
        return routeDecisionCache.computeIfAbsent(key, k -> {
            routeDecisionCounter.increment();
            return calculateRouteDecision(k);
        });
    }
    
    /**
     * 计算路由决策
     */
    private RouteDecision calculateRouteDecision(String key) {
        // 基于 key 特征计算最优路径
        boolean isHotKey = isLikelyHotKey(key);
        boolean isLargeValue = isLikelyLargeValue(key);
        
        List<CacheLayer> readPath;
        List<CacheLayer> writePath;
        
        if (isHotKey) {
            // 热点数据：优先 L0 -> L1 -> L2
            readPath = List.of(CacheLayer.L0_ULTRA_HOT, CacheLayer.L1_LOCAL, CacheLayer.L2_REDIS);
            writePath = List.of(CacheLayer.L0_ULTRA_HOT, CacheLayer.L1_LOCAL, CacheLayer.L2_REDIS, CacheLayer.L3_MEMCACHED);
        } else if (isLargeValue) {
            // 大值数据：跳过 L0，优先 Memcached
            readPath = List.of(CacheLayer.L1_LOCAL, CacheLayer.L3_MEMCACHED, CacheLayer.L2_REDIS);
            writePath = List.of(CacheLayer.L3_MEMCACHED, CacheLayer.L2_REDIS);
        } else {
            // 普通数据
            readPath = List.of(CacheLayer.L1_LOCAL, CacheLayer.L2_REDIS, CacheLayer.L3_MEMCACHED);
            writePath = List.of(CacheLayer.L1_LOCAL, CacheLayer.L2_REDIS, CacheLayer.L3_MEMCACHED);
        }
        
        return new RouteDecision(key, readPath, writePath, System.currentTimeMillis());
    }
    
    private RouteDecision getDefaultRouteDecision() {
        return new RouteDecision(
            "default",
            List.of(CacheLayer.L0_ULTRA_HOT, CacheLayer.L1_LOCAL, CacheLayer.L2_REDIS, CacheLayer.L3_MEMCACHED),
            List.of(CacheLayer.L1_LOCAL, CacheLayer.L2_REDIS, CacheLayer.L3_MEMCACHED),
            System.currentTimeMillis()
        );
    }
    
    private boolean isLikelyHotKey(String key) {
        // 基于历史数据判断
        LongAdder l0Hits = layerHitCounters.get(CacheLayer.L0_ULTRA_HOT);
        return l0Hits != null && l0Hits.sum() > 1000;
    }
    
    private boolean isLikelyLargeValue(String key) {
        // 基于 key 模式判断
        return key.contains(":detail:") || key.contains(":full:");
    }
    
    /**
     * 从指定层读取
     */
    @SuppressWarnings("unchecked")
    private <T> T readFromLayer(String key, CacheLayer layer, Class<T> type) {
        return switch (layer) {
            case L0_ULTRA_HOT -> {
                String value = l0Cache.get(key);
                yield value != null ? (T) deserialize(value, type) : null;
            }
            case L1_LOCAL -> null; // 由外部 L1 服务处理
            case L2_REDIS -> null; // 由外部 Redis 服务处理
            case L3_MEMCACHED -> null; // 由外部 Memcached 服务处理
            case DB -> null;
        };
    }
    
    /**
     * 写入指定层
     */
    private void writeToLayer(String key, String value, long ttlSeconds, CacheLayer layer) {
        switch (layer) {
            case L0_ULTRA_HOT -> l0Cache.put(key, value, ttlSeconds * 1000);
            case L1_LOCAL -> {} // 由外部服务处理
            case L2_REDIS -> {}
            case L3_MEMCACHED -> {}
            case DB -> {}
        }
    }
    
    /**
     * 失效指定层
     */
    private void invalidateLayer(String key, CacheLayer layer) {
        switch (layer) {
            case L0_ULTRA_HOT -> l0Cache.invalidate(key);
            case L1_LOCAL -> {}
            case L2_REDIS -> {}
            case L3_MEMCACHED -> {}
            case DB -> {}
        }
    }
    
    /**
     * 从 DB 加载并缓存
     */
    private <T> T loadFromDbAndCache(String key, Function<String, T> dbLoader, RouteDecision decision) {
        T value = dbLoader.apply(key);
        if (value != null) {
            String serialized = serialize(value);
            for (CacheLayer layer : decision.writePath()) {
                if (layer != CacheLayer.DB) {
                    writeToLayer(key, serialized, 600, layer);
                }
            }
        }
        return value;
    }
    
    /**
     * 考虑升级到更高层级
     */
    private <T> void considerPromotion(String key, T value, CacheLayer currentLayer) {
        if (currentLayer.level > CacheLayer.L0_ULTRA_HOT.level) {
            l0Cache.considerUpgrade(key, serialize(value));
        }
    }
    
    /**
     * 记录层级命中
     */
    private void recordLayerHit(CacheLayer layer, long latencyNs) {
        layerHitCounters.get(layer).increment();
        layerLatencySum.get(layer).add(latencyNs / 1000); // 转换为微秒
    }
    
    /**
     * 确定写入策略
     */
    private <T> WriteStrategy determineWriteStrategy(String key, T value) {
        if (!writeBehindEnabled) {
            return WriteStrategy.WRITE_THROUGH;
        }
        
        // 大值使用 Write-Behind
        String serialized = serialize(value);
        if (serialized.length() > 1024) {
            return WriteStrategy.WRITE_BEHIND;
        }
        
        return WriteStrategy.WRITE_THROUGH;
    }
    
    /**
     * 启动 Write-Behind 处理器
     */
    private void startWriteBehindProcessor() {
        Thread processor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<WriteTask> batch = new ArrayList<>();
                    WriteTask task = writeBehindQueue.poll(writeBehindIntervalMs, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        batch.add(task);
                        writeBehindQueue.drainTo(batch, writeBehindBatchSize - 1);
                        processBatchWrite(batch);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Write-behind processor error", e);
                }
            }
        }, "write-behind-processor");
        processor.setDaemon(true);
        processor.start();
    }
    
    /**
     * 批量处理写入
     */
    private void processBatchWrite(List<WriteTask> batch) {
        for (WriteTask task : batch) {
            for (CacheLayer layer : task.layers) {
                try {
                    writeToLayer(task.key, task.value, task.ttlSeconds, layer);
                } catch (Exception e) {
                    log.warn("Batch write to {} failed: {}", layer, e.getMessage());
                }
            }
            pendingWrites.remove(task.key);
        }
        log.debug("Batch write completed: {} tasks", batch.size());
    }
    
    /**
     * 定期清理路由决策缓存
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void cleanupRouteDecisionCache() {
        long threshold = System.currentTimeMillis() - 600000; // 10分钟
        routeDecisionCache.entrySet().removeIf(e -> e.getValue().timestamp < threshold);
        log.debug("Route decision cache cleanup, remaining: {}", routeDecisionCache.size());
    }
    
    /**
     * 获取协调器统计
     */
    public CoordinatorStats getStats() {
        Map<String, LayerStats> layerStats = new HashMap<>();
        for (CacheLayer layer : CacheLayer.values()) {
            long hits = layerHitCounters.get(layer).sum();
            long latencySum = layerLatencySum.get(layer).sum();
            double avgLatencyUs = hits > 0 ? (double) latencySum / hits : 0;
            LayerStatus status = layerStatuses.get(layer);
            layerStats.put(layer.name, new LayerStats(hits, avgLatencyUs, status.isHealthy(), status.failureCount.get()));
        }
        
        return new CoordinatorStats(
            layerStats,
            routeDecisionCache.size(),
            pendingWrites.size(),
            writeBehindQueue.size()
        );
    }
    
    // ========== 内部类 ==========
    
    private record LayerReadResult<T>(CacheLayer layer, T value, long latencyNs, Exception error) {}
    
    private record RouteDecision(String key, List<CacheLayer> readPath, List<CacheLayer> writePath, long timestamp) {}
    
    private record WriteTask(String key, String value, long ttlSeconds, List<CacheLayer> layers) {}
    
    private static class LayerStatus {
        final CacheLayer layer;
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicLong lastFailureTime = new AtomicLong(0);
        volatile boolean healthy = true;
        
        LayerStatus(CacheLayer layer) {
            this.layer = layer;
        }
        
        void recordFailure() {
            failureCount.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());
            if (failureCount.get() > 5) {
                healthy = false;
            }
        }
        
        void recordSuccess() {
            failureCount.set(0);
            healthy = true;
        }
        
        boolean isHealthy() {
            // 自动恢复检查
            if (!healthy && System.currentTimeMillis() - lastFailureTime.get() > 30000) {
                healthy = true;
                failureCount.set(0);
            }
            return healthy;
        }
    }
    
    public record LayerStats(long hits, double avgLatencyUs, boolean healthy, int failures) {}
    
    public record CoordinatorStats(
        Map<String, LayerStats> layerStats,
        int routeDecisionCacheSize,
        int pendingWriteCount,
        int writeBehindQueueSize
    ) {}
    
    // ========== 序列化辅助 ==========
    
    private <T> String serialize(T value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        // 实际项目中使用 JSON 序列化
        return value.toString();
    }
    
    @SuppressWarnings("unchecked")
    private <T> T deserialize(String value, Class<T> type) {
        if (value == null) return null;
        if (type == String.class) return (T) value;
        // 实际项目中使用 JSON 反序列化
        return (T) value;
    }
}
