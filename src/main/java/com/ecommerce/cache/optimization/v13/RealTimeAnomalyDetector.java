package com.ecommerce.cache.optimization.v13;

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
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * V13实时异常检测引擎
 * 支持Isolation Forest、Z-Score、MAD、集成检测、自动修复
 */
@Component
public class RealTimeAnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(RealTimeAnomalyDetector.class);

    private final OptimizationV13Properties properties;
    private final MeterRegistry meterRegistry;

    // 检测器
    private final ConcurrentMap<String, AnomalyDetector> detectors = new ConcurrentHashMap<>();
    
    // 数据窗口
    private final ConcurrentMap<String, SlidingWindow> dataWindows = new ConcurrentHashMap<>();
    
    // 异常记录
    private final ConcurrentMap<String, List<AnomalyEvent>> anomalyHistory = new ConcurrentHashMap<>();
    private final BlockingQueue<AnomalyEvent> alertQueue = new LinkedBlockingQueue<>(10000);
    
    // 修复器
    private final ConcurrentMap<String, Consumer<AnomalyEvent>> remediationHandlers = new ConcurrentHashMap<>();
    
    // 告警冷却
    private final ConcurrentMap<String, Long> lastAlertTime = new ConcurrentHashMap<>();
    
    // 执行器
    private ScheduledExecutorService scheduler;
    private ExecutorService detectionExecutor;
    
    // 计数器
    private final AtomicLong totalSamples = new AtomicLong(0);
    private final AtomicLong totalAnomalies = new AtomicLong(0);
    
    // 指标
    private Counter anomaliesDetected;
    private Counter remediationsExecuted;
    private Counter alertsSent;

    public RealTimeAnomalyDetector(OptimizationV13Properties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        if (!properties.getAnomalyDetection().isEnabled()) {
            log.info("V13实时异常检测引擎已禁用");
            return;
        }

        initializeMetrics();
        initializeDetectors();
        initializeExecutors();
        startBackgroundTasks();

        log.info("V13实时异常检测引擎初始化完成 - 算法: {}, 敏感度: {}",
                properties.getAnomalyDetection().getAlgorithm(),
                properties.getAnomalyDetection().getSensitivityThreshold());
    }

    private void initializeMetrics() {
        anomaliesDetected = Counter.builder("v13.anomaly.detected")
                .description("检测到的异常数").register(meterRegistry);
        remediationsExecuted = Counter.builder("v13.anomaly.remediated")
                .description("执行的修复数").register(meterRegistry);
        alertsSent = Counter.builder("v13.anomaly.alerts")
                .description("发送的告警数").register(meterRegistry);
        
        Gauge.builder("v13.anomaly.rate", this, d -> 
                totalSamples.get() > 0 ? (double) totalAnomalies.get() / totalSamples.get() : 0)
                .description("异常率").register(meterRegistry);
    }

    private void initializeDetectors() {
        // 初始化各类检测器
        detectors.put("ISOLATION_FOREST", new IsolationForestDetector());
        detectors.put("Z_SCORE", new ZScoreDetector());
        detectors.put("MAD", new MADDetector());
        detectors.put("EWMA", new EWMADetector());
        detectors.put("PERCENTILE", new PercentileDetector());
    }

    private void initializeExecutors() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "v13-anomaly-scheduler");
            t.setDaemon(true);
            return t;
        });
        detectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private void startBackgroundTasks() {
        // 告警处理
        scheduler.scheduleAtFixedRate(this::processAlerts, 100, 100, TimeUnit.MILLISECONDS);
        // 历史清理
        scheduler.scheduleAtFixedRate(this::cleanupHistory, 60, 60, TimeUnit.SECONDS);
        // 模型更新
        scheduler.scheduleAtFixedRate(this::updateModels, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (detectionExecutor != null) detectionExecutor.shutdown();
    }

    // ==================== 异常检测API ====================

    /**
     * 记录数据点并检测异常
     */
    public AnomalyResult record(String metric, double value) {
        return record(metric, value, System.currentTimeMillis());
    }

    public AnomalyResult record(String metric, double value, long timestamp) {
        totalSamples.incrementAndGet();
        
        // 获取或创建滑动窗口
        SlidingWindow window = dataWindows.computeIfAbsent(metric, 
                k -> new SlidingWindow(properties.getAnomalyDetection().getWindowSize()));
        
        // 添加数据点
        window.add(value, timestamp);
        
        // 检测异常
        AnomalyResult result = detect(metric, value, window);
        
        if (result.isAnomaly()) {
            handleAnomaly(metric, value, timestamp, result);
        }
        
        return result;
    }

    /**
     * 批量记录
     */
    public List<AnomalyResult> recordBatch(String metric, List<Double> values) {
        return values.stream()
                .map(v -> record(metric, v))
                .collect(Collectors.toList());
    }

    /**
     * 检测异常
     */
    public AnomalyResult detect(String metric, double value, SlidingWindow window) {
        if (window.size() < properties.getAnomalyDetection().getMinSamplesForTraining()) {
            return AnomalyResult.normal(value, 0);
        }

        // 使用配置的算法
        String algorithm = properties.getAnomalyDetection().getAlgorithm();
        
        if (properties.getAnomalyDetection().isEnsembleDetectionEnabled()) {
            return ensembleDetect(metric, value, window);
        }
        
        AnomalyDetector detector = detectors.get(algorithm);
        if (detector == null) {
            detector = detectors.get("Z_SCORE");
        }
        
        return detector.detect(value, window, properties.getAnomalyDetection().getOutlierScoreThreshold());
    }

    /**
     * 集成检测
     */
    private AnomalyResult ensembleDetect(String metric, double value, SlidingWindow window) {
        double threshold = properties.getAnomalyDetection().getOutlierScoreThreshold();
        
        List<AnomalyResult> results = new ArrayList<>();
        for (AnomalyDetector detector : detectors.values()) {
            results.add(detector.detect(value, window, threshold));
        }
        
        // 投票机制
        long anomalyVotes = results.stream().filter(AnomalyResult::isAnomaly).count();
        double avgScore = results.stream().mapToDouble(AnomalyResult::getScore).average().orElse(0);
        
        boolean isAnomaly = anomalyVotes > detectors.size() / 2.0;
        
        return new AnomalyResult(value, avgScore, isAnomaly, 
                isAnomaly ? determineAnomalyType(value, window) : AnomalyType.NONE);
    }

    private AnomalyType determineAnomalyType(double value, SlidingWindow window) {
        double mean = window.getMean();
        double std = window.getStdDev();
        
        if (value > mean + 3 * std) {
            return AnomalyType.SPIKE;
        } else if (value < mean - 3 * std) {
            return AnomalyType.DIP;
        } else if (detectTrend(window) != 0) {
            return AnomalyType.TREND_CHANGE;
        } else if (detectSeasonality(window)) {
            return AnomalyType.SEASONAL_DEVIATION;
        }
        
        return AnomalyType.POINT_ANOMALY;
    }

    private int detectTrend(SlidingWindow window) {
        double[] values = window.getValues();
        if (values.length < 10) return 0;
        
        // 简单线性趋势检测
        int n = values.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += values[i];
            sumXY += i * values[i];
            sumX2 += i * i;
        }
        
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double std = window.getStdDev();
        
        if (slope > std * 0.1) return 1;  // 上升趋势
        if (slope < -std * 0.1) return -1; // 下降趋势
        return 0;
    }

    private boolean detectSeasonality(SlidingWindow window) {
        // 简化的季节性检测
        double[] values = window.getValues();
        if (values.length < 24) return false;
        
        // 自相关检测
        double mean = window.getMean();
        double var = 0;
        for (double v : values) {
            var += (v - mean) * (v - mean);
        }
        var /= values.length;
        
        // 检测周期12的自相关
        int lag = 12;
        double autoCorr = 0;
        for (int i = lag; i < values.length; i++) {
            autoCorr += (values[i] - mean) * (values[i - lag] - mean);
        }
        autoCorr /= (values.length - lag);
        autoCorr /= var;
        
        return Math.abs(autoCorr) > 0.5;
    }

    // ==================== 异常处理 ====================

    private void handleAnomaly(String metric, double value, long timestamp, AnomalyResult result) {
        totalAnomalies.incrementAndGet();
        anomaliesDetected.increment();
        
        AnomalyEvent event = new AnomalyEvent(metric, value, timestamp, result);
        
        // 记录历史
        anomalyHistory.computeIfAbsent(metric, k -> new CopyOnWriteArrayList<>()).add(event);
        
        // 检查告警冷却
        if (shouldAlert(metric)) {
            alertQueue.offer(event);
            lastAlertTime.put(metric, System.currentTimeMillis());
        }
        
        // 自动修复
        if (properties.getAnomalyDetection().isAutoRemediationEnabled()) {
            executeRemediation(event);
        }
        
        log.warn("检测到异常: metric={}, value={}, score={}, type={}",
                metric, value, result.getScore(), result.getType());
    }

    private boolean shouldAlert(String metric) {
        long cooldown = properties.getAnomalyDetection().getAlertCooldownMs();
        Long lastAlert = lastAlertTime.get(metric);
        return lastAlert == null || System.currentTimeMillis() - lastAlert > cooldown;
    }

    private void processAlerts() {
        List<AnomalyEvent> batch = new ArrayList<>();
        alertQueue.drainTo(batch, 100);
        
        for (AnomalyEvent event : batch) {
            sendAlert(event);
            alertsSent.increment();
        }
    }

    private void sendAlert(AnomalyEvent event) {
        // 实际实现需要接入告警系统
        log.info("发送告警: metric={}, value={}, type={}", 
                event.getMetric(), event.getValue(), event.getResult().getType());
    }

    // ==================== 自动修复 ====================

    /**
     * 注册修复处理器
     */
    public void registerRemediationHandler(String metric, Consumer<AnomalyEvent> handler) {
        remediationHandlers.put(metric, handler);
        log.info("注册修复处理器: metric={}", metric);
    }

    private void executeRemediation(AnomalyEvent event) {
        Consumer<AnomalyEvent> handler = remediationHandlers.get(event.getMetric());
        if (handler == null) {
            handler = remediationHandlers.get("*"); // 默认处理器
        }
        
        if (handler != null) {
            detectionExecutor.submit(() -> {
                try {
                    handler.accept(event);
                    remediationsExecuted.increment();
                    log.info("执行修复: metric={}", event.getMetric());
                } catch (Exception e) {
                    log.error("修复执行失败: metric={}", event.getMetric(), e);
                }
            });
        }
    }

    /**
     * 默认修复策略
     */
    public void registerDefaultRemediation() {
        registerRemediationHandler("*", event -> {
            AnomalyType type = event.getResult().getType();
            switch (type) {
                case SPIKE:
                    // 流量激增 - 触发限流
                    log.info("触发限流策略: metric={}", event.getMetric());
                    break;
                case DIP:
                    // 指标骤降 - 触发告警升级
                    log.info("告警升级: metric={}", event.getMetric());
                    break;
                case TREND_CHANGE:
                    // 趋势变化 - 触发容量规划
                    log.info("触发容量评估: metric={}", event.getMetric());
                    break;
                default:
                    log.info("通用修复: metric={}, type={}", event.getMetric(), type);
            }
        });
    }

    // ==================== 模型维护 ====================

    private void updateModels() {
        for (Map.Entry<String, SlidingWindow> entry : dataWindows.entrySet()) {
            String metric = entry.getKey();
            SlidingWindow window = entry.getValue();
            
            // 更新检测器阈值
            if (window.size() >= properties.getAnomalyDetection().getMinSamplesForTraining()) {
                for (AnomalyDetector detector : detectors.values()) {
                    detector.updateModel(window);
                }
            }
        }
    }

    private void cleanupHistory() {
        long threshold = System.currentTimeMillis() - 3600000; // 1小时
        for (List<AnomalyEvent> events : anomalyHistory.values()) {
            events.removeIf(e -> e.getTimestamp() < threshold);
        }
    }

    // ==================== 查询API ====================

    /**
     * 获取异常历史
     */
    public List<AnomalyEvent> getAnomalyHistory(String metric) {
        return anomalyHistory.getOrDefault(metric, Collections.emptyList());
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSamples", totalSamples.get());
        stats.put("totalAnomalies", totalAnomalies.get());
        stats.put("anomalyRate", totalSamples.get() > 0 
                ? String.format("%.4f%%", (double) totalAnomalies.get() / totalSamples.get() * 100) 
                : "N/A");
        stats.put("monitoredMetrics", dataWindows.size());
        stats.put("pendingAlerts", alertQueue.size());
        
        Map<String, Object> metricStats = new LinkedHashMap<>();
        for (Map.Entry<String, SlidingWindow> e : dataWindows.entrySet()) {
            SlidingWindow w = e.getValue();
            metricStats.put(e.getKey(), Map.of(
                    "count", w.size(),
                    "mean", String.format("%.2f", w.getMean()),
                    "stdDev", String.format("%.2f", w.getStdDev()),
                    "anomalies", anomalyHistory.getOrDefault(e.getKey(), Collections.emptyList()).size()
            ));
        }
        stats.put("metrics", metricStats);
        
        return stats;
    }

    // ==================== 检测器实现 ====================

    private interface AnomalyDetector {
        AnomalyResult detect(double value, SlidingWindow window, double threshold);
        void updateModel(SlidingWindow window);
    }

    /**
     * Isolation Forest检测器
     */
    private class IsolationForestDetector implements AnomalyDetector {
        private final List<IsolationTree> forest = new ArrayList<>();
        private final Random random = new Random();

        @Override
        public AnomalyResult detect(double value, SlidingWindow window, double threshold) {
            if (forest.isEmpty()) {
                updateModel(window);
            }
            
            double score = computeAnomalyScore(value);
            boolean isAnomaly = score > threshold;
            
            return new AnomalyResult(value, score, isAnomaly, 
                    isAnomaly ? AnomalyType.POINT_ANOMALY : AnomalyType.NONE);
        }

        private double computeAnomalyScore(double value) {
            if (forest.isEmpty()) return 0;
            
            double avgPathLength = forest.stream()
                    .mapToDouble(tree -> tree.pathLength(value, 0))
                    .average()
                    .orElse(0);
            
            int n = properties.getAnomalyDetection().getWindowSize();
            double c = 2 * (Math.log(n - 1) + 0.5772156649) - 2 * (n - 1) / n;
            
            return Math.pow(2, -avgPathLength / c);
        }

        @Override
        public void updateModel(SlidingWindow window) {
            forest.clear();
            double[] values = window.getValues();
            int treeCount = properties.getAnomalyDetection().getTreeCount();
            int sampleSize = Math.min(256, values.length);
            
            for (int i = 0; i < treeCount; i++) {
                double[] sample = randomSample(values, sampleSize);
                forest.add(new IsolationTree(sample, 0, (int) Math.ceil(Math.log(sampleSize) / Math.log(2))));
            }
        }

        private double[] randomSample(double[] data, int size) {
            double[] sample = new double[size];
            for (int i = 0; i < size; i++) {
                sample[i] = data[random.nextInt(data.length)];
            }
            return sample;
        }
    }

    private static class IsolationTree {
        private final Double splitValue;
        private final IsolationTree left;
        private final IsolationTree right;
        private final int size;

        IsolationTree(double[] data, int depth, int maxDepth) {
            this.size = data.length;
            
            if (depth >= maxDepth || data.length <= 1) {
                this.splitValue = null;
                this.left = null;
                this.right = null;
                return;
            }
            
            double min = Arrays.stream(data).min().orElse(0);
            double max = Arrays.stream(data).max().orElse(0);
            
            if (min == max) {
                this.splitValue = null;
                this.left = null;
                this.right = null;
                return;
            }
            
            this.splitValue = min + Math.random() * (max - min);
            
            double[] leftData = Arrays.stream(data).filter(v -> v < splitValue).toArray();
            double[] rightData = Arrays.stream(data).filter(v -> v >= splitValue).toArray();
            
            this.left = leftData.length > 0 ? new IsolationTree(leftData, depth + 1, maxDepth) : null;
            this.right = rightData.length > 0 ? new IsolationTree(rightData, depth + 1, maxDepth) : null;
        }

        double pathLength(double value, int depth) {
            if (splitValue == null) {
                return depth + averagePathLength(size);
            }
            
            if (value < splitValue) {
                return left != null ? left.pathLength(value, depth + 1) : depth + 1;
            } else {
                return right != null ? right.pathLength(value, depth + 1) : depth + 1;
            }
        }

        private double averagePathLength(int n) {
            if (n <= 1) return 0;
            return 2 * (Math.log(n - 1) + 0.5772156649) - 2 * (n - 1) / n;
        }
    }

    /**
     * Z-Score检测器
     */
    private static class ZScoreDetector implements AnomalyDetector {
        @Override
        public AnomalyResult detect(double value, SlidingWindow window, double threshold) {
            double mean = window.getMean();
            double std = window.getStdDev();
            
            if (std == 0) return AnomalyResult.normal(value, 0);
            
            double zScore = Math.abs(value - mean) / std;
            double score = 1 - 1 / (1 + Math.exp(-(zScore - 3))); // Sigmoid映射
            boolean isAnomaly = score > threshold;
            
            return new AnomalyResult(value, score, isAnomaly,
                    isAnomaly ? AnomalyType.POINT_ANOMALY : AnomalyType.NONE);
        }

        @Override
        public void updateModel(SlidingWindow window) {
            // Z-Score不需要训练
        }
    }

    /**
     * MAD (Median Absolute Deviation) 检测器
     */
    private static class MADDetector implements AnomalyDetector {
        @Override
        public AnomalyResult detect(double value, SlidingWindow window, double threshold) {
            double[] values = window.getValues();
            Arrays.sort(values);
            
            double median = values[values.length / 2];
            
            double[] deviations = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                deviations[i] = Math.abs(values[i] - median);
            }
            Arrays.sort(deviations);
            
            double mad = deviations[deviations.length / 2];
            if (mad == 0) return AnomalyResult.normal(value, 0);
            
            double modifiedZScore = 0.6745 * (value - median) / mad;
            double score = Math.abs(modifiedZScore) / 5.0; // 归一化
            score = Math.min(1, score);
            boolean isAnomaly = score > threshold;
            
            return new AnomalyResult(value, score, isAnomaly,
                    isAnomaly ? AnomalyType.POINT_ANOMALY : AnomalyType.NONE);
        }

        @Override
        public void updateModel(SlidingWindow window) {
            // MAD不需要训练
        }
    }

    /**
     * EWMA (指数加权移动平均) 检测器
     */
    private static class EWMADetector implements AnomalyDetector {
        private double ewma = 0;
        private double ewmVar = 0;
        private final double alpha = 0.3;
        private boolean initialized = false;

        @Override
        public AnomalyResult detect(double value, SlidingWindow window, double threshold) {
            if (!initialized) {
                ewma = window.getMean();
                ewmVar = Math.pow(window.getStdDev(), 2);
                initialized = true;
            }
            
            double prediction = ewma;
            double error = value - prediction;
            
            ewma = alpha * value + (1 - alpha) * ewma;
            ewmVar = alpha * error * error + (1 - alpha) * ewmVar;
            
            double ewmStd = Math.sqrt(ewmVar);
            if (ewmStd == 0) return AnomalyResult.normal(value, 0);
            
            double score = Math.abs(error) / (3 * ewmStd);
            score = Math.min(1, score);
            boolean isAnomaly = score > threshold;
            
            return new AnomalyResult(value, score, isAnomaly,
                    isAnomaly ? AnomalyType.POINT_ANOMALY : AnomalyType.NONE);
        }

        @Override
        public void updateModel(SlidingWindow window) {
            ewma = window.getMean();
            ewmVar = Math.pow(window.getStdDev(), 2);
            initialized = true;
        }
    }

    /**
     * 百分位检测器
     */
    private static class PercentileDetector implements AnomalyDetector {
        @Override
        public AnomalyResult detect(double value, SlidingWindow window, double threshold) {
            double[] values = window.getValues().clone();
            Arrays.sort(values);
            
            double p1 = values[(int) (values.length * 0.01)];
            double p99 = values[(int) (values.length * 0.99)];
            
            double score = 0;
            if (value < p1) {
                score = Math.min(1, (p1 - value) / (window.getStdDev() + 0.001));
            } else if (value > p99) {
                score = Math.min(1, (value - p99) / (window.getStdDev() + 0.001));
            }
            
            boolean isAnomaly = score > threshold;
            
            return new AnomalyResult(value, score, isAnomaly,
                    isAnomaly ? AnomalyType.POINT_ANOMALY : AnomalyType.NONE);
        }

        @Override
        public void updateModel(SlidingWindow window) {
            // 百分位检测不需要训练
        }
    }

    // ==================== 数据结构 ====================

    public static class SlidingWindow {
        private final double[] buffer;
        private final long[] timestamps;
        private int head = 0;
        private int count = 0;
        private double sum = 0;
        private double sumSquares = 0;

        public SlidingWindow(int size) {
            this.buffer = new double[size];
            this.timestamps = new long[size];
        }

        public synchronized void add(double value, long timestamp) {
            if (count == buffer.length) {
                sum -= buffer[head];
                sumSquares -= buffer[head] * buffer[head];
            } else {
                count++;
            }
            
            buffer[head] = value;
            timestamps[head] = timestamp;
            sum += value;
            sumSquares += value * value;
            head = (head + 1) % buffer.length;
        }

        public synchronized double getMean() {
            return count > 0 ? sum / count : 0;
        }

        public synchronized double getStdDev() {
            if (count < 2) return 0;
            double mean = getMean();
            double variance = sumSquares / count - mean * mean;
            return Math.sqrt(Math.max(0, variance));
        }

        public synchronized double[] getValues() {
            double[] result = new double[count];
            for (int i = 0; i < count; i++) {
                result[i] = buffer[(head - count + i + buffer.length) % buffer.length];
            }
            return result;
        }

        public synchronized int size() {
            return count;
        }
    }

    public enum AnomalyType {
        NONE, POINT_ANOMALY, SPIKE, DIP, TREND_CHANGE, SEASONAL_DEVIATION
    }

    public static class AnomalyResult {
        private final double value;
        private final double score;
        private final boolean anomaly;
        private final AnomalyType type;

        public AnomalyResult(double value, double score, boolean anomaly, AnomalyType type) {
            this.value = value;
            this.score = score;
            this.anomaly = anomaly;
            this.type = type;
        }

        public static AnomalyResult normal(double value, double score) {
            return new AnomalyResult(value, score, false, AnomalyType.NONE);
        }

        public double getValue() { return value; }
        public double getScore() { return score; }
        public boolean isAnomaly() { return anomaly; }
        public AnomalyType getType() { return type; }
    }

    public static class AnomalyEvent {
        private final String metric;
        private final double value;
        private final long timestamp;
        private final AnomalyResult result;

        public AnomalyEvent(String metric, double value, long timestamp, AnomalyResult result) {
            this.metric = metric;
            this.value = value;
            this.timestamp = timestamp;
            this.result = result;
        }

        public String getMetric() { return metric; }
        public double getValue() { return value; }
        public long getTimestamp() { return timestamp; }
        public AnomalyResult getResult() { return result; }
    }
}
