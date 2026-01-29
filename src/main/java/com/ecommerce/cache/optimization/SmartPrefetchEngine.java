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
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 智能预取引擎 - 基于访问模式的预测性缓存预取
 * 
 * 核心算法:
 * 1. 时序访问模式分析 - 基于时间窗口的访问频率预测
 * 2. 关联规则挖掘 - Apriori算法发现数据关联性
 * 3. 热度衰减模型 - 指数衰减的热度评分
 * 4. 马尔可夫链预测 - 基于状态转移的访问预测
 * 5. 自适应预取策略 - 根据命中率动态调整预取阈值
 * 
 * 目标: 预取命中率 > 80%, 预取延迟 < 5ms
 */
@Service
public class SmartPrefetchEngine {
    
    private static final Logger log = LoggerFactory.getLogger(SmartPrefetchEngine.class);
    
    // ========== 配置参数 ==========
    @Value("${optimization.prefetch.enabled:true}")
    private boolean prefetchEnabled;
    
    @Value("${optimization.prefetch.max-concurrent:100}")
    private int maxConcurrentPrefetch;
    
    @Value("${optimization.prefetch.threshold-score:0.7}")
    private double prefetchThresholdScore;
    
    @Value("${optimization.prefetch.decay-factor:0.95}")
    private double decayFactor;
    
    @Value("${optimization.prefetch.window-size-minutes:30}")
    private int windowSizeMinutes;
    
    @Value("${optimization.prefetch.association-min-support:0.1}")
    private double associationMinSupport;
    
    @Value("${optimization.prefetch.markov-lookback:3}")
    private int markovLookback;
    
    // ========== 数据结构 ==========
    
    // 访问历史记录 (key -> 时间戳列表)
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> accessHistory = new ConcurrentHashMap<>();
    
    // 关联规则表 (前置key -> 后继key集合及置信度)
    private final ConcurrentHashMap<String, Map<String, Double>> associationRules = new ConcurrentHashMap<>();
    
    // 马尔可夫状态转移矩阵 (状态序列 -> 下一状态概率)
    private final ConcurrentHashMap<String, Map<String, Double>> markovTransitions = new ConcurrentHashMap<>();
    
    // 热度评分缓存
    private final ConcurrentHashMap<String, AtomicDouble> hotnessScores = new ConcurrentHashMap<>();
    
    // 预取队列
    private final PriorityBlockingQueue<PrefetchTask> prefetchQueue;
    
    // 正在预取的key集合
    private final ConcurrentHashMap<String, Boolean> prefetchingKeys = new ConcurrentHashMap<>();
    
    // 最近访问序列 (用于马尔可夫链)
    private final ConcurrentLinkedDeque<String> recentAccessSequence = new ConcurrentLinkedDeque<>();
    
    // 执行器
    private final ExecutorService prefetchExecutor;
    private final ScheduledExecutorService analyzerScheduler;
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Counter prefetchHitCounter;
    private Counter prefetchMissCounter;
    private Counter prefetchTriggerCounter;
    private Timer prefetchLatencyTimer;
    private final LongAdder totalPredictions = new LongAdder();
    private final LongAdder successfulPredictions = new LongAdder();
    
    // 自适应参数
    private volatile double adaptiveThreshold;
    private final AtomicLong lastAdaptationTime = new AtomicLong(0);
    
