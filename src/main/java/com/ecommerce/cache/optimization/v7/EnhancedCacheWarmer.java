package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 增强缓存预热服务
 * 
 * 核心特性:
 * 1. 智能预热: 基于历史访问模式预热
 * 2. 分层预热: 按优先级分层预热
 * 3. 定时预热: 支持定时任务预热
 * 4. 流量感知: 根据流量动态调整预热策略
 * 5. 增量预热: 只预热变化的数据
 * 6. 并行预热: 多线程并行预热加速
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnhancedCacheWarmer {
    
    private final OptimizationV7Properties properties;
    private final MeterRegistry meterRegistry;
    private final AIDrivenCachePredictor aiPredictor;
    
    // ========== 预热配置 ==========
    
    /** 预热批次大小 */
    private static final int BATCH_SIZE = 100;
    
    /** 最大并行度 */
    private static final int MAX_PARALLELISM = 16;
    
    /** 预热超时(秒) */
    private static final int WARMUP_TIMEOUT = 300;
    
    // ========== 数据结构 ==========
    
    /** 预热任务队列 */
    private final PriorityBlockingQueue<WarmupTask> taskQueue = 
        new PriorityBlockingQueue<>(1000, Comparator.comparingInt(t -> -t.priority));
    
    /** 预热历史 */
    private final ConcurrentMap<String, WarmupRecord> warmupHistory = new ConcurrentHashMap<>();
    
    /** 预热策略注册表 */
    private final ConcurrentMap<String, WarmupStrategy<?>> strategies = new ConcurrentHashMap<>();
    
    /** 定时任务 */
    private final ConcurrentMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    
    /** 执行器 */
    private ExecutorService warmupExecutor;
    private ScheduledExecutorService scheduler;
    
    /** 计数器 */
    private final AtomicLong totalWarmedUp = new AtomicLong(0);
    private final AtomicLong failedWarmup = new AtomicLong(0);
    private volatile boolean warming = false;
    
    // ========== 指标 ==========
    
    private Counter warmupCounter;
    private Counter warmupFailCounter;
    
    @PostConstruct
    public void init() {
        // 初始化执行器
        warmupExecutor = Executors.newFixedThreadPool(MAX_PARALLELISM, r -> {
            Thread t = new Thread(r, "cache-warmer");
            t.setDaemon(true);
            return t;
        });
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "warmup-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化指标
        initMetrics();
        
        // 启动后台任务
        startBackgroundTasks();
        
        // 注册默认策略
        registerDefaultStrategies();
        
        log.info("[增强预热服务] 初始化完成 - 批次大小: {}, 并行度: {}", BATCH_SIZE, MAX_PARALLELISM);
    }
    
    @PreDestroy
    public void shutdown() {
        warming = false;
        scheduledTasks.values().forEach(f -> f.cancel(true));
        
        if (warmupExecutor != null) {
            warmupExecutor.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        
        log.info("[增强预热服务] 已关闭");
    }
    
    private void initMetrics() {
        warmupCounter = Counter.builder("cache.warmup.success")
            .description("预热成功次数")
            .register(meterRegistry);
        
        warmupFailCounter = Counter.builder("cache.warmup.fail")
            .description("预热失败次数")
            .register(meterRegistry);
        
        Gauge.builder("cache.warmup.queue.size", taskQueue, Queue::size)
            .description("预热队列大小")
            .register(meterRegistry);
        
        Gauge.builder("cache.warmup.total", totalWarmedUp, AtomicLong::get)
            .description("预热总数")
            .register(meterRegistry);
    }
    
    private void startBackgroundTasks() {
        // 任务处理器
        scheduler.scheduleWithFixedDelay(
            this::processWarmupTasks,
            5,
            1,
            TimeUnit.SECONDS
        );
        
        // 历史清理
        scheduler.scheduleAtFixedRate(
            this::cleanupHistory,
            60,
            60,
            TimeUnit.MINUTES
        );
        
        // 智能预热(基于AI预测)
        scheduler.scheduleAtFixedRate(
            this::smartPrefetch,
            30,
            30,
            TimeUnit.SECONDS
        );
    }
    
    private void registerDefaultStrategies() {
        // 热点数据策略
        registerStrategy("hotspot", new WarmupStrategy<String>() {
            @Override
            public List<String> getKeysToWarmup() {
                return aiPredictor.getHotKeyPredictions(100);
            }
            
            @Override
            public int getPriority() {
                return 100;
            }
        });
    }
    
    // ========== 核心API ==========
    
    /**
     * 提交预热任务
     */
    public <T> void submitWarmup(String key, Function<String, T> loader, int priority) {
        WarmupTask task = new WarmupTask(key, k -> loader.apply(k), priority);
        taskQueue.offer(task);
    }
    
    /**
     * 批量提交预热任务
     */
    public <T> void submitBatchWarmup(Collection<String> keys, Function<String, T> loader, int priority) {
        for (String key : keys) {
            submitWarmup(key, loader, priority);
        }
    }
    
    /**
     * 立即预热
     */
    public <T> CompletableFuture<Map<String, T>> warmupNow(
            Collection<String> keys, 
            Function<String, T> loader) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, T> results = new ConcurrentHashMap<>();
            
            List<CompletableFuture<Void>> futures = keys.stream()
                .map(key -> CompletableFuture.runAsync(() -> {
                    try {
                        T value = loader.apply(key);
                        if (value != null) {
                            results.put(key, value);
                            recordSuccess(key);
                        }
                    } catch (Exception e) {
                        recordFailure(key, e.getMessage());
                    }
                }, warmupExecutor))
                .toList();
            
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(WARMUP_TIMEOUT, TimeUnit.SECONDS)
                .join();
            
            return results;
        }, warmupExecutor);
    }
    
    /**
     * 注册预热策略
     */
    public <T> void registerStrategy(String name, WarmupStrategy<T> strategy) {
        strategies.put(name, strategy);
    }
    
    /**
     * 注册定时预热任务
     */
    public void scheduleWarmup(String name, WarmupStrategy<?> strategy, 
                               long initialDelay, long period, TimeUnit unit) {
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            executeStrategy(name, strategy);
        }, initialDelay, period, unit);
        
        scheduledTasks.put(name, future);
        strategies.put(name, strategy);
        
        log.info("[增强预热服务] 注册定时预热任务: {} - 周期: {}{}",
            name, period, unit.toString().toLowerCase());
    }
    
    /**
     * 注册按时间点预热
     */
    public void scheduleWarmupAt(String name, WarmupStrategy<?> strategy, LocalTime time) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime targetTime = now.with(time);
        
        if (targetTime.isBefore(now)) {
            targetTime = targetTime.plusDays(1);
        }
        
        long delay = java.time.Duration.between(now, targetTime).toMillis();
        
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            executeStrategy(name, strategy);
        }, delay, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
        
        scheduledTasks.put(name, future);
        strategies.put(name, strategy);
        
        log.info("[增强预热服务] 注册定点预热任务: {} - 时间: {}", name, time);
    }
    
    /**
     * 取消定时预热任务
     */
    public void cancelScheduledWarmup(String name) {
        ScheduledFuture<?> future = scheduledTasks.remove(name);
        if (future != null) {
            future.cancel(false);
        }
        strategies.remove(name);
    }
    
    /**
     * 手动触发策略预热
     */
    public CompletableFuture<WarmupResult> triggerStrategyWarmup(String strategyName) {
        WarmupStrategy<?> strategy = strategies.get(strategyName);
        if (strategy == null) {
            return CompletableFuture.completedFuture(WarmupResult.failed("策略不存在: " + strategyName));
        }
        
        return CompletableFuture.supplyAsync(() -> executeStrategy(strategyName, strategy), warmupExecutor);
    }
    
    /**
     * 增量预热(只预热变化的数据)
     */
    public <T> CompletableFuture<Map<String, T>> incrementalWarmup(
            Collection<String> keys,
            Function<String, Long> versionGetter,
            Function<String, T> loader) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, T> results = new ConcurrentHashMap<>();
            
            for (String key : keys) {
                WarmupRecord record = warmupHistory.get(key);
                Long currentVersion = versionGetter.apply(key);
                
                // 检查是否需要重新预热
                if (record == null || record.version == null || 
                    !record.version.equals(currentVersion)) {
                    try {
                        T value = loader.apply(key);
                        if (value != null) {
                            results.put(key, value);
                            recordSuccess(key, currentVersion);
                        }
                    } catch (Exception e) {
                        recordFailure(key, e.getMessage());
                    }
                }
            }
            
            return results;
        }, warmupExecutor);
    }
    
    // ========== 内部方法 ==========
    
    private void processWarmupTasks() {
        if (warming) return;
        warming = true;
        
        try {
            List<WarmupTask> batch = new ArrayList<>();
            taskQueue.drainTo(batch, BATCH_SIZE);
            
            if (batch.isEmpty()) return;
            
            List<CompletableFuture<Void>> futures = batch.stream()
                .map(task -> CompletableFuture.runAsync(() -> {
                    try {
                        Object result = task.loader.apply(task.key);
                        if (result != null) {
                            recordSuccess(task.key);
                        }
                    } catch (Exception e) {
                        recordFailure(task.key, e.getMessage());
                    }
                }, warmupExecutor))
                .toList();
            
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(60, TimeUnit.SECONDS)
                .exceptionally(e -> null)
                .join();
                
        } finally {
            warming = false;
        }
    }
    
    @SuppressWarnings("unchecked")
    private WarmupResult executeStrategy(String name, WarmupStrategy<?> strategy) {
        long start = System.currentTimeMillis();
        int success = 0;
        int failed = 0;
        
        try {
            List<String> keys = (List<String>) strategy.getKeysToWarmup();
            
            for (String key : keys) {
                WarmupTask task = new WarmupTask(key, k -> null, strategy.getPriority());
                if (taskQueue.offer(task)) {
                    success++;
                } else {
                    failed++;
                }
            }
            
            log.info("[增强预热服务] 策略执行完成: {} - 提交: {}, 失败: {}",
                name, success, failed);
            
            return WarmupResult.success(success, failed, System.currentTimeMillis() - start);
            
        } catch (Exception e) {
            log.error("[增强预热服务] 策略执行失败: {}", name, e);
            return WarmupResult.failed(e.getMessage());
        }
    }
    
    private void smartPrefetch() {
        // 基于AI预测进行智能预取
        List<String> hotKeys = aiPredictor.getHotKeyPredictions(50);
        
        for (String key : hotKeys) {
            if (aiPredictor.shouldPrefetch(key)) {
                submitWarmup(key, k -> null, 80);
            }
        }
    }
    
    private void recordSuccess(String key) {
        recordSuccess(key, null);
    }
    
    private void recordSuccess(String key, Long version) {
        totalWarmedUp.incrementAndGet();
        warmupCounter.increment();
        
        warmupHistory.put(key, new WarmupRecord(
            key,
            System.currentTimeMillis(),
            true,
            null,
            version
        ));
    }
    
    private void recordFailure(String key, String error) {
        failedWarmup.incrementAndGet();
        warmupFailCounter.increment();
        
        warmupHistory.put(key, new WarmupRecord(
            key,
            System.currentTimeMillis(),
            false,
            error,
            null
        ));
    }
    
    private void cleanupHistory() {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        warmupHistory.entrySet().removeIf(e -> e.getValue().timestamp < cutoff);
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("queueSize", taskQueue.size());
        stats.put("totalWarmedUp", totalWarmedUp.get());
        stats.put("failedWarmup", failedWarmup.get());
        stats.put("successRate", calculateSuccessRate());
        stats.put("registeredStrategies", strategies.keySet());
        stats.put("scheduledTasks", scheduledTasks.keySet());
        stats.put("historySize", warmupHistory.size());
        stats.put("warming", warming);
        
        // 最近预热记录
        List<Map<String, Object>> recentRecords = warmupHistory.values().stream()
            .sorted((a, b) -> Long.compare(b.timestamp, a.timestamp))
            .limit(20)
            .map(r -> {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("key", r.key);
                record.put("success", r.success);
                record.put("timestamp", new Date(r.timestamp));
                record.put("error", r.error);
                return record;
            })
            .toList();
        stats.put("recentRecords", recentRecords);
        
        return stats;
    }
    
    private String calculateSuccessRate() {
        long total = totalWarmedUp.get();
        long failed = failedWarmup.get();
        if (total + failed == 0) return "N/A";
        return String.format("%.2f%%", (double) total / (total + failed) * 100);
    }
    
    // ========== 内部类 ==========
    
    /**
     * 预热任务
     */
    @Data
    private static class WarmupTask {
        private final String key;
        private final Function<String, ?> loader;
        private final int priority;
        private final long createTime = System.currentTimeMillis();
    }
    
    /**
     * 预热记录
     */
    private record WarmupRecord(String key, long timestamp, boolean success, 
                               String error, Long version) {}
    
    /**
     * 预热策略接口
     */
    public interface WarmupStrategy<T> {
        List<String> getKeysToWarmup();
        default int getPriority() { return 50; }
    }
    
    /**
     * 预热结果
     */
    @Data
    public static class WarmupResult {
        private final boolean success;
        private final int successCount;
        private final int failedCount;
        private final long durationMs;
        private final String error;
        
        public static WarmupResult success(int successCount, int failedCount, long durationMs) {
            return new WarmupResult(true, successCount, failedCount, durationMs, null);
        }
        
        public static WarmupResult failed(String error) {
            return new WarmupResult(false, 0, 0, 0, error);
        }
    }
}
