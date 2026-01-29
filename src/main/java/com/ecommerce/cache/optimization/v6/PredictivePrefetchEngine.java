package com.ecommerce.cache.optimization.v6;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/**
 * 智能预测预取引擎 - 提前加载
 * 
 * 核心特性:
 * 1. 马尔可夫链访问预测
 * 2. 时序模式识别
 * 3. 关联规则挖掘
 * 4. 滑动窗口学习
 * 5. 自适应预取阈值
 * 6. 预取优先级队列
 * 
 * 性能目标:
 * - 预测准确率 > 80%
 * - 预取命中率 > 70%
 * - 额外延迟 < 5ms
 */
public class PredictivePrefetchEngine {
    
    private static final Logger log = LoggerFactory.getLogger(PredictivePrefetchEngine.class);
    
    @Value("${optimization.v6.prefetch.enabled:true}")
    private boolean enabled = true;
    
    @Value("${optimization.v6.prefetch.max-concurrent:50}")
    private int maxConcurrent = 50;
    
    @Value("${optimization.v6.prefetch.threshold:0.6}")
    private double prefetchThreshold = 0.6;
    
    @Value("${optimization.v6.prefetch.markov-depth:3}")
    private int markovDepth = 3;
    
    @Value("${optimization.v6.prefetch.association-min-support:0.1}")
    private double associationMinSupport = 0.1;
    
    @Value("${optimization.v6.prefetch.window-size-minutes:30}")
    private int windowSizeMinutes = 30;
    
    @Value("${optimization.v6.prefetch.max-prefetch-queue:5000}")
    private int maxPrefetchQueue = 5000;
    
    @Value("${optimization.v6.prefetch.prefetch-ttl-seconds:300}")
    private long prefetchTtlSeconds = 300;
    
    private final MeterRegistry meterRegistry;
    
    // 马尔可夫转移矩阵
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>> transitionMatrix = new ConcurrentHashMap<>();
    
    // 关联规则
    private final ConcurrentHashMap<String, Set<String>> associationRules = new ConcurrentHashMap<>();
    
    // 时序模式
    private final ConcurrentLinkedDeque<AccessEvent> accessHistory = new ConcurrentLinkedDeque<>();
    
    // 预取缓存
    private final ConcurrentHashMap<String, PrefetchedEntry> prefetchCache = new ConcurrentHashMap<>();
    
    // 预取队列
    private final PriorityBlockingQueue<PrefetchTask> prefetchQueue = new PriorityBlockingQueue<>();
    
    // 当前会话轨迹
    private final ConcurrentHashMap<String, LinkedList<String>> sessionTraces = new ConcurrentHashMap<>();
    
    // 信号量控制并发
    private final Semaphore prefetchSemaphore;
    
    // 统计
    private final LongAdder totalPredictions = new LongAdder();
    private final LongAdder successfulPredictions = new LongAdder();
    private final LongAdder prefetchHits = new LongAdder();
    private final LongAdder prefetchMisses = new LongAdder();
    private final LongAdder prefetchExecutions = new LongAdder();
    
    // 执行器
    private ExecutorService prefetchExecutor;
    private ScheduledExecutorService scheduledExecutor;
    
    // 指标
    private Timer predictionTimer;
    private Timer prefetchTimer;
    private Counter prefetchHitCounter;
    
    public PredictivePrefetchEngine(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.prefetchSemaphore = new Semaphore(maxConcurrent);
    }
    
    @PostConstruct
    public void init() {
        // 初始化执行器
        prefetchExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("prefetch-", 0).factory());
        scheduledExecutor = Executors.newScheduledThreadPool(2,
            Thread.ofVirtual().name("prefetch-sched-", 0).factory());
        
        // 启动预取消费者
        for (int i = 0; i < 3; i++) {
            prefetchExecutor.submit(this::prefetchConsumer);
        }
        
        // 定时清理
        scheduledExecutor.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
        
        // 定时更新关联规则
        scheduledExecutor.scheduleAtFixedRate(this::updateAssociationRules, 5, 5, TimeUnit.MINUTES);
        
        // 注册指标
        registerMetrics();
        
