package com.ecommerce.cache.optimization.v5;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 智能缓存预热与预取服务 - 主动式缓存填充
 * 
 * 核心功能：
 * 1. 冷启动预热 - 应用启动时预加载热点数据
 * 2. 访问模式学习 - 基于 Markov 链预测下一个访问
 * 3. 关联规则挖掘 - 发现并预取关联数据
 * 4. 时间序列预测 - 基于历史访问模式预热
 * 5. 增量预热 - 避免预热风暴
 * 6. 优先级队列 - 按热度优先预热
 * 7. 并行预热 - 虚拟线程高效预热
 * 
 * 目标：缓存命中率提升 15%，冷启动时间减少 70%
 */
@Service
public class SmartCacheWarmer {
    
    private static final Logger log = LoggerFactory.getLogger(SmartCacheWarmer.class);
    
    // ========== 配置 ==========
    @Value("${optimization.warmer.startup-batch-size:1000}")
    private int startupBatchSize;
    
    @Value("${optimization.warmer.parallel-threads:16}")
    private int parallelThreads;
    
    @Value("${optimization.warmer.prefetch-threshold:0.7}")
    private double prefetchThreshold;
    
    @Value("${optimization.warmer.max-prefetch-queue:10000}")
    private int maxPrefetchQueue;
    
    @Value("${optimization.warmer.markov-lookback:3}")
    private int markovLookback;
    
    @Value("${optimization.warmer.association-min-support:0.05}")
    private double associationMinSupport;
    
    @Value("${optimization.warmer.enabled:true}")
    private boolean enabled;
    
    // ========== 预热队列 ==========
    private final PriorityBlockingQueue<WarmupTask> warmupQueue;
    private final ConcurrentHashMap<String, Long> warmupHistory = new ConcurrentHashMap<>();
    
    // ========== Markov 链 ==========
    private final ConcurrentHashMap<String, MarkovState> markovChain = new ConcurrentHashMap<>();
    private final LinkedList<String> accessSequence = new LinkedList<>();
    private static final int MAX_SEQUENCE_LENGTH = 10000;
    
    // ========== 关联规则 ==========
    private final ConcurrentHashMap<String, Set<String>> associationRules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> itemFrequency = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>> coOccurrence = new ConcurrentHashMap<>();
    
    // ========== 时间序列 ==========
    private final ConcurrentHashMap<String, TimeSeriesData> timeSeriesData = new ConcurrentHashMap<>();
    
    // ========== 执行器 ==========
    private ExecutorService warmupExecutor;
    private ScheduledExecutorService scheduledExecutor;
    
    // ========== 统计 ==========
    private final LongAdder totalWarmedKeys = new LongAdder();
    private final LongAdder totalPrefetchedKeys = new LongAdder();
    private final LongAdder prefetchHits = new LongAdder();
    private final LongAdder prefetchMisses = new LongAdder();
    private final AtomicInteger activeWarmups = new AtomicInteger(0);
    
    // ========== 指标 ==========
    private final MeterRegistry meterRegistry;
    private Timer warmupTimer;
    private Timer prefetchTimer;
    private Counter warmupSuccessCounter;
    private Counter warmupFailureCounter;
    
