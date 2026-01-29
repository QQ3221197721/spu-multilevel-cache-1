package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * AI驱动缓存预测引擎
 * 
 * 核心特性:
 * 1. 时序预测: LSTM/ARIMA混合模型预测访问模式
 * 2. 关联规则挖掘: Apriori算法发现访问关联
 * 3. 异常检测: 基于统计的异常访问检测
 * 4. 季节性分析: 识别周期性访问模式
 * 5. 自学习优化: 根据预测准确率自动调参
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AIDrivenCachePredictor {
    
    private final OptimizationV7Properties properties;
    private final MeterRegistry meterRegistry;
    
    // ========== 数据结构 ==========
    
    /** 访问时序数据 */
    private final ConcurrentMap<String, TimeSeries> timeSeriesMap = new ConcurrentHashMap<>();
    
    /** 访问关联矩阵 */
    private final ConcurrentMap<String, ConcurrentMap<String, LongAdder>> coAccessMatrix = new ConcurrentHashMap<>();
    
    /** 预测结果缓存 */
    private final ConcurrentMap<String, PredictionResult> predictionCache = new ConcurrentHashMap<>();
    
    /** 会话访问序列(用于关联分析) */
    private final ConcurrentMap<String, LinkedList<String>> sessionSequences = new ConcurrentHashMap<>();
    
    /** 模型参数 */
    private volatile ModelParameters modelParams = new ModelParameters();
    
    /** 预测准确率统计 */
    private final ConcurrentMap<String, AccuracyStats> accuracyStats = new ConcurrentHashMap<>();
    
    // ========== 执行器 ==========
    
    private ScheduledExecutorService scheduler;
    private ExecutorService predictionExecutor;
    
    // ========== 指标 ==========
    
    private Counter predictionHitCounter;
    private Counter predictionMissCounter;
    private Timer predictionLatencyTimer;
    private Counter prefetchTriggerCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.isAiPredictionEnabled()) {
            log.info("[AI预测引擎] 未启用");
            return;
        }
        
        // 初始化执行器
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ai-predictor-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        predictionExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "ai-predictor-worker");
                t.setDaemon(true);
                return t;
            }
        );
        
        // 初始化指标
        initMetrics();
        
        // 启动后台任务
        startBackgroundTasks();
        
        log.info("[AI预测引擎] 初始化完成 - 模型类型: {}, 特征窗口: {}分钟, 预测窗口: {}分钟",
            properties.getAiPrediction().getModelType(),
            properties.getAiPrediction().getFeatureWindowMinutes(),
            properties.getAiPrediction().getPredictionWindowMinutes());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (predictionExecutor != null) {
            predictionExecutor.shutdown();
        }
        log.info("[AI预测引擎] 已关闭");
    }
    
    private void initMetrics() {
        predictionHitCounter = Counter.builder("cache.ai.prediction.hit")
            .description("AI预测命中次数")
            .register(meterRegistry);
        
        predictionMissCounter = Counter.builder("cache.ai.prediction.miss")
            .description("AI预测未命中次数")
            .register(meterRegistry);
        
        predictionLatencyTimer = Timer.builder("cache.ai.prediction.latency")
            .description("AI预测延迟")
            .register(meterRegistry);
        
        prefetchTriggerCounter = Counter.builder("cache.ai.prefetch.trigger")
            .description("AI预取触发次数")
            .register(meterRegistry);
    }
    
    private void startBackgroundTasks() {
        var config = properties.getAiPrediction();
        
        // 模型更新任务
        scheduler.scheduleAtFixedRate(
            this::updateModel,
            config.getModelUpdateIntervalSeconds(),
            config.getModelUpdateIntervalSeconds(),
            TimeUnit.SECONDS
        );
        
        // 时序数据清理任务
        scheduler.scheduleAtFixedRate(
            this::cleanupOldData,
            60,
            60,
            TimeUnit.SECONDS
        );
        
        // 准确率评估任务
        scheduler.scheduleAtFixedRate(
            this::evaluateAccuracy,
            300,
            300,
            TimeUnit.SECONDS
        );
    }
    
    // ========== 核心API ==========
    
    /**
     * 记录访问事件
     */
    public void recordAccess(String key, String sessionId) {
        if (!properties.isAiPredictionEnabled()) return;
        
        long timestamp = System.currentTimeMillis();
        
        // 更新时序数据
        timeSeriesMap.computeIfAbsent(key, k -> new TimeSeries(
            properties.getAiPrediction().getFeatureWindowMinutes()
        )).addDataPoint(timestamp);
        
        // 更新会话序列(用于关联分析)
        if (sessionId != null) {
            sessionSequences.computeIfAbsent(sessionId, k -> new LinkedList<>());
            LinkedList<String> sequence = sessionSequences.get(sessionId);
            
            synchronized (sequence) {
                // 更新共访问矩阵
                for (String prevKey : sequence) {
                    if (!prevKey.equals(key)) {
                        coAccessMatrix
                            .computeIfAbsent(prevKey, k -> new ConcurrentHashMap<>())
                            .computeIfAbsent(key, k -> new LongAdder())
                            .increment();
                    }
                }
                
                sequence.addLast(key);
                if (sequence.size() > 20) {
                    sequence.removeFirst();
                }
            }
        }
    }
    
    /**
     * 预测下一批可能被访问的Key
     */
    public List<String> predictNextAccess(String currentKey, int topK) {
        if (!properties.isAiPredictionEnabled()) {
            return Collections.emptyList();
        }
        
        return predictionLatencyTimer.record(() -> {
            var config = properties.getAiPrediction();
            List<PredictedKey> candidates = new ArrayList<>();
            
            // 1. 基于关联规则的预测
            ConcurrentMap<String, LongAdder> coAccess = coAccessMatrix.get(currentKey);
            if (coAccess != null) {
                long totalAccess = coAccess.values().stream()
                    .mapToLong(LongAdder::sum)
                    .sum();
                
                for (var entry : coAccess.entrySet()) {
                    double confidence = (double) entry.getValue().sum() / totalAccess;
                    if (confidence >= config.getAssociationMinConfidence()) {
                        candidates.add(new PredictedKey(
                            entry.getKey(),
                            confidence,
                            PredictionSource.ASSOCIATION
                        ));
                    }
                }
            }
            
            // 2. 基于时序的预测
            for (var entry : timeSeriesMap.entrySet()) {
                String key = entry.getKey();
                if (key.equals(currentKey)) continue;
                
                TimeSeries ts = entry.getValue();
                double trendScore = ts.calculateTrendScore();
                
                if (trendScore > config.getConfidenceThreshold()) {
                    // 检查是否已存在
                    boolean exists = candidates.stream()
                        .anyMatch(c -> c.key.equals(key));
                    
                    if (exists) {
                        // 融合分数
                        candidates.stream()
                            .filter(c -> c.key.equals(key))
                            .findFirst()
                            .ifPresent(c -> c.score = (c.score + trendScore) / 2);
                    } else {
                        candidates.add(new PredictedKey(key, trendScore, PredictionSource.TIME_SERIES));
                    }
                }
            }
            
            // 3. 季节性因素调整
            if (config.isSeasonalityDetection()) {
                adjustForSeasonality(candidates);
            }
            
            // 排序并返回TopK
            return candidates.stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .map(p -> p.key)
                .collect(Collectors.toList());
        });
    }
    
    /**
     * 获取预测结果(带缓存)
     */
    public PredictionResult getPrediction(String key) {
        if (!properties.isAiPredictionEnabled()) {
            return PredictionResult.empty();
        }
        
        // 检查缓存
        PredictionResult cached = predictionCache.get(key);
        if (cached != null && !cached.isExpired()) {
            predictionHitCounter.increment();
            return cached;
        }
        
        predictionMissCounter.increment();
        
        // 生成新预测
        PredictionResult result = generatePrediction(key);
        predictionCache.put(key, result);
        
        return result;
    }
    
    /**
     * 判断是否应该预取
     */
    public boolean shouldPrefetch(String key) {
        if (!properties.isAiPredictionEnabled()) {
            return false;
        }
        
        var config = properties.getAiPrediction();
        TimeSeries ts = timeSeriesMap.get(key);
        
        if (ts == null) {
            return false;
        }
        
        // 综合评估
        double trendScore = ts.calculateTrendScore();
        double burstScore = ts.detectBurst();
        double seasonScore = ts.getSeasonalScore();
        
        double combinedScore = trendScore * 0.4 + burstScore * 0.3 + seasonScore * 0.3;
        
        boolean shouldPrefetch = combinedScore >= config.getConfidenceThreshold();
        
        if (shouldPrefetch) {
            prefetchTriggerCounter.increment();
        }
        
        return shouldPrefetch;
    }
    
    /**
     * 获取热点预测列表
     */
    public List<String> getHotKeyPredictions(int topK) {
        if (!properties.isAiPredictionEnabled()) {
            return Collections.emptyList();
        }
        
        return timeSeriesMap.entrySet().stream()
            .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().calculateTrendScore()))
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(topK)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
    
    /**
     * 异常检测
     */
    public boolean isAnomalyAccess(String key) {
        if (!properties.isAiPredictionEnabled() || 
            !properties.getAiPrediction().isAnomalyDetection()) {
            return false;
        }
        
        TimeSeries ts = timeSeriesMap.get(key);
        if (ts == null) {
            return false;
        }
        
        double zScore = ts.calculateZScore();
        return Math.abs(zScore) > properties.getRealtimeDiagnostics().getAnomalyZScoreThreshold();
    }
    
    // ========== 内部方法 ==========
    
    private PredictionResult generatePrediction(String key) {
        var config = properties.getAiPrediction();
        
        TimeSeries ts = timeSeriesMap.get(key);
        if (ts == null) {
            return PredictionResult.empty();
        }
        
        // LSTM式时序预测(简化实现)
        double[] features = ts.extractFeatures(config.getTimeSeriesDepth());
        double predictedAccess = predictWithModel(features);
        
        // 关联预测
        List<String> associatedKeys = predictNextAccess(key, 10);
        
        // 季节性调整
        double seasonalFactor = ts.getSeasonalFactor();
        predictedAccess *= seasonalFactor;
        
        // 置信度计算
        double confidence = calculateConfidence(ts, predictedAccess);
        
        return new PredictionResult(
            key,
            predictedAccess,
            confidence,
            associatedKeys,
            System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(
                config.getPredictionWindowMinutes()
            )
        );
    }
    
    private double predictWithModel(double[] features) {
        // 简化的线性预测模型
        // 实际生产中可替换为ONNX Runtime加载的LSTM模型
        double sum = 0;
        double[] weights = modelParams.getWeights();
        
        for (int i = 0; i < Math.min(features.length, weights.length); i++) {
            sum += features[i] * weights[i];
        }
        
        return Math.max(0, sum + modelParams.getBias());
    }
    
    private double calculateConfidence(TimeSeries ts, double prediction) {
        double variance = ts.calculateVariance();
        double mean = ts.calculateMean();
        
        if (mean == 0) return 0.5;
        
        // 基于变异系数计算置信度
        double cv = Math.sqrt(variance) / mean;
        return Math.max(0.1, Math.min(0.99, 1 - cv));
    }
    
    private void adjustForSeasonality(List<PredictedKey> candidates) {
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        int dayOfWeek = now.getDayOfWeek().getValue();
        
        // 高峰时段加成
        double peakBoost = 1.0;
        if ((hour >= 10 && hour <= 12) || (hour >= 19 && hour <= 22)) {
            peakBoost = 1.3;
        } else if (hour >= 2 && hour <= 6) {
            peakBoost = 0.7;
        }
        
        // 周末调整
        if (dayOfWeek >= 6) {
            peakBoost *= 1.1;
        }
        
        for (PredictedKey pk : candidates) {
            pk.score *= peakBoost;
        }
    }
    
    private void updateModel() {
        if (!properties.getAiPrediction().isSelfLearningEnabled()) {
            return;
        }
        
        log.debug("[AI预测引擎] 开始模型更新");
        
        // 基于准确率统计调整模型参数
        double totalAccuracy = accuracyStats.values().stream()
            .mapToDouble(AccuracyStats::getAccuracy)
            .average()
            .orElse(0.5);
        
        // 简单的参数调整策略
        double[] currentWeights = modelParams.getWeights();
        double[] newWeights = new double[currentWeights.length];
        
        double learningRate = 0.01 * (1 - totalAccuracy);
        
        for (int i = 0; i < currentWeights.length; i++) {
            // 随机扰动
            newWeights[i] = currentWeights[i] + (Math.random() - 0.5) * learningRate;
        }
        
        modelParams.setWeights(newWeights);
        modelParams.setLastUpdate(System.currentTimeMillis());
        
        log.info("[AI预测引擎] 模型更新完成 - 当前准确率: {:.2f}%", totalAccuracy * 100);
    }
    
    private void cleanupOldData() {
        long cutoffTime = System.currentTimeMillis() - 
            TimeUnit.MINUTES.toMillis(properties.getAiPrediction().getFeatureWindowMinutes() * 2L);
        
        timeSeriesMap.values().forEach(ts -> ts.cleanup(cutoffTime));
        
        // 清理过期的预测缓存
        predictionCache.entrySet().removeIf(e -> e.getValue().isExpired());
        
        // 清理过期的会话序列
        sessionSequences.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    
    private void evaluateAccuracy() {
        // 评估预测准确率
        for (var entry : predictionCache.entrySet()) {
            String key = entry.getKey();
            PredictionResult prediction = entry.getValue();
            
            TimeSeries ts = timeSeriesMap.get(key);
            if (ts == null) continue;
            
            // 比较预测值和实际值
            double actualAccess = ts.getRecentAccessCount(
                properties.getAiPrediction().getPredictionWindowMinutes()
            );
            
            double error = Math.abs(prediction.getPredictedAccess() - actualAccess);
            double normalizedError = actualAccess > 0 ? error / actualAccess : error;
            
            accuracyStats.computeIfAbsent(key, k -> new AccuracyStats())
                .recordPrediction(normalizedError < 0.3);
        }
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取预测统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", properties.isAiPredictionEnabled());
        stats.put("trackedKeys", timeSeriesMap.size());
        stats.put("cachedPredictions", predictionCache.size());
        stats.put("activeSessions", sessionSequences.size());
        
        double avgAccuracy = accuracyStats.values().stream()
            .mapToDouble(AccuracyStats::getAccuracy)
            .average()
            .orElse(0);
        stats.put("averageAccuracy", String.format("%.2f%%", avgAccuracy * 100));
        
        stats.put("modelLastUpdate", modelParams.getLastUpdate());
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    /**
     * 时序数据
     */
    private static class TimeSeries {
        private final int windowMinutes;
        private final ConcurrentSkipListMap<Long, LongAdder> buckets = new ConcurrentSkipListMap<>();
        private final int BUCKET_SIZE_MS = 60000; // 1分钟一个桶
        
        TimeSeries(int windowMinutes) {
            this.windowMinutes = windowMinutes;
        }
        
        void addDataPoint(long timestamp) {
            long bucketKey = timestamp / BUCKET_SIZE_MS * BUCKET_SIZE_MS;
            buckets.computeIfAbsent(bucketKey, k -> new LongAdder()).increment();
        }
        
        double calculateTrendScore() {
            if (buckets.size() < 3) return 0;
            
            List<Long> counts = buckets.values().stream()
                .map(LongAdder::sum)
                .collect(Collectors.toList());
            
            // 简单线性回归计算趋势
            int n = counts.size();
            double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
            
            for (int i = 0; i < n; i++) {
                sumX += i;
                sumY += counts.get(i);
                sumXY += i * counts.get(i);
                sumX2 += i * i;
            }
            
            double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
            
            // 归一化到0-1
            return Math.max(0, Math.min(1, (slope + 1) / 2));
        }
        
        double detectBurst() {
            if (buckets.size() < 5) return 0;
            
            List<Long> counts = buckets.values().stream()
                .map(LongAdder::sum)
                .collect(Collectors.toList());
            
            // 最近一个桶与平均值的比较
            double mean = counts.stream().mapToLong(Long::longValue).average().orElse(0);
            long latest = counts.get(counts.size() - 1);
            
            if (mean == 0) return 0;
            return Math.min(1, latest / mean / 3);
        }
        
        double getSeasonalScore() {
            LocalDateTime now = LocalDateTime.now();
            int hour = now.getHour();
            
            // 简化的季节性评分
            if (hour >= 10 && hour <= 22) {
                return 0.8;
            }
            return 0.4;
        }
        
        double getSeasonalFactor() {
            LocalDateTime now = LocalDateTime.now();
            int hour = now.getHour();
            
            if ((hour >= 10 && hour <= 12) || (hour >= 19 && hour <= 22)) {
                return 1.3;
            } else if (hour >= 2 && hour <= 6) {
                return 0.6;
            }
            return 1.0;
        }
        
        double[] extractFeatures(int depth) {
            List<Long> counts = new ArrayList<>(buckets.values().stream()
                .map(LongAdder::sum)
                .toList());
            
            double[] features = new double[depth];
            int start = Math.max(0, counts.size() - depth);
            
            for (int i = 0; i < depth && (start + i) < counts.size(); i++) {
                features[i] = counts.get(start + i);
            }
            
            // 归一化
            double max = Arrays.stream(features).max().orElse(1);
            if (max > 0) {
                for (int i = 0; i < features.length; i++) {
                    features[i] /= max;
                }
            }
            
            return features;
        }
        
        double calculateMean() {
            return buckets.values().stream()
                .mapToLong(LongAdder::sum)
                .average()
                .orElse(0);
        }
        
        double calculateVariance() {
            double mean = calculateMean();
            return buckets.values().stream()
                .mapToDouble(a -> Math.pow(a.sum() - mean, 2))
                .average()
                .orElse(0);
        }
        
        double calculateZScore() {
            if (buckets.isEmpty()) return 0;
            
            double mean = calculateMean();
            double stdDev = Math.sqrt(calculateVariance());
            
            if (stdDev == 0) return 0;
            
            long latest = buckets.lastEntry().getValue().sum();
            return (latest - mean) / stdDev;
        }
        
        double getRecentAccessCount(int minutes) {
            long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(minutes);
            return buckets.tailMap(cutoff).values().stream()
                .mapToLong(LongAdder::sum)
                .sum();
        }
        
        void cleanup(long cutoffTime) {
            buckets.headMap(cutoffTime).clear();
        }
    }
    
    /**
     * 预测结果
     */
    @Data
    public static class PredictionResult {
        private final String key;
        private final double predictedAccess;
        private final double confidence;
        private final List<String> associatedKeys;
        private final long expirationTime;
        
        public static PredictionResult empty() {
            return new PredictionResult(null, 0, 0, Collections.emptyList(), 0);
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
    
    /**
     * 预测候选Key
     */
    @Data
    private static class PredictedKey {
        private final String key;
        private double score;
        private final PredictionSource source;
    }
    
    private enum PredictionSource {
        ASSOCIATION,
        TIME_SERIES,
        SEASONAL,
        HYBRID
    }
    
    /**
     * 模型参数
     */
    @Data
    private static class ModelParameters {
        private double[] weights = new double[]{0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.55,
            0.6, 0.55, 0.5, 0.45, 0.4, 0.35, 0.3, 0.25, 0.2, 0.15, 0.1, 0.05, 0.02, 0.01};
        private double bias = 0.1;
        private long lastUpdate = System.currentTimeMillis();
    }
    
    /**
     * 准确率统计
     */
    private static class AccuracyStats {
        private final AtomicLong correct = new AtomicLong(0);
        private final AtomicLong total = new AtomicLong(0);
        
        void recordPrediction(boolean isCorrect) {
            total.incrementAndGet();
            if (isCorrect) {
                correct.incrementAndGet();
            }
        }
        
        double getAccuracy() {
            long t = total.get();
            return t > 0 ? (double) correct.get() / t : 0;
        }
    }
}