        log.info("PredictivePrefetchEngine initialized: threshold={}, maxConcurrent={}, markovDepth={}",
            prefetchThreshold, maxConcurrent, markovDepth);
    }
    
    @PreDestroy
    public void shutdown() {
        if (prefetchExecutor != null) {
            prefetchExecutor.shutdown();
        }
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
    }
    
    private void registerMetrics() {
        predictionTimer = Timer.builder("cache.prefetch.prediction.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        prefetchTimer = Timer.builder("cache.prefetch.execution.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        prefetchHitCounter = Counter.builder("cache.prefetch.hits")
            .register(meterRegistry);
        
        Gauge.builder("cache.prefetch.queue.size", prefetchQueue, Queue::size)
            .register(meterRegistry);
        Gauge.builder("cache.prefetch.cache.size", prefetchCache, Map::size)
            .register(meterRegistry);
        Gauge.builder("cache.prefetch.accuracy", this, PredictivePrefetchEngine::calculateAccuracy)
            .register(meterRegistry);
    }
    
    /**
     * 记录访问（用于学习）
     */
    public void recordAccess(String key) {
        if (!enabled) return;
        
        long now = System.currentTimeMillis();
        
        // 记录访问事件
        accessHistory.addLast(new AccessEvent(key, now));
        
        // 更新会话轨迹
        String sessionId = getSessionId();
        LinkedList<String> trace = sessionTraces.computeIfAbsent(
            sessionId, k -> new LinkedList<>());
        
        synchronized (trace) {
            if (!trace.isEmpty()) {
                // 更新马尔可夫转移矩阵
                String prevKey = trace.getLast();
                updateTransitionMatrix(prevKey, key);
            }
            
            trace.addLast(key);
            while (trace.size() > markovDepth) {
                trace.removeFirst();
            }
        }
        
        // 检查预取缓存命中
        if (prefetchCache.containsKey(key)) {
            prefetchHits.increment();
            prefetchHitCounter.increment();
            successfulPredictions.increment();
        } else {
            prefetchMisses.increment();
        }
    }
    
    /**
     * 获取预取的数据
     */
    public String getPrefetched(String key) {
        PrefetchedEntry entry = prefetchCache.get(key);
        if (entry != null && !entry.isExpired()) {
            prefetchHits.increment();
            prefetchHitCounter.increment();
            return entry.value;
        }
        return null;
    }
    
    /**
     * 触发关联预取
     */
    public void triggerAssociatedPrefetch(String key, Supplier<String> loader) {
        if (!enabled) return;
        
        predictionTimer.record(() -> {
            // 基于马尔可夫链预测
            Set<String> predictions = predictNextKeys(key);
            
            // 基于关联规则预测
            Set<String> associated = associationRules.getOrDefault(key, Collections.emptySet());
            predictions.addAll(associated);
            
            // 加入预取队列
            for (String predictedKey : predictions) {
                if (!prefetchCache.containsKey(predictedKey)) {
                    double score = calculatePredictionScore(key, predictedKey);
                    if (score >= prefetchThreshold) {
                        enqueuePrefetch(predictedKey, score, loader);
                    }
                }
            }
            
            totalPredictions.add(predictions.size());
        });
    }
    
    /**
     * 触发预取
     */
    public void triggerPrefetch(String key, Supplier<String> loader) {
        triggerAssociatedPrefetch(key, loader);
    }
    
    /**
     * 基于马尔可夫链预测下一个Key
     */
    private Set<String> predictNextKeys(String currentKey) {
        Set<String> predictions = new HashSet<>();
        
        ConcurrentHashMap<String, LongAdder> transitions = transitionMatrix.get(currentKey);
        if (transitions == null || transitions.isEmpty()) {
            return predictions;
        }
        
        // 计算总转移次数
        long total = transitions.values().stream()
            .mapToLong(LongAdder::sum)
            .sum();
        
        if (total == 0) return predictions;
        
        // 选择概率超过阈值的目标
        for (Map.Entry<String, LongAdder> entry : transitions.entrySet()) {
            double probability = (double) entry.getValue().sum() / total;
            if (probability >= prefetchThreshold * 0.5) {
                predictions.add(entry.getKey());
            }
        }
        
        return predictions;
    }
    
    /**
     * 计算预测得分
     */
    private double calculatePredictionScore(String fromKey, String toKey) {
        ConcurrentHashMap<String, LongAdder> transitions = transitionMatrix.get(fromKey);
        if (transitions == null) return 0;
        
        LongAdder toCount = transitions.get(toKey);
        if (toCount == null) return 0;
        
        long total = transitions.values().stream()
            .mapToLong(LongAdder::sum)
            .sum();
        
        return total > 0 ? (double) toCount.sum() / total : 0;
    }
    
    /**
     * 更新转移矩阵
     */
    private void updateTransitionMatrix(String fromKey, String toKey) {
        transitionMatrix.computeIfAbsent(fromKey, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(toKey, k -> new LongAdder())
            .increment();
    }
    
    /**
     * 加入预取队列
     */
    private void enqueuePrefetch(String key, double score, Supplier<String> loader) {
        if (prefetchQueue.size() >= maxPrefetchQueue) {
            return;
        }
        
        PrefetchTask task = new PrefetchTask(key, score, loader, System.currentTimeMillis());
        prefetchQueue.offer(task);
    }
    
    /**
     * 预取消费者
     */
    private void prefetchConsumer() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PrefetchTask task = prefetchQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) continue;
                
                // 检查是否过期
                if (System.currentTimeMillis() - task.createTime > 5000) {
                    continue;
                }
                
                // 检查是否已预取
                if (prefetchCache.containsKey(task.key)) {
                    continue;
                }
                
                // 执行预取
                if (prefetchSemaphore.tryAcquire(10, TimeUnit.MILLISECONDS)) {
                    try {
                        executePrefetch(task);
                    } finally {
                        prefetchSemaphore.release();
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Prefetch consumer error", e);
            }
        }
    }
    
    /**
     * 执行预取
     */
    private void executePrefetch(PrefetchTask task) {
        prefetchTimer.record(() -> {
            try {
                String value = task.loader.get();
                if (value != null) {
                    prefetchCache.put(task.key, new PrefetchedEntry(
                        value, System.currentTimeMillis() + prefetchTtlSeconds * 1000));
                    prefetchExecutions.increment();
                    log.debug("Prefetched: key={}, score={}", task.key, task.score);
                }
            } catch (Exception e) {
                log.debug("Prefetch failed: key={}", task.key);
            }
        });
    }
    
    /**
     * 更新关联规则
     */
    private void updateAssociationRules() {
        // 使用简化的Apriori算法
        Map<String, LongAdder> itemCounts = new ConcurrentHashMap<>();
        Map<String, Map<String, LongAdder>> pairCounts = new ConcurrentHashMap<>();
        
        // 统计窗口内的访问
        long windowStart = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(windowSizeMinutes);
        
        List<String> windowKeys = new ArrayList<>();
        for (AccessEvent event : accessHistory) {
            if (event.timestamp >= windowStart) {
                windowKeys.add(event.key);
                itemCounts.computeIfAbsent(event.key, k -> new LongAdder()).increment();
            }
        }
        
        int totalTransactions = windowKeys.size();
        if (totalTransactions < 100) return;
        
        // 统计共现
        for (int i = 0; i < windowKeys.size() - 1; i++) {
            String key1 = windowKeys.get(i);
            for (int j = i + 1; j < Math.min(i + 5, windowKeys.size()); j++) {
                String key2 = windowKeys.get(j);
                if (!key1.equals(key2)) {
                    pairCounts.computeIfAbsent(key1, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(key2, k -> new LongAdder()).increment();
                }
            }
        }
        
        // 生成规则
        for (Map.Entry<String, Map<String, LongAdder>> entry : pairCounts.entrySet()) {
            String key1 = entry.getKey();
            LongAdder key1Count = itemCounts.get(key1);
            if (key1Count == null) continue;
            
            Set<String> associated = new HashSet<>();
            for (Map.Entry<String, LongAdder> pairEntry : entry.getValue().entrySet()) {
                double support = (double) pairEntry.getValue().sum() / totalTransactions;
                double confidence = (double) pairEntry.getValue().sum() / key1Count.sum();
                
                if (support >= associationMinSupport && confidence >= prefetchThreshold) {
                    associated.add(pairEntry.getKey());
                }
            }
            
            if (!associated.isEmpty()) {
                associationRules.put(key1, associated);
            }
        }
        
        log.debug("Updated association rules: {} rules", associationRules.size());
    }
    
    /**
     * 清理过期数据
     */
    private void cleanup() {
        long now = System.currentTimeMillis();
        long windowStart = now - TimeUnit.MINUTES.toMillis(windowSizeMinutes);
        
        // 清理访问历史
        while (!accessHistory.isEmpty()) {
            AccessEvent first = accessHistory.peekFirst();
            if (first != null && first.timestamp < windowStart) {
                accessHistory.pollFirst();
            } else {
                break;
            }
        }
        
        // 清理预取缓存
        prefetchCache.entrySet().removeIf(e -> e.getValue().isExpired());
        
        // 清理过期会话
        sessionTraces.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    
    /**
     * 获取会话ID（简化实现）
     */
    private String getSessionId() {
        return "default"; // 实际应该从上下文获取
    }
    
    /**
     * 计算预测准确率
     */
    public double calculateAccuracy() {
        long hits = prefetchHits.sum();
        long misses = prefetchMisses.sum();
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0;
    }
    
    /**
     * 获取统计信息
     */
    public PrefetchStats getStats() {
        return new PrefetchStats(
            totalPredictions.sum(),
            successfulPredictions.sum(),
            prefetchHits.sum(),
            prefetchMisses.sum(),
            prefetchExecutions.sum(),
            calculateAccuracy(),
            prefetchQueue.size(),
            prefetchCache.size(),
            transitionMatrix.size(),
            associationRules.size()
        );
    }
    
    // ========== 内部类 ==========
    
    private record AccessEvent(String key, long timestamp) {}
    
    private record PrefetchedEntry(String value, long expireTime) {
        boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }
    
    private record PrefetchTask(
        String key, 
        double score, 
        Supplier<String> loader,
        long createTime
    ) implements Comparable<PrefetchTask> {
        @Override
        public int compareTo(PrefetchTask other) {
            return Double.compare(other.score, this.score); // 高分优先
        }
    }
    
    public record PrefetchStats(
        long totalPredictions,
        long successfulPredictions,
        long prefetchHits,
        long prefetchMisses,
        long prefetchExecutions,
        double accuracy,
        int queueSize,
        int cacheSize,
        int transitionMatrixSize,
        int associationRulesCount
    ) {}
}
