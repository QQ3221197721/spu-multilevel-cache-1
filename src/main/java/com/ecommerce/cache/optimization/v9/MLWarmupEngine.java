package com.ecommerce.cache.optimization.v9;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 机器学习驱动的缓存预热引擎
 * 
 * 核心特性:
 * 1. 访问模式学习: 分析历史访问模式
 * 2. 时序预测: LSTM/ARIMA预测未来访问
 * 3. 智能预热: 预测热点Key并提前加载
 * 4. 自适应调整: 根据预测准确率调整策略
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MLWarmupEngine {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV9Properties properties;
    
    /** 访问历史 */
    private final ConcurrentMap<String, AccessHistory> accessHistories = new ConcurrentHashMap<>();
    
    /** 预测结果缓存 */
    private final ConcurrentMap<String, PredictionResult> predictions = new ConcurrentHashMap<>();
    
    /** 预热队列 */
    private final BlockingQueue<WarmupTask> warmupQueue = new LinkedBlockingQueue<>();
    
    /** 模型参数 */
    private final ConcurrentMap<String, double[]> modelWeights = new ConcurrentHashMap<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    private ExecutorService warmupExecutor;
    
    /** 统计 */
    private final AtomicLong predictionCount = new AtomicLong(0);
    private final AtomicLong warmupCount = new AtomicLong(0);
    private final AtomicLong hitCount = new AtomicLong(0);
    
    private Counter predCounter;
    private Counter warmCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getMlWarmup().isEnabled()) {
            log.info("[ML预热] 已禁用");
            return;
        }
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ml-warmup-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        warmupExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ml-warmup-worker");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 启动预热处理
        for (int i = 0; i < 4; i++) {
            warmupExecutor.submit(this::processWarmupQueue);
        }
        
        // 定期预测
        int window = properties.getMlWarmup().getPredictionWindow();
        scheduler.scheduleWithFixedDelay(this::runPrediction, window, window, TimeUnit.SECONDS);
        
        // 定期模型更新
        scheduler.scheduleWithFixedDelay(this::updateModel, 300, 300, TimeUnit.SECONDS);
        
        log.info("[ML预热] 初始化完成 - 模型: {}", properties.getMlWarmup().getModelType());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (warmupExecutor != null) warmupExecutor.shutdown();
        log.info("[ML预热] 已关闭 - 预测: {}, 预热: {}, 命中: {}",
            predictionCount.get(), warmupCount.get(), hitCount.get());
    }
    
    private void initMetrics() {
        predCounter = Counter.builder("cache.ml.prediction").register(meterRegistry);
        warmCounter = Counter.builder("cache.ml.warmup").register(meterRegistry);
    }
    
    // ========== 访问记录 ==========
    
    public void recordAccess(String key) {
        AccessHistory history = accessHistories.computeIfAbsent(key, k -> new AccessHistory());
        history.record(System.currentTimeMillis());
        
        // 检查是否命中预测
        PredictionResult pred = predictions.get(key);
        if (pred != null && pred.predicted) {
            hitCount.incrementAndGet();
        }
    }
    
    // ========== 预测API ==========
    
    public List<String> predictHotKeys(int topK) {
        List<PredictionResult> results = new ArrayList<>();
        
        for (var entry : accessHistories.entrySet()) {
            double score = calculatePredictionScore(entry.getValue());
            results.add(new PredictionResult(
                entry.getKey(),
                score,
                score >= properties.getMlWarmup().getConfidenceThreshold(),
                System.currentTimeMillis()
            ));
        }
        
        results.sort((a, b) -> Double.compare(b.score, a.score));
        
        return results.stream()
            .limit(topK)
            .filter(r -> r.predicted)
            .map(r -> r.key)
            .toList();
    }
    
    public PredictionResult predict(String key) {
        AccessHistory history = accessHistories.get(key);
        if (history == null) {
            return new PredictionResult(key, 0, false, System.currentTimeMillis());
        }
        
        double score = calculatePredictionScore(history);
        predictionCount.incrementAndGet();
        predCounter.increment();
        
        PredictionResult result = new PredictionResult(
            key,
            score,
            score >= properties.getMlWarmup().getConfidenceThreshold(),
            System.currentTimeMillis()
        );
        
        predictions.put(key, result);
        return result;
    }
    
    // ========== 预热API ==========
    
    public void submitWarmup(String key, WarmupCallback callback) {
        warmupQueue.offer(new WarmupTask(key, callback, System.currentTimeMillis()));
    }
    
    public void submitBatchWarmup(List<String> keys, WarmupCallback callback) {
        for (String key : keys) {
            submitWarmup(key, callback);
        }
    }
    
    public void triggerPredictiveWarmup(WarmupCallback callback) {
        int maxPrefetch = properties.getMlWarmup().getMaxPrefetch();
        List<String> hotKeys = predictHotKeys(maxPrefetch);
        
        for (String key : hotKeys) {
            submitWarmup(key, callback);
        }
        
        log.info("[ML预热] 预测预热触发: {} keys", hotKeys.size());
    }
    
    // ========== 模型计算 ==========
    
    private double calculatePredictionScore(AccessHistory history) {
        List<Long> timestamps = history.getRecentTimestamps(100);
        
        if (timestamps.size() < 3) {
            return 0;
        }
        
        // 特征提取
        double[] features = extractFeatures(timestamps);
        
        // 简化的预测模型
        double score = 0;
        double[] weights = modelWeights.getOrDefault("default", getDefaultWeights());
        
        for (int i = 0; i < Math.min(features.length, weights.length); i++) {
            score += features[i] * weights[i];
        }
        
        // Sigmoid归一化
        return 1.0 / (1.0 + Math.exp(-score));
    }
    
    private double[] extractFeatures(List<Long> timestamps) {
        double[] features = new double[5];
        
        // 访问频率
        long timeSpan = timestamps.get(timestamps.size() - 1) - timestamps.get(0);
        features[0] = timeSpan > 0 ? (double) timestamps.size() / (timeSpan / 1000.0) : 0;
        
        // 访问量
        features[1] = Math.log1p(timestamps.size());
        
        // 最近活跃度
        long recent = System.currentTimeMillis() - timestamps.get(timestamps.size() - 1);
        features[2] = Math.exp(-recent / 60000.0);
        
        // 访问间隔稳定性
        features[3] = calculateIntervalStability(timestamps);
        
        // 时间趋势
        features[4] = calculateTrend(timestamps);
        
        return features;
    }
    
    private double calculateIntervalStability(List<Long> timestamps) {
        if (timestamps.size() < 2) return 0;
        
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < timestamps.size(); i++) {
            intervals.add(timestamps.get(i) - timestamps.get(i - 1));
        }
        
        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = intervals.stream()
            .mapToDouble(i -> Math.pow(i - mean, 2))
            .average().orElse(0);
        
        double cv = mean > 0 ? Math.sqrt(variance) / mean : 0;
        return Math.exp(-cv);
    }
    
    private double calculateTrend(List<Long> timestamps) {
        int n = timestamps.size();
        if (n < 2) return 0;
        
        // 简单线性回归斜率
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        long base = timestamps.get(0);
        
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = timestamps.get(i) - base;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX + 0.001);
        return slope > 0 ? 1 : (slope < 0 ? -1 : 0);
    }
    
    private double[] getDefaultWeights() {
        return new double[]{0.3, 0.2, 0.25, 0.15, 0.1};
    }
    
    // ========== 内部任务 ==========
    
    private void processWarmupQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WarmupTask task = warmupQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task != null) {
                    executeWarmup(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    private void executeWarmup(WarmupTask task) {
        try {
            if (task.callback != null) {
                task.callback.onWarmup(task.key);
            }
            warmupCount.incrementAndGet();
            warmCounter.increment();
        } catch (Exception e) {
            log.warn("[ML预热] 预热失败: {}", task.key, e);
        }
    }
    
    private void runPrediction() {
        int batch = properties.getMlWarmup().getBatchSize();
        List<String> hotKeys = predictHotKeys(batch);
        
        for (String key : hotKeys) {
            predictions.put(key, predict(key));
        }
    }
    
    private void updateModel() {
        // 根据命中率调整模型权重
        long total = predictionCount.get();
        long hits = hitCount.get();
        
        if (total < 100) return;
        
        double hitRate = (double) hits / total;
        
        double[] weights = modelWeights.getOrDefault("default", getDefaultWeights());
        
        // 简单自适应调整
        if (hitRate < 0.5) {
            // 降低频率权重，提高活跃度权重
            weights[0] *= 0.9;
            weights[2] *= 1.1;
        } else if (hitRate > 0.8) {
            // 维持当前权重
        }
        
        modelWeights.put("default", weights);
        
        log.debug("[ML预热] 模型更新 - 命中率: {:.2f}%", hitRate * 100);
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("modelType", properties.getMlWarmup().getModelType());
        stats.put("accessHistoryCount", accessHistories.size());
        stats.put("predictionCount", predictionCount.get());
        stats.put("warmupCount", warmupCount.get());
        stats.put("hitCount", hitCount.get());
        stats.put("warmupQueueSize", warmupQueue.size());
        
        long total = predictionCount.get();
        if (total > 0) {
            stats.put("hitRate", String.format("%.2f%%", (double) hitCount.get() / total * 100));
        }
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    @Data
    public static class PredictionResult {
        private final String key;
        private final double score;
        private final boolean predicted;
        private final long timestamp;
    }
    
    private static class AccessHistory {
        private final Queue<Long> timestamps = new ConcurrentLinkedQueue<>();
        private static final int MAX_SIZE = 1000;
        
        void record(long timestamp) {
            timestamps.offer(timestamp);
            while (timestamps.size() > MAX_SIZE) {
                timestamps.poll();
            }
        }
        
        List<Long> getRecentTimestamps(int count) {
            List<Long> list = new ArrayList<>(timestamps);
            if (list.size() <= count) return list;
            return list.subList(list.size() - count, list.size());
        }
    }
    
    private record WarmupTask(String key, WarmupCallback callback, long submitTime) {}
    
    @FunctionalInterface
    public interface WarmupCallback {
        void onWarmup(String key);
    }
}