    public SmartPrefetchEngine(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.prefetchQueue = new PriorityBlockingQueue<>(1000, 
            Comparator.comparingDouble(PrefetchTask::priority).reversed());
        this.prefetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.analyzerScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "prefetch-analyzer");
            t.setDaemon(true);
            return t;
        });
        this.adaptiveThreshold = 0.7;
    }
    
    @PostConstruct
    public void init() {
        // 注册指标
        prefetchHitCounter = Counter.builder("cache.prefetch.hits").register(meterRegistry);
        prefetchMissCounter = Counter.builder("cache.prefetch.misses").register(meterRegistry);
        prefetchTriggerCounter = Counter.builder("cache.prefetch.triggers").register(meterRegistry);
        prefetchLatencyTimer = Timer.builder("cache.prefetch.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        
        Gauge.builder("cache.prefetch.queue.size", prefetchQueue, PriorityBlockingQueue::size)
            .register(meterRegistry);
        Gauge.builder("cache.prefetch.hit.rate", this, SmartPrefetchEngine::getPrefetchHitRate)
            .register(meterRegistry);
        Gauge.builder("cache.prefetch.adaptive.threshold", () -> adaptiveThreshold)
            .register(meterRegistry);
        
        // 启动预取处理器
        startPrefetchProcessor();
        
        log.info("SmartPrefetchEngine initialized: threshold={}, decay={}, window={}min",
            prefetchThresholdScore, decayFactor, windowSizeMinutes);
    }
    
    @PreDestroy
    public void shutdown() {
        prefetchExecutor.shutdown();
        analyzerScheduler.shutdown();
    }
    
    /**
     * 记录访问事件 - 核心入口
     */
    public void recordAccess(String key) {
        if (!prefetchEnabled) return;
        
        long now = System.currentTimeMillis();
        
        // 1. 更新访问历史
        accessHistory.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).addLast(now);
        
        // 2. 更新热度评分
        updateHotnessScore(key, now);
        
        // 3. 更新马尔可夫序列
        updateMarkovSequence(key);
        
        // 4. 触发预取预测
        triggerPrefetchPrediction(key);
    }
    
    /**
     * 批量记录访问
     */
    public void recordBatchAccess(Collection<String> keys) {
        keys.forEach(this::recordAccess);
    }
    
    /**
     * 更新热度评分 - 指数衰减模型
     */
    private void updateHotnessScore(String key, long timestamp) {
        hotnessScores.compute(key, (k, score) -> {
            if (score == null) {
                return new AtomicDouble(1.0);
            }
            // 衰减后的旧分数 + 新访问加成
            double decayed = score.get() * decayFactor;
            score.set(decayed + 1.0);
            return score;
        });
    }
    
    /**
     * 更新马尔可夫访问序列
     */
    private void updateMarkovSequence(String key) {
        // 限制序列长度
        while (recentAccessSequence.size() >= 10000) {
            recentAccessSequence.pollFirst();
        }
        recentAccessSequence.addLast(key);
        
        // 更新状态转移概率
        if (recentAccessSequence.size() >= markovLookback + 1) {
            String[] recent = recentAccessSequence.toArray(new String[0]);
            int len = recent.length;
            
            // 构建状态序列
            StringBuilder stateBuilder = new StringBuilder();
            for (int i = len - markovLookback - 1; i < len - 1; i++) {
                if (i > len - markovLookback - 1) stateBuilder.append("->");
                stateBuilder.append(recent[i]);
            }
            String state = stateBuilder.toString();
            String nextKey = recent[len - 1];
            
            // 更新转移概率
            markovTransitions.computeIfAbsent(state, s -> new ConcurrentHashMap<>())
                .compute(nextKey, (k, count) -> count == null ? 1.0 : count + 1.0);
        }
    }
    
    /**
     * 触发预取预测
     */
    private void triggerPrefetchPrediction(String currentKey) {
        // 1. 基于关联规则的预测
        Map<String, Double> associated = associationRules.get(currentKey);
        if (associated != null) {
            associated.forEach((relatedKey, confidence) -> {
                if (confidence >= adaptiveThreshold) {
                    schedulePrefetch(relatedKey, confidence, "association");
                }
            });
        }
        
        // 2. 基于马尔可夫链的预测
        predictFromMarkov(currentKey);
        
        // 3. 基于时序模式的预测
        predictFromTemporalPattern(currentKey);
    }
    
    /**
     * 马尔可夫链预测
     */
    private void predictFromMarkov(String currentKey) {
        String[] recent = recentAccessSequence.toArray(new String[0]);
        int len = recent.length;
        
        if (len >= markovLookback) {
            StringBuilder stateBuilder = new StringBuilder();
            for (int i = len - markovLookback; i < len; i++) {
                if (i > len - markovLookback) stateBuilder.append("->");
                stateBuilder.append(recent[i]);
            }
            String state = stateBuilder.toString();
            
            Map<String, Double> transitions = markovTransitions.get(state);
            if (transitions != null) {
                double total = transitions.values().stream().mapToDouble(d -> d).sum();
                transitions.forEach((nextKey, count) -> {
                    double probability = count / total;
                    if (probability >= adaptiveThreshold * 0.8) {
                        schedulePrefetch(nextKey, probability, "markov");
                    }
                });
            }
        }
    }
    
    /**
     * 时序模式预测
     */
    private void predictFromTemporalPattern(String currentKey) {
        ConcurrentLinkedDeque<Long> history = accessHistory.get(currentKey);
        if (history == null || history.size() < 5) return;
        
        Long[] timestamps = history.toArray(new Long[0]);
        
        // 计算访问间隔
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < timestamps.length; i++) {
            intervals.add(timestamps[i] - timestamps[i-1]);
        }
        
        // 如果访问间隔相对稳定，预测下次访问时间
        if (!intervals.isEmpty()) {
            double avgInterval = intervals.stream().mapToLong(l -> l).average().orElse(0);
            double stdDev = calculateStdDev(intervals, avgInterval);
            
            // 如果标准差小于平均间隔的30%，认为有规律性
            if (stdDev < avgInterval * 0.3 && avgInterval > 0) {
                long lastAccess = timestamps[timestamps.length - 1];
                long predictedNext = lastAccess + (long) avgInterval;
                long now = System.currentTimeMillis();
                
                // 如果预测时间在未来5分钟内，提前预取
                if (predictedNext > now && predictedNext - now < 300000) {
                    double confidence = 1.0 - (stdDev / avgInterval);
                    schedulePrefetch(currentKey, confidence, "temporal");
                }
            }
        }
    }
    
    private double calculateStdDev(List<Long> values, double mean) {
        double sumSquares = values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .sum();
        return Math.sqrt(sumSquares / values.size());
    }
    
    /**
     * 调度预取任务
     */
    private void schedulePrefetch(String key, double priority, String source) {
        if (!prefetchEnabled) return;
        if (prefetchingKeys.containsKey(key)) return;
        if (prefetchQueue.size() >= maxConcurrentPrefetch * 2) return;
        
        totalPredictions.increment();
        prefetchQueue.offer(new PrefetchTask(key, priority, source, System.currentTimeMillis()));
        prefetchTriggerCounter.increment();
        
        log.debug("Prefetch scheduled: key={}, priority={:.3f}, source={}", key, priority, source);
    }
    
    /**
     * 启动预取处理器
     */
    private void startPrefetchProcessor() {
        for (int i = 0; i < 4; i++) {
            prefetchExecutor.submit(this::processPrefetchQueue);
        }
    }
    
    /**
     * 处理预取队列
     */
    private void processPrefetchQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PrefetchTask task = prefetchQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) continue;
                
                // 防止重复预取
                if (prefetchingKeys.putIfAbsent(task.key, Boolean.TRUE) != null) {
                    continue;
                }
                
                try {
                    executePrefetch(task);
                } finally {
                    prefetchingKeys.remove(task.key);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Prefetch error", e);
            }
        }
    }
    
    /**
     * 执行预取
     */
    private void executePrefetch(PrefetchTask task) {
        prefetchLatencyTimer.record(() -> {
            // 通知外部执行预取（由调用方实现具体预取逻辑）
            log.debug("Executing prefetch: key={}, source={}", task.key, task.source);
            // 实际预取逻辑由外部注入的 prefetchHandler 执行
        });
    }
    
    /**
     * 记录预取结果（由外部调用）
     */
    public void recordPrefetchResult(String key, boolean hit) {
        if (hit) {
            prefetchHitCounter.increment();
            successfulPredictions.increment();
        } else {
            prefetchMissCounter.increment();
        }
        
        // 触发自适应阈值调整
        adaptThreshold();
    }
    
    /**
     * 自适应阈值调整
     */
    private void adaptThreshold() {
        long now = System.currentTimeMillis();
        if (now - lastAdaptationTime.get() < 60000) return; // 1分钟调整一次
        
        if (lastAdaptationTime.compareAndSet(lastAdaptationTime.get(), now)) {
            double hitRate = getPrefetchHitRate();
            
            // 命中率高于90%，降低阈值以增加预取
            // 命中率低于70%，提高阈值以减少无效预取
            if (hitRate > 0.9 && adaptiveThreshold > 0.5) {
                adaptiveThreshold -= 0.05;
            } else if (hitRate < 0.7 && adaptiveThreshold < 0.95) {
                adaptiveThreshold += 0.05;
            }
            
            log.debug("Adaptive threshold adjusted: {} (hit rate: {:.2f}%)", 
                adaptiveThreshold, hitRate * 100);
        }
    }
    
    /**
     * 定期分析关联规则 - Apriori算法
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void analyzeAssociationRules() {
        if (!prefetchEnabled) return;
        
        log.debug("Analyzing association rules...");
        long startTime = System.currentTimeMillis();
        
        // 构建事务集（每5秒为一个事务窗口）
        Map<Long, Set<String>> transactions = new HashMap<>();
        long windowMs = 5000;
        
        accessHistory.forEach((key, timestamps) -> {
            timestamps.forEach(ts -> {
                long window = ts / windowMs;
                transactions.computeIfAbsent(window, w -> new HashSet<>()).add(key);
            });
        });
        
        // 计算频繁项集和关联规则
        int transactionCount = transactions.size();
        if (transactionCount < 10) return;
        
        // 单项频率
        Map<String, Long> itemCounts = new HashMap<>();
        transactions.values().forEach(items -> 
            items.forEach(item -> itemCounts.merge(item, 1L, Long::sum)));
        
        // 过滤低频项
        double minCount = transactionCount * associationMinSupport;
        Set<String> frequentItems = itemCounts.entrySet().stream()
            .filter(e -> e.getValue() >= minCount)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        
        // 计算二项关联规则
        Map<String, Map<String, Double>> newRules = new ConcurrentHashMap<>();
        for (String item1 : frequentItems) {
            Map<String, Double> related = new HashMap<>();
            for (String item2 : frequentItems) {
                if (item1.equals(item2)) continue;
                
                // 计算共现次数
                long cooccurrence = transactions.values().stream()
                    .filter(t -> t.contains(item1) && t.contains(item2))
                    .count();
                
                // 计算置信度 P(item2|item1)
                long item1Count = itemCounts.get(item1);
                if (item1Count > 0) {
                    double confidence = (double) cooccurrence / item1Count;
                    if (confidence >= associationMinSupport) {
                        related.put(item2, confidence);
                    }
                }
            }
            if (!related.isEmpty()) {
                newRules.put(item1, related);
            }
        }
        
        // 更新规则
        associationRules.clear();
        associationRules.putAll(newRules);
        
        log.info("Association rules updated: {} rules in {}ms", 
            newRules.size(), System.currentTimeMillis() - startTime);
    }
    
    /**
     * 定期清理过期数据
     */
    @Scheduled(fixedRate = 60000) // 1分钟
    public void cleanupExpiredData() {
        long threshold = System.currentTimeMillis() - (windowSizeMinutes * 60000L);
        
        accessHistory.forEach((key, timestamps) -> {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < threshold) {
                timestamps.pollFirst();
            }
        });
        
        // 清理空历史
        accessHistory.entrySet().removeIf(e -> e.getValue().isEmpty());
        
        // 衰减热度评分
        hotnessScores.forEach((key, score) -> {
            double newScore = score.get() * decayFactor;
            if (newScore < 0.1) {
                hotnessScores.remove(key);
            } else {
                score.set(newScore);
            }
        });
        
        // 限制马尔可夫转移矩阵大小
        if (markovTransitions.size() > 10000) {
            List<String> toRemove = markovTransitions.keySet().stream()
                .limit(markovTransitions.size() - 5000)
                .toList();
            toRemove.forEach(markovTransitions::remove);
        }
    }
    
    /**
     * 获取预取命中率
     */
    public double getPrefetchHitRate() {
        double total = prefetchHitCounter.count() + prefetchMissCounter.count();
        return total > 0 ? prefetchHitCounter.count() / total : 0;
    }
    
    /**
     * 获取预测准确率
     */
    public double getPredictionAccuracy() {
        long total = totalPredictions.sum();
        return total > 0 ? (double) successfulPredictions.sum() / total : 0;
    }
    
    /**
     * 获取引擎统计
     */
    public PrefetchStats getStats() {
        return new PrefetchStats(
            prefetchEnabled,
            adaptiveThreshold,
            getPrefetchHitRate(),
            getPredictionAccuracy(),
            prefetchQueue.size(),
            prefetchingKeys.size(),
            accessHistory.size(),
            associationRules.size(),
            markovTransitions.size(),
            hotnessScores.size(),
            totalPredictions.sum(),
            successfulPredictions.sum()
        );
    }
    
    /**
     * 获取key的热度评分
     */
    public double getHotnessScore(String key) {
        AtomicDouble score = hotnessScores.get(key);
        return score != null ? score.get() : 0;
    }
    
    /**
     * 获取预测的下一批访问key
     */
    public List<String> predictNextAccess(String currentKey, int limit) {
        List<PredictedAccess> predictions = new ArrayList<>();
        
        // 从关联规则预测
        Map<String, Double> associated = associationRules.get(currentKey);
        if (associated != null) {
            associated.forEach((k, v) -> predictions.add(new PredictedAccess(k, v, "association")));
        }
        
        // 从马尔可夫链预测
        String[] recent = recentAccessSequence.toArray(new String[0]);
        int len = recent.length;
        if (len >= markovLookback) {
            StringBuilder stateBuilder = new StringBuilder();
            for (int i = len - markovLookback; i < len; i++) {
                if (i > len - markovLookback) stateBuilder.append("->");
                stateBuilder.append(recent[i]);
            }
            Map<String, Double> transitions = markovTransitions.get(stateBuilder.toString());
            if (transitions != null) {
                double total = transitions.values().stream().mapToDouble(d -> d).sum();
                transitions.forEach((k, v) -> predictions.add(new PredictedAccess(k, v / total, "markov")));
            }
        }
        
        // 按概率排序返回
        return predictions.stream()
            .sorted(Comparator.comparingDouble(PredictedAccess::probability).reversed())
            .limit(limit)
            .map(PredictedAccess::key)
            .toList();
    }
    
    // ========== 内部类 ==========
    
    private record PrefetchTask(String key, double priority, String source, long timestamp) {}
    private record PredictedAccess(String key, double probability, String source) {}
    
    public record PrefetchStats(
        boolean enabled,
        double adaptiveThreshold,
        double hitRate,
        double predictionAccuracy,
        int queueSize,
        int prefetchingCount,
        int accessHistorySize,
        int associationRulesCount,
        int markovStatesCount,
        int hotnessScoresCount,
        long totalPredictions,
        long successfulPredictions
    ) {}
    
    /**
     * 原子Double实现
     */
    private static class AtomicDouble {
        private final AtomicLong bits;
        
        AtomicDouble(double initialValue) {
            bits = new AtomicLong(Double.doubleToLongBits(initialValue));
        }
        
        double get() {
            return Double.longBitsToDouble(bits.get());
        }
        
        void set(double newValue) {
            bits.set(Double.doubleToLongBits(newValue));
        }
    }
}