    public SmartCacheWarmer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.warmupQueue = new PriorityBlockingQueue<>(1000, 
            Comparator.comparingDouble(WarmupTask::priority).reversed());
    }
    
    @PostConstruct
    public void init() {
        // 初始化执行器
        warmupExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("cache-warmer-", 0).factory()
        );
        scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "warmer-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化指标
        warmupTimer = Timer.builder("cache.warmup.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        prefetchTimer = Timer.builder("cache.prefetch.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        warmupSuccessCounter = Counter.builder("cache.warmup.success")
            .register(meterRegistry);
        warmupFailureCounter = Counter.builder("cache.warmup.failure")
            .register(meterRegistry);
        
        Gauge.builder("cache.warmup.queue.size", warmupQueue, Queue::size)
            .register(meterRegistry);
        Gauge.builder("cache.warmup.active", activeWarmups, AtomicInteger::get)
            .register(meterRegistry);
        Gauge.builder("cache.prefetch.hit.rate", this, SmartCacheWarmer::getPrefetchHitRate)
            .register(meterRegistry);
        Gauge.builder("cache.markov.states", markovChain, Map::size)
            .register(meterRegistry);
        
        // 启动预热处理器
        startWarmupProcessor();
        
        log.info("SmartCacheWarmer initialized: batchSize={}, threads={}, prefetchThreshold={}",
            startupBatchSize, parallelThreads, prefetchThreshold);
    }
    
    /**
     * 启动时批量预热
     */
    public CompletableFuture<WarmupResult> startupWarmup(List<String> keys, Supplier<Map<String, String>> dataLoader) {
        if (!enabled) {
            return CompletableFuture.completedFuture(new WarmupResult(0, 0, 0));
        }
        
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            int success = 0;
            int failed = 0;
            
            // 分批预热
            for (int i = 0; i < keys.size(); i += startupBatchSize) {
                List<String> batch = keys.subList(i, Math.min(i + startupBatchSize, keys.size()));
                
                try {
                    Map<String, String> data = dataLoader.get();
                    success += data.size();
                    totalWarmedKeys.add(data.size());
                    
                    log.debug("Startup warmup batch completed: {}/{}", i + batch.size(), keys.size());
                } catch (Exception e) {
                    failed += batch.size();
                    log.warn("Startup warmup batch failed", e);
                }
                
                // 避免预热风暴，适当休眠
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            long elapsed = System.currentTimeMillis() - start;
            log.info("Startup warmup completed: success={}, failed={}, elapsed={}ms", 
                success, failed, elapsed);
            
            return new WarmupResult(success, failed, elapsed);
        }, warmupExecutor);
    }
    
    /**
     * 记录访问（用于学习访问模式）
     */
    public void recordAccess(String key) {
        if (!enabled) return;
        
        // 更新访问序列
        synchronized (accessSequence) {
            accessSequence.addLast(key);
            if (accessSequence.size() > MAX_SEQUENCE_LENGTH) {
                accessSequence.removeFirst();
            }
            
            // 更新 Markov 链
            if (accessSequence.size() >= markovLookback + 1) {
                String prevState = buildMarkovState(accessSequence, accessSequence.size() - 1);
                MarkovState state = markovChain.computeIfAbsent(prevState, k -> new MarkovState());
                state.recordTransition(key);
            }
        }
        
        // 更新频率
        itemFrequency.computeIfAbsent(key, k -> new LongAdder()).increment();
        
        // 更新时间序列
        TimeSeriesData ts = timeSeriesData.computeIfAbsent(key, k -> new TimeSeriesData());
        ts.record();
    }
    
    /**
     * 记录关联访问（用于发现关联规则）
     */
    public void recordSessionAccess(List<String> sessionKeys) {
        if (!enabled || sessionKeys.size() < 2) return;
        
        // 更新共现矩阵
        for (int i = 0; i < sessionKeys.size(); i++) {
            String key1 = sessionKeys.get(i);
            ConcurrentHashMap<String, LongAdder> row = coOccurrence.computeIfAbsent(
                key1, k -> new ConcurrentHashMap<>());
            
            for (int j = i + 1; j < sessionKeys.size(); j++) {
                String key2 = sessionKeys.get(j);
                row.computeIfAbsent(key2, k -> new LongAdder()).increment();
            }
        }
    }
    
    /**
     * 预测下一个访问（基于 Markov 链）
     */
    public List<String> predictNextAccess(int topN) {
        if (!enabled) return Collections.emptyList();
        
        String currentState;
        synchronized (accessSequence) {
            if (accessSequence.size() < markovLookback) {
                return Collections.emptyList();
            }
            currentState = buildMarkovState(accessSequence, accessSequence.size());
        }
        
        MarkovState state = markovChain.get(currentState);
        if (state == null) {
            return Collections.emptyList();
        }
        
        return state.getPredictions(topN, prefetchThreshold);
    }
    
    /**
     * 获取关联 Key（基于关联规则）
     */
    public Set<String> getAssociatedKeys(String key) {
        if (!enabled) return Collections.emptySet();
        
        // 检查缓存的关联规则
        Set<String> cached = associationRules.get(key);
        if (cached != null) {
            return cached;
        }
        
        // 计算关联
        ConcurrentHashMap<String, LongAdder> row = coOccurrence.get(key);
        if (row == null) {
            return Collections.emptySet();
        }
        
        long keyFreq = itemFrequency.getOrDefault(key, new LongAdder()).sum();
        if (keyFreq == 0) {
            return Collections.emptySet();
        }
        
        Set<String> associated = ConcurrentHashMap.newKeySet();
        row.forEach((otherKey, counter) -> {
            double support = (double) counter.sum() / keyFreq;
            if (support >= associationMinSupport) {
                associated.add(otherKey);
            }
        });
        
        // 缓存结果
        associationRules.put(key, associated);
        
        return associated;
    }
    
    /**
     * 提交预热任务
     */
    public void submitWarmupTask(String key, double priority, Runnable warmupAction) {
        if (!enabled) return;
        
        // 检查是否最近已预热
        Long lastWarmup = warmupHistory.get(key);
        if (lastWarmup != null && System.currentTimeMillis() - lastWarmup < 60000) {
            return; // 1分钟内不重复预热
        }
        
        if (warmupQueue.size() < maxPrefetchQueue) {
            warmupQueue.offer(new WarmupTask(key, priority, warmupAction));
        }
    }
    
    /**
     * 触发预取（基于预测）
     */
    public void triggerPrefetch(String currentKey, Supplier<String> loader) {
        if (!enabled) return;
        
        prefetchTimer.record(() -> {
            // 基于 Markov 预测
            List<String> predictions = predictNextAccess(5);
            for (String predictedKey : predictions) {
                submitWarmupTask(predictedKey, 0.8, () -> {
                    try {
                        loader.get();
                        totalPrefetchedKeys.increment();
                        prefetchHits.increment();
                    } catch (Exception e) {
                        prefetchMisses.increment();
                    }
                });
            }
            
            // 基于关联规则
            Set<String> associated = getAssociatedKeys(currentKey);
            for (String assocKey : associated) {
                submitWarmupTask(assocKey, 0.6, () -> {
                    try {
                        loader.get();
                        totalPrefetchedKeys.increment();
                    } catch (Exception e) {
                        log.debug("Prefetch failed for key: {}", assocKey);
                    }
                });
            }
        });
    }
    
    /**
     * 基于时间序列的定时预热
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void scheduledWarmup() {
        if (!enabled) return;
        
        log.debug("Running scheduled warmup check");
        
        // 找出即将被访问的 Key
        long now = System.currentTimeMillis();
        int hourOfDay = (int) ((now / 3600000) % 24);
        
        timeSeriesData.forEach((key, ts) -> {
            if (ts.shouldWarmup(hourOfDay)) {
                submitWarmupTask(key, 0.5, () -> {
                    // 预热逻辑由外部提供
                    log.debug("Scheduled warmup for key: {}", key);
                });
            }
        });
    }
    
    /**
     * 定期更新关联规则
     */
    @Scheduled(fixedRate = 600000) // 10分钟
    public void updateAssociationRules() {
        if (!enabled) return;
        
        log.debug("Updating association rules");
        associationRules.clear();
        
        // 清理低频数据
        long minFreq = 10;
        itemFrequency.entrySet().removeIf(e -> e.getValue().sum() < minFreq);
        coOccurrence.entrySet().removeIf(e -> {
            e.getValue().entrySet().removeIf(inner -> inner.getValue().sum() < minFreq / 2);
            return e.getValue().isEmpty();
        });
    }
    
    /**
     * 获取预热统计
     */
    public WarmupStats getStats() {
        return new WarmupStats(
            totalWarmedKeys.sum(),
            totalPrefetchedKeys.sum(),
            prefetchHits.sum(),
            prefetchMisses.sum(),
            getPrefetchHitRate(),
            warmupQueue.size(),
            activeWarmups.get(),
            markovChain.size(),
            associationRules.size()
        );
    }
    
    // ========== 私有方法 ==========
    
    private void startWarmupProcessor() {
        for (int i = 0; i < parallelThreads; i++) {
            warmupExecutor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        WarmupTask task = warmupQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (task != null) {
                            processWarmupTask(task);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("Warmup processor error", e);
                    }
                }
            });
        }
    }
    
    private void processWarmupTask(WarmupTask task) {
        activeWarmups.incrementAndGet();
        try {
            warmupTimer.record(() -> {
                task.action.run();
                warmupHistory.put(task.key, System.currentTimeMillis());
                warmupSuccessCounter.increment();
            });
        } catch (Exception e) {
            warmupFailureCounter.increment();
            log.debug("Warmup task failed for key: {}", task.key);
        } finally {
            activeWarmups.decrementAndGet();
        }
    }
    
    private String buildMarkovState(LinkedList<String> sequence, int endIndex) {
        StringBuilder sb = new StringBuilder();
        int startIndex = Math.max(0, endIndex - markovLookback);
        for (int i = startIndex; i < endIndex; i++) {
            if (i > startIndex) sb.append("->");
            sb.append(sequence.get(i));
        }
        return sb.toString();
    }
    
    private double getPrefetchHitRate() {
        long hits = prefetchHits.sum();
        long total = hits + prefetchMisses.sum();
        return total > 0 ? (double) hits / total : 0;
    }
    
    // ========== 内部类 ==========
    
    /**
     * Markov 状态
     */
    private static class MarkovState {
        private final ConcurrentHashMap<String, LongAdder> transitions = new ConcurrentHashMap<>();
        private final AtomicLong totalTransitions = new AtomicLong(0);
        
        void recordTransition(String nextKey) {
            transitions.computeIfAbsent(nextKey, k -> new LongAdder()).increment();
            totalTransitions.incrementAndGet();
        }
        
        List<String> getPredictions(int topN, double threshold) {
            long total = totalTransitions.get();
            if (total == 0) return Collections.emptyList();
            
            return transitions.entrySet().stream()
                .filter(e -> (double) e.getValue().sum() / total >= threshold)
                .sorted(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> 
                    e.getValue().sum()).reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
        }
    }
    
    /**
     * 时间序列数据
     */
    private static class TimeSeriesData {
        private final long[] hourlyAccess = new long[24];
        private final AtomicLong lastAccess = new AtomicLong(0);
        
        void record() {
            int hour = (int) ((System.currentTimeMillis() / 3600000) % 24);
            hourlyAccess[hour]++;
            lastAccess.set(System.currentTimeMillis());
        }
        
        boolean shouldWarmup(int currentHour) {
            // 如果下一小时访问量较高，提前预热
            int nextHour = (currentHour + 1) % 24;
            long avgAccess = 0;
            for (long access : hourlyAccess) {
                avgAccess += access;
            }
            avgAccess /= 24;
            
            return hourlyAccess[nextHour] > avgAccess * 1.5;
        }
    }
    
    /**
     * 预热任务
     */
    private record WarmupTask(String key, double priority, Runnable action) {}
    
    /**
     * 预热结果
     */
    public record WarmupResult(int success, int failed, long elapsedMs) {}
    
    /**
     * 预热统计
     */
    public record WarmupStats(
        long totalWarmedKeys,
        long totalPrefetchedKeys,
        long prefetchHits,
        long prefetchMisses,
        double prefetchHitRate,
        int queueSize,
        int activeWarmups,
        int markovStates,
        int associationRules
    ) {}
}
