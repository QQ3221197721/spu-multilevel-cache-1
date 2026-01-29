package com.ecommerce.cache.optimization.v14;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * V14自适应负载预测器
 * 基于LSTM、季节性检测、趋势分析、异常感知的智能负载预测
 */
@Component
public class AdaptiveLoadPredictor {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveLoadPredictor.class);

    private final OptimizationV14Properties properties;
    private final MeterRegistry meterRegistry;

    // 时间序列数据
    private final ConcurrentMap<String, TimeSeriesBuffer> timeSeriesBuffers = new ConcurrentHashMap<>();
    
    // 预测模型
    private final ConcurrentMap<String, LoadPredictionModel> models = new ConcurrentHashMap<>();
    
    // 季节性模式
    private final ConcurrentMap<String, SeasonalPattern> seasonalPatterns = new ConcurrentHashMap<>();
    
    // 趋势分析
    private final ConcurrentMap<String, TrendAnalyzer> trendAnalyzers = new ConcurrentHashMap<>();
    
    // 异常检测器
    private final ConcurrentMap<String, AnomalyDetector> anomalyDetectors = new ConcurrentHashMap<>();
    
    // 预测结果缓存
    private final ConcurrentMap<String, PredictionResult> predictionCache = new ConcurrentHashMap<>();
    
    // 统计信息
    private final AtomicLong totalPredictions = new AtomicLong(0);
    private final AtomicLong totalAnomalies = new AtomicLong(0);
    
    // 执行器
    private ScheduledExecutorService scheduler;
    private ExecutorService predictionExecutor;
    
    // 指标
    private Counter predictionsMade;
    private Counter anomaliesDetected;

    public AdaptiveLoadPredictor(OptimizationV14Properties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        if (!properties.getAdaptiveLoadPredictor().isEnabled()) {
            log.info("V14自适应负载预测器已禁用");
            return;
        }

        initializeMetrics();
        initializeExecutors();
        startBackgroundTasks();

        log.info("V14自适应负载预测器初始化完成 - 算法: {}, 预测窗口: {}分钟",
                properties.getAdaptiveLoadPredictor().getAlgorithm(),
                properties.getAdaptiveLoadPredictor().getPredictionHorizonMinutes());
    }

    private void initializeMetrics() {
        predictionsMade = Counter.builder("v14.load.predictions")
                .description("负载预测次数").register(meterRegistry);
        anomaliesDetected = Counter.builder("v14.load.anomalies")
                .description("检测到的异常数").register(meterRegistry);
        
        Gauge.builder("v14.load.anomaly.rate", this, p -> 
                totalPredictions.get() > 0 ? (double) totalAnomalies.get() / totalPredictions.get() : 0)
                .description("异常率").register(meterRegistry);
    }

    private void initializeExecutors() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "v14-load-predictor-scheduler");
            t.setDaemon(true);
            return t;
        });
        predictionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private void startBackgroundTasks() {
        // 模型更新
        scheduler.scheduleAtFixedRate(this::updateModels, 
                properties.getAdaptiveLoadPredictor().getModelUpdateIntervalMinutes(),
                properties.getAdaptiveLoadPredictor().getModelUpdateIntervalMinutes(),
                TimeUnit.MINUTES);
        
        // 季节性检测
        if (properties.getAdaptiveLoadPredictor().isSeasonalityDetectionEnabled()) {
            scheduler.scheduleAtFixedRate(this::detectSeasonality, 60, 60, TimeUnit.MINUTES);
        }
        
        // 趋势分析
        scheduler.scheduleAtFixedRate(this::analyzeTrends, 
                properties.getAdaptiveLoadPredictor().getTrendAnalysisWindow(),
                properties.getAdaptiveLoadPredictor().getTrendAnalysisWindow(),
                TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (predictionExecutor != null) predictionExecutor.shutdown();
    }

    // ==================== 核心API ====================

    /**
     * 记录负载数据点
     */
    public void recordLoad(String metric, double load, long timestamp) {
        TimeSeriesBuffer buffer = timeSeriesBuffers.computeIfAbsent(metric, 
                k -> new TimeSeriesBuffer(properties.getAdaptiveLoadPredictor().getHistoryWindowHours() * 60));
        
        buffer.add(load, timestamp);
        
        // 异常检测
        if (properties.getAdaptiveLoadPredictor().isAnomalyAwareEnabled()) {
            AnomalyDetector detector = anomalyDetectors.computeIfAbsent(metric, 
                    k -> new AnomalyDetector());
            if (detector.isAnomaly(load)) {
                totalAnomalies.incrementAndGet();
                anomaliesDetected.increment();
            }
        }
    }

    /**
     * 记录当前负载
     */
    public void recordCurrentLoad(String metric, double load) {
        recordLoad(metric, load, System.currentTimeMillis());
    }

    /**
     * 预测未来负载
     */
    public PredictionResult predict(String metric) {
        return predict(metric, properties.getAdaptiveLoadPredictor().getPredictionHorizonMinutes());
    }

    public PredictionResult predict(String metric, int minutesAhead) {
        return predictionsMade.record(() -> {
            totalPredictions.incrementAndGet();
            
            TimeSeriesBuffer buffer = timeSeriesBuffers.get(metric);
            if (buffer == null || buffer.size() < 10) {
                return PredictionResult.defaultPrediction(minutesAhead);
            }
            
            // 检查预测缓存
            String cacheKey = metric + "_" + minutesAhead;
            PredictionResult cached = predictionCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                return cached;
            }
            
            // 获取或创建模型
            LoadPredictionModel model = models.computeIfAbsent(metric, 
                    k -> new LoadPredictionModel(properties.getAdaptiveLoadPredictor().getAlgorithm()));
            
            // 获取时间序列数据
            double[] historicalData = buffer.getRecentValues(100); // 最近100个点
            
            // 季节性模式
            SeasonalPattern seasonalPattern = seasonalPatterns.get(metric);
            
            // 趋势信息
            TrendAnalyzer trendAnalyzer = trendAnalyzers.get(metric);
            
            // 执行预测
            double[] predictions = model.predict(historicalData, minutesAhead);
            
            // 结合季节性和趋势
            if (seasonalPattern != null) {
                predictions = applySeasonalAdjustment(predictions, seasonalPattern);
            }
            if (trendAnalyzer != null) {
                predictions = applyTrendAdjustment(predictions, trendAnalyzer);
            }
            
            // 创建预测结果
            PredictionResult result = new PredictionResult(
                    metric, 
                    predictions, 
                    System.currentTimeMillis(),
                    properties.getAdaptiveLoadPredictor().getConfidenceThreshold()
            );
            
            // 缓存结果（5分钟过期）
            predictionCache.put(cacheKey, result);
            
            return result;
        });
    }

    /**
     * 批量预测
     */
    public Map<String, PredictionResult> predictBatch(List<String> metrics) {
        return metrics.stream()
                .collect(Collectors.toMap(
                        metric -> metric,
                        metric -> predict(metric)
                ));
    }

    /**
     * 获取负载统计
     */
    public LoadStatistics getStatistics(String metric) {
        TimeSeriesBuffer buffer = timeSeriesBuffers.get(metric);
        if (buffer == null || buffer.size() == 0) {
            return LoadStatistics.empty();
        }
        
        double[] values = buffer.getRecentValues(buffer.size());
        return new LoadStatistics(
                Arrays.stream(values).average().orElse(0),
                Arrays.stream(values).max().orElse(0),
                Arrays.stream(values).min().orElse(0),
                Arrays.stream(values).mapToObj(x -> x).collect(Collectors.summarizingDouble(Double::doubleValue)).getStandardDeviation()
        );
    }

    // ==================== 模型管理 ====================

    private void updateModels() {
        for (String metric : timeSeriesBuffers.keySet()) {
            TimeSeriesBuffer buffer = timeSeriesBuffers.get(metric);
            if (buffer.size() < 20) continue; // 数据不足
            
            LoadPredictionModel model = models.computeIfAbsent(metric, 
                    k -> new LoadPredictionModel(properties.getAdaptiveLoadPredictor().getAlgorithm()));
            
            double[] recentData = buffer.getRecentValues(200);
            model.train(recentData);
        }
    }

    private void detectSeasonality() {
        for (String metric : timeSeriesBuffers.keySet()) {
            TimeSeriesBuffer buffer = timeSeriesBuffers.get(metric);
            if (buffer.size() < 100) continue;
            
            double[] data = buffer.getRecentValues(buffer.size());
            SeasonalPattern pattern = analyzeSeasonalPattern(data);
            seasonalPatterns.put(metric, pattern);
        }
    }

    private void analyzeTrends() {
        for (String metric : timeSeriesBuffers.keySet()) {
            TimeSeriesBuffer buffer = timeSeriesBuffers.get(metric);
            if (buffer.size() < 50) continue;
            
            double[] data = buffer.getRecentValues(buffer.size());
            TrendAnalyzer analyzer = new TrendAnalyzer(data);
            trendAnalyzers.put(metric, analyzer);
        }
    }

    private SeasonalPattern analyzeSeasonalPattern(double[] data) {
        // 简化的季节性检测：寻找周期性模式
        int minPeriod = 24; // 最小周期（小时）
        int maxPeriod = 168; // 最大周期（周）
        
        double bestCorrelation = 0;
        int bestPeriod = 24;
        
        for (int period = minPeriod; period <= maxPeriod && period < data.length / 2; period++) {
            double correlation = calculateAutocorrelation(data, period);
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestPeriod = period;
            }
        }
        
        return new SeasonalPattern(bestPeriod, bestCorrelation);
    }

    private double calculateAutocorrelation(double[] data, int lag) {
        if (lag >= data.length) return 0;
        
        double mean = Arrays.stream(data).average().orElse(0);
        double numerator = 0, denominator = 0;
        
        for (int i = lag; i < data.length; i++) {
            numerator += (data[i] - mean) * (data[i - lag] - mean);
        }
        
        for (double value : data) {
            denominator += (value - mean) * (value - mean);
        }
        
        return denominator > 0 ? numerator / denominator : 0;
    }

    private double[] applySeasonalAdjustment(double[] predictions, SeasonalPattern pattern) {
        double[] adjusted = new double[predictions.length];
        for (int i = 0; i < predictions.length; i++) {
            // 应用季节性调整
            double seasonalFactor = 1.0 + (Math.sin(2 * Math.PI * i / pattern.getPeriod()) * 0.1);
            adjusted[i] = predictions[i] * seasonalFactor;
        }
        return adjusted;
    }

    private double[] applyTrendAdjustment(double[] predictions, TrendAnalyzer trend) {
        double[] adjusted = new double[predictions.length];
        for (int i = 0; i < predictions.length; i++) {
            // 应用趋势调整
            double trendFactor = 1.0 + (trend.getSlope() * i * 0.01);
            adjusted[i] = predictions[i] * trendFactor;
        }
        return adjusted;
    }

    // ==================== 状态查询 ====================

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPredictions", totalPredictions.get());
        stats.put("totalAnomalies", totalAnomalies.get());
        stats.put("anomalyRate", totalPredictions.get() > 0 
                ? String.format("%.4f%%", (double) totalAnomalies.get() / totalPredictions.get() * 100) 
                : "N/A");
        stats.put("trackedMetrics", timeSeriesBuffers.size());
        stats.put("trainedModels", models.size());
        stats.put("seasonalPatterns", seasonalPatterns.size());
        
        Map<String, Object> metricStats = new LinkedHashMap<>();
        for (String metric : timeSeriesBuffers.keySet()) {
            TimeSeriesBuffer buffer = timeSeriesBuffers.get(metric);
            LoadStatistics loadStats = getStatistics(metric);
            
            metricStats.put(metric, Map.of(
                    "dataPoints", buffer.size(),
                    "avgLoad", String.format("%.2f", loadStats.getAverage()),
                    "maxLoad", String.format("%.2f", loadStats.getMax()),
                    "minLoad", String.format("%.2f", loadStats.getMin()),
                    "stdDev", String.format("%.2f", loadStats.getStdDev())
            ));
        }
        stats.put("metrics", metricStats);
        
        return stats;
    }

    public List<String> getTrackedMetrics() {
        return new ArrayList<>(timeSeriesBuffers.keySet());
    }

    // ==================== 内部类 ====================

    /**
     * LSTM风格的简单预测模型
     */
    private static class LoadPredictionModel {
        private final String algorithm;
        private double[] weights;
        private int sequenceLength;
        private boolean trained;

        LoadPredictionModel(String algorithm) {
            this.algorithm = algorithm;
            this.sequenceLength = 10; // 使用前10个时间点预测下一个
            this.weights = new double[sequenceLength];
            Arrays.fill(weights, 0.1); // 初始化权重
            this.trained = false;
        }

        void train(double[] historicalData) {
            if (historicalData.length < sequenceLength + 1) {
                return;
            }
            
            // 简化的训练：最小二乘法
            double[][] X = new double[historicalData.length - sequenceLength][sequenceLength];
            double[] y = new double[historicalData.length - sequenceLength];
            
            for (int i = 0; i < historicalData.length - sequenceLength; i++) {
                for (int j = 0; j < sequenceLength; j++) {
                    X[i][j] = historicalData[i + j];
                }
                y[i] = historicalData[i + sequenceLength];
            }
            
            // 简化的权重更新（实际应使用梯度下降）
            for (int i = 0; i < sequenceLength; i++) {
                double sum = 0;
                for (int j = 0; j < X.length; j++) {
                    sum += X[j][i] * y[j];
                }
                weights[i] = sum / X.length;
            }
            
            trained = true;
        }

        double[] predict(double[] historicalData, int steps) {
            double[] result = new double[steps];
            double[] current = Arrays.copyOf(historicalData, historicalData.length);
            
            for (int step = 0; step < steps; step++) {
                double prediction = 0;
                int startIndex = Math.max(0, current.length - sequenceLength);
                
                for (int i = 0; i < sequenceLength && startIndex + i < current.length; i++) {
                    prediction += current[startIndex + i] * weights[i];
                }
                
                result[step] = Math.max(0, prediction); // 负载不能为负
                
                // 将预测值加入序列，用于下一步预测
                if (current.length < historicalData.length + steps) {
                    current = Arrays.copyOf(current, current.length + 1);
                    current[current.length - 1] = result[step];
                }
            }
            
            return result;
        }
    }

    /**
     * 时间序列缓冲区
     */
    private static class TimeSeriesBuffer {
        private final double[] buffer;
        private final long[] timestamps;
        private int head = 0;
        private int count = 0;
        private final int capacity;

        TimeSeriesBuffer(int hours) {
            this.capacity = hours * 60; // 按分钟存储
            this.buffer = new double[capacity];
            this.timestamps = new long[capacity];
        }

        void add(double value, long timestamp) {
            if (count == capacity) {
                // 覆盖最旧的数据
                buffer[head] = value;
                timestamps[head] = timestamp;
            } else {
                buffer[count] = value;
                timestamps[count] = timestamp;
                count++;
            }
            head = (head + 1) % capacity;
        }

        double[] getRecentValues(int limit) {
            int actualLimit = Math.min(limit, count);
            double[] result = new double[actualLimit];
            
            for (int i = 0; i < actualLimit; i++) {
                int index = (head - actualLimit + i + capacity) % capacity;
                result[i] = buffer[index];
            }
            
            return result;
        }

        int size() {
            return count;
        }
    }

    /**
     * 季节性模式
     */
    private static class SeasonalPattern {
        private final int period;
        private final double strength;

        SeasonalPattern(int period, double strength) {
            this.period = period;
            this.strength = strength;
        }

        int getPeriod() { return period; }
        double getStrength() { return strength; }
    }

    /**
     * 趋势分析器
     */
    private static class TrendAnalyzer {
        private final double slope;
        private final double intercept;

        TrendAnalyzer(double[] data) {
            // 简单线性回归
            int n = data.length;
            double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
            
            for (int i = 0; i < n; i++) {
                double x = i;
                double y = data[i];
                sumX += x;
                sumY += y;
                sumXY += x * y;
                sumXX += x * x;
            }
            
            double denominator = n * sumXX - sumX * sumX;
            if (denominator != 0) {
                this.slope = (n * sumXY - sumX * sumY) / denominator;
                this.intercept = (sumY - this.slope * sumX) / n;
            } else {
                this.slope = 0;
                this.intercept = 0;
            }
        }

        double getSlope() { return slope; }
        double getIntercept() { return intercept; }
    }

    /**
     * 异常检测器
     */
    private static class AnomalyDetector {
        private final double[] recentValues;
        private int head = 0;
        private int count = 0;
        private final int windowSize = 50;

        AnomalyDetector() {
            this.recentValues = new double[windowSize];
        }

        boolean isAnomaly(double value) {
            if (count < 10) {
                addValue(value);
                return false;
            }
            
            double mean = getMean();
            double stdDev = getStdDev();
            
            boolean isAnomaly = Math.abs(value - mean) > 3 * stdDev; // 3σ原则
            
            addValue(value);
            
            return isAnomaly;
        }

        private void addValue(double value) {
            recentValues[head] = value;
            head = (head + 1) % windowSize;
            if (count < windowSize) count++;
        }

        private double getMean() {
            double sum = 0;
            for (int i = 0; i < count; i++) {
                sum += recentValues[i];
            }
            return sum / count;
        }

        private double getStdDev() {
            double mean = getMean();
            double sumSquaredDiff = 0;
            for (int i = 0; i < count; i++) {
                double diff = recentValues[i] - mean;
                sumSquaredDiff += diff * diff;
            }
            return Math.sqrt(sumSquaredDiff / count);
        }
    }

    /**
     * 预测结果
     */
    public static class PredictionResult {
        private final String metric;
        private final double[] predictions;
        private final long timestamp;
        private final double confidenceThreshold;
        private final long expirationTime;

        PredictionResult(String metric, double[] predictions, long timestamp, double confidenceThreshold) {
            this.metric = metric;
            this.predictions = predictions;
            this.timestamp = timestamp;
            this.confidenceThreshold = confidenceThreshold;
            this.expirationTime = timestamp + 5 * 60 * 1000; // 5分钟过期
        }

        public static PredictionResult defaultPrediction(int steps) {
            double[] defaults = new double[steps];
            Arrays.fill(defaults, 100); // 默认负载值
            return new PredictionResult("default", defaults, System.currentTimeMillis(), 0.8);
        }

        String getMetric() { return metric; }
        double[] getPredictions() { return predictions; }
        long getTimestamp() { return timestamp; }
        double getConfidenceThreshold() { return confidenceThreshold; }
        boolean isExpired() { return System.currentTimeMillis() > expirationTime; }
        
        public double getMaxPrediction() {
            return Arrays.stream(predictions).max().orElse(0);
        }
        
        public double getAvgPrediction() {
            return Arrays.stream(predictions).average().orElse(0);
        }
        
        public double getMinPrediction() {
            return Arrays.stream(predictions).min().orElse(0);
        }
    }

    /**
     * 负载统计
     */
    public static class LoadStatistics {
        private final double average;
        private final double max;
        private final double min;
        private final double stdDev;

        LoadStatistics(double average, double max, double min, double stdDev) {
            this.average = average;
            this.max = max;
            this.min = min;
            this.stdDev = stdDev;
        }

        public static LoadStatistics empty() {
            return new LoadStatistics(0, 0, 0, 0);
        }

        double getAverage() { return average; }
        double getMax() { return max; }
        double getMin() { return min; }
        double getStdDev() { return stdDev; }
    }

    public void enableAutoScaling(String metric) {
        if (properties.getAdaptiveLoadPredictor().isAutoScalingEnabled()) {
            log.info("启用{}的自动伸缩预测", metric);
        }
    }
}
