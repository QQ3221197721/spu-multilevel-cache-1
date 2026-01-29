package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.lang.management.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * 实时性能分析器 - 全方位监控与异常检测
 * 
 * 核心特性：
 * 1. 实时指标采集 - 毫秒级性能数据
 * 2. 异常检测 - 基于统计的异常识别
 * 3. 趋势分析 - 性能趋势预测与预警
 * 4. 瓶颈定位 - 自动识别系统瓶颈
 * 5. 建议生成 - 智能优化建议
 * 6. 告警管理 - 多级告警与通知
 * 
 * 目标：问题发现时间 < 10s，告警准确率 > 95%
 */
@Service
public class RealTimePerformanceAnalyzer {
    
    private static final Logger log = LoggerFactory.getLogger(RealTimePerformanceAnalyzer.class);
    
    // 性能指标历史（滑动窗口）
    private final ConcurrentHashMap<String, MetricHistory> metricHistories = new ConcurrentHashMap<>();
    
    // 异常事件队列
    private final BlockingQueue<AnomalyEvent> anomalyEvents = new LinkedBlockingQueue<>(1000);
    
    // 告警状态
    private final ConcurrentHashMap<String, AlertState> alertStates = new ConcurrentHashMap<>();
    
    // JVM 监控
    private final MemoryMXBean memoryMXBean;
    private final ThreadMXBean threadMXBean;
    private final RuntimeMXBean runtimeMXBean;
    private final List<GarbageCollectorMXBean> gcMXBeans;
    
    // 配置
    @Value("${optimization.analyzer.anomaly-detection-enabled:true}")
    private boolean anomalyDetectionEnabled;
    
    @Value("${optimization.analyzer.history-window-minutes:60}")
    private int historyWindowMinutes;
    
    @Value("${optimization.analyzer.anomaly-z-score-threshold:3.0}")
    private double anomalyZScoreThreshold;
    
    @Value("${optimization.analyzer.alert-cooldown-seconds:300}")
    private int alertCooldownSeconds;
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Counter anomalyDetectedCounter;
    private Counter alertTriggeredCounter;
    private Gauge cpuUsageGauge;
    private Gauge memoryUsageGauge;
    private Gauge gcPauseGauge;
    
    // 告警监听器
    private final List<AlertListener> alertListeners = new CopyOnWriteArrayList<>();
    
    // 最近性能快照
    private volatile PerformanceSnapshot latestSnapshot;
    
    public RealTimePerformanceAnalyzer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        this.runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        this.gcMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
    }
    
    @PostConstruct
    public void init() {
        // 注册指标
        anomalyDetectedCounter = Counter.builder("analyzer.anomaly.detected").register(meterRegistry);
        alertTriggeredCounter = Counter.builder("analyzer.alert.triggered").register(meterRegistry);
        
        Gauge.builder("analyzer.anomaly.queue.size", anomalyEvents, BlockingQueue::size)
            .register(meterRegistry);
        Gauge.builder("analyzer.active.alerts", alertStates, m -> 
            m.values().stream().filter(s -> s.isActive).count())
            .register(meterRegistry);
        
        // JVM 指标
        Gauge.builder("jvm.cpu.usage", this, RealTimePerformanceAnalyzer::getCpuUsage)
            .register(meterRegistry);
        Gauge.builder("jvm.memory.heap.usage.ratio", this, RealTimePerformanceAnalyzer::getHeapUsageRatio)
            .register(meterRegistry);
        Gauge.builder("jvm.gc.pause.total.ms", this, RealTimePerformanceAnalyzer::getTotalGcPauseTime)
            .register(meterRegistry);
        
        log.info("RealTimePerformanceAnalyzer initialized: anomalyDetection={}, historyWindow={}min",
            anomalyDetectionEnabled, historyWindowMinutes);
    }
    
    /**
     * 记录指标
     */
    public void recordMetric(String name, double value) {
        MetricHistory history = metricHistories.computeIfAbsent(name, k -> 
            new MetricHistory(k, historyWindowMinutes));
        history.record(value);
        
        // 异常检测
        if (anomalyDetectionEnabled) {
            detectAnomaly(name, value, history);
        }
    }
    
    /**
     * 记录延迟指标
     */
    public void recordLatency(String name, long latencyMs) {
        recordMetric(name + ".latency", latencyMs);
        
        // 延迟异常检测
        if (latencyMs > 1000) {
            createAnomalyEvent(name, AnomalyType.HIGH_LATENCY, latencyMs, 
                "High latency detected: " + latencyMs + "ms");
        }
    }
    
    /**
     * 记录错误
     */
    public void recordError(String name, Throwable error) {
        String metricName = name + ".errors";
        MetricHistory history = metricHistories.computeIfAbsent(metricName, k -> 
            new MetricHistory(k, historyWindowMinutes));
        history.record(1);
        
        // 错误率异常检测
        double errorRate = history.getRecentAverage(60); // 最近1分钟
        if (errorRate > 0.1) { // 错误率超过10%
            createAnomalyEvent(name, AnomalyType.HIGH_ERROR_RATE, errorRate * 100,
                "High error rate detected: " + String.format("%.2f%%", errorRate * 100));
        }
    }
    
    /**
     * 异常检测（基于 Z-Score）
     */
    private void detectAnomaly(String name, double value, MetricHistory history) {
        double mean = history.getMean();
        double stdDev = history.getStdDev();
        
        if (stdDev == 0) return;
        
        double zScore = Math.abs((value - mean) / stdDev);
        
        if (zScore > anomalyZScoreThreshold) {
            AnomalyType type = value > mean ? AnomalyType.SPIKE : AnomalyType.DROP;
            createAnomalyEvent(name, type, value,
                String.format("Anomaly detected: value=%.2f, mean=%.2f, zScore=%.2f", 
                    value, mean, zScore));
        }
    }
    
    /**
     * 创建异常事件
     */
    private void createAnomalyEvent(String source, AnomalyType type, double value, String description) {
        AnomalyEvent event = new AnomalyEvent(
            UUID.randomUUID().toString(),
            source,
            type,
            value,
            description,
            Instant.now()
        );
        
        anomalyEvents.offer(event);
        anomalyDetectedCounter.increment();
        
        log.warn("Anomaly detected: {} - {}", source, description);
        
        // 检查是否需要触发告警
        checkAndTriggerAlert(event);
    }
    
    /**
     * 检查并触发告警
     */
    private void checkAndTriggerAlert(AnomalyEvent event) {
        String alertKey = event.source + ":" + event.type;
        AlertState state = alertStates.computeIfAbsent(alertKey, k -> new AlertState(alertKey));
        
        // 检查冷却时间
        if (state.isInCooldown(alertCooldownSeconds)) {
            return;
        }
        
        // 检查连续异常次数
        state.recordAnomaly();
        if (state.getConsecutiveAnomalies() >= 3) {
            triggerAlert(event, state);
        }
    }
    
    /**
     * 触发告警
     */
    private void triggerAlert(AnomalyEvent event, AlertState state) {
        state.activate();
        alertTriggeredCounter.increment();
        
        Alert alert = new Alert(
            UUID.randomUUID().toString(),
            event.source,
            determineSeverity(event),
            event.description,
            generateRecommendations(event),
            Instant.now()
        );
        
        log.error("ALERT [{}]: {} - {}", alert.severity, alert.source, alert.description);
        
        // 通知监听器
        for (AlertListener listener : alertListeners) {
            try {
                listener.onAlert(alert);
            } catch (Exception e) {
                log.error("Alert listener error", e);
            }
        }
    }
    
    /**
     * 确定告警级别
     */
    private AlertSeverity determineSeverity(AnomalyEvent event) {
        return switch (event.type) {
            case HIGH_LATENCY -> event.value > 5000 ? AlertSeverity.CRITICAL : AlertSeverity.WARNING;
            case HIGH_ERROR_RATE -> event.value > 50 ? AlertSeverity.CRITICAL : AlertSeverity.WARNING;
            case MEMORY_PRESSURE -> event.value > 95 ? AlertSeverity.CRITICAL : AlertSeverity.WARNING;
            case CPU_SPIKE -> event.value > 95 ? AlertSeverity.CRITICAL : AlertSeverity.WARNING;
            default -> AlertSeverity.INFO;
        };
    }
    
    /**
     * 生成优化建议
     */
    private List<String> generateRecommendations(AnomalyEvent event) {
        List<String> recommendations = new ArrayList<>();
        
        switch (event.type) {
            case HIGH_LATENCY -> {
                recommendations.add("检查数据库查询是否需要优化");
                recommendations.add("检查网络连接状态");
                recommendations.add("考虑增加缓存命中率");
                recommendations.add("检查是否存在热点 Key");
            }
            case HIGH_ERROR_RATE -> {
                recommendations.add("检查依赖服务健康状态");
                recommendations.add("检查资源配额是否充足");
                recommendations.add("查看错误日志获取详细信息");
                recommendations.add("考虑启用熔断降级");
            }
            case MEMORY_PRESSURE -> {
                recommendations.add("检查是否存在内存泄漏");
                recommendations.add("考虑增加堆内存");
                recommendations.add("优化对象生命周期");
                recommendations.add("检查缓存大小配置");
            }
            case CPU_SPIKE -> {
                recommendations.add("检查是否存在死循环");
                recommendations.add("分析热点代码路径");
                recommendations.add("考虑增加实例数量");
                recommendations.add("检查 GC 频率");
            }
            default -> recommendations.add("查看详细指标数据进行分析");
        }
        
        return recommendations;
    }
    
    /**
     * 定期采集系统指标
     */
    @Scheduled(fixedRate = 5000) // 5秒
    public void collectSystemMetrics() {
        // CPU 使用率
        double cpuUsage = getCpuUsage();
        recordMetric("system.cpu.usage", cpuUsage);
        
        // 内存使用率
        double heapUsage = getHeapUsageRatio() * 100;
        recordMetric("system.memory.heap.usage", heapUsage);
        
        // GC 统计
        double gcPause = getTotalGcPauseTime();
        recordMetric("system.gc.pause.total", gcPause);
        
        // 线程数
        int threadCount = threadMXBean.getThreadCount();
        recordMetric("system.threads.count", threadCount);
        
        // 检测系统级异常
        if (cpuUsage > 90) {
            createAnomalyEvent("system.cpu", AnomalyType.CPU_SPIKE, cpuUsage,
                "High CPU usage: " + String.format("%.1f%%", cpuUsage));
        }
        if (heapUsage > 85) {
            createAnomalyEvent("system.memory", AnomalyType.MEMORY_PRESSURE, heapUsage,
                "High memory usage: " + String.format("%.1f%%", heapUsage));
        }
        
        // 更新快照
        updateSnapshot();
    }
    
    /**
     * 定期清理过期数据
     */
    @Scheduled(fixedRate = 60000) // 1分钟
    public void cleanup() {
        long threshold = System.currentTimeMillis() - historyWindowMinutes * 60 * 1000L;
        
        // 清理指标历史
        metricHistories.values().forEach(h -> h.cleanup(threshold));
        
        // 清理异常事件（保留最近1000条）
        while (anomalyEvents.size() > 1000) {
            anomalyEvents.poll();
        }
        
        // 重置告警状态
        alertStates.values().forEach(AlertState::checkAndReset);
    }
    
    /**
     * 更新性能快照
     */
    private void updateSnapshot() {
        latestSnapshot = new PerformanceSnapshot(
            getCpuUsage(),
            getHeapUsageRatio() * 100,
            threadMXBean.getThreadCount(),
            getTotalGcPauseTime(),
            getUptime(),
            getMetricSummary(),
            Instant.now()
        );
    }
    
    /**
     * 获取 CPU 使用率
     */
    public double getCpuUsage() {
        try {
            var osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                return sunBean.getProcessCpuLoad() * 100;
            }
        } catch (Exception e) {
            log.debug("Failed to get CPU usage", e);
        }
        return 0;
    }
    
    /**
     * 获取堆内存使用比率
     */
    public double getHeapUsageRatio() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        return (double) heapUsage.getUsed() / heapUsage.getMax();
    }
    
    /**
     * 获取 GC 暂停总时间
     */
    public double getTotalGcPauseTime() {
        return gcMXBeans.stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionTime)
            .sum();
    }
    
    /**
     * 获取运行时间（秒）
     */
    public long getUptime() {
        return runtimeMXBean.getUptime() / 1000;
    }
    
    /**
     * 获取指标摘要
     */
    private Map<String, MetricSummary> getMetricSummary() {
        return metricHistories.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> new MetricSummary(
                    e.getValue().getMean(),
                    e.getValue().getMin(),
                    e.getValue().getMax(),
                    e.getValue().getP99(),
                    e.getValue().getCount()
                )
            ));
    }
    
    /**
     * 注册告警监听器
     */
    public void addAlertListener(AlertListener listener) {
        alertListeners.add(listener);
    }
    
    /**
     * 获取最近异常事件
     */
    public List<AnomalyEvent> getRecentAnomalies(int limit) {
        return anomalyEvents.stream()
            .sorted((a, b) -> b.timestamp.compareTo(a.timestamp))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    /**
     * 获取活跃告警
     */
    public List<AlertState> getActiveAlerts() {
        return alertStates.values().stream()
            .filter(s -> s.isActive)
            .collect(Collectors.toList());
    }
    
    /**
     * 获取最新性能快照
     */
    public PerformanceSnapshot getLatestSnapshot() {
        return latestSnapshot;
    }
    
    /**
     * 获取指标历史
     */
    public MetricHistory getMetricHistory(String name) {
        return metricHistories.get(name);
    }
    
    /**
     * 获取趋势分析
     */
    public TrendAnalysis analyzeTrend(String metricName) {
        MetricHistory history = metricHistories.get(metricName);
        if (history == null) {
            return null;
        }
        
        double recent = history.getRecentAverage(5); // 最近5分钟
        double earlier = history.getEarlierAverage(5, 60); // 5-60分钟前
        
        double changeRate = earlier != 0 ? (recent - earlier) / earlier * 100 : 0;
        TrendDirection direction = changeRate > 10 ? TrendDirection.INCREASING :
                                   changeRate < -10 ? TrendDirection.DECREASING : TrendDirection.STABLE;
        
        return new TrendAnalysis(metricName, direction, changeRate, recent, earlier);
    }
    
    /**
     * 获取瓶颈分析
     */
    public List<BottleneckInfo> analyzeBottlenecks() {
        List<BottleneckInfo> bottlenecks = new ArrayList<>();
        
        // 分析 CPU 瓶颈
        double cpuUsage = getCpuUsage();
        if (cpuUsage > 80) {
            bottlenecks.add(new BottleneckInfo("CPU", cpuUsage, BottleneckSeverity.HIGH,
                "CPU 使用率过高，考虑优化计算密集型代码或增加实例"));
        }
        
        // 分析内存瓶颈
        double heapUsage = getHeapUsageRatio() * 100;
        if (heapUsage > 80) {
            bottlenecks.add(new BottleneckInfo("Memory", heapUsage, BottleneckSeverity.HIGH,
                "内存使用率过高，考虑增加堆内存或优化对象创建"));
        }
        
        // 分析延迟瓶颈
        MetricHistory latencyHistory = metricHistories.get("cache.read.latency");
        if (latencyHistory != null && latencyHistory.getP99() > 100) {
            bottlenecks.add(new BottleneckInfo("Latency", latencyHistory.getP99(), 
                BottleneckSeverity.MEDIUM, "缓存读取延迟过高，检查 Redis/Memcached 连接"));
        }
        
        // 分析线程瓶颈
        int threadCount = threadMXBean.getThreadCount();
        if (threadCount > 500) {
            bottlenecks.add(new BottleneckInfo("Threads", threadCount, BottleneckSeverity.MEDIUM,
                "线程数过多，考虑使用虚拟线程或优化异步处理"));
        }
        
        return bottlenecks;
    }
    
    /**
     * 获取综合统计
     */
    public AnalyzerStats getStats() {
        return new AnalyzerStats(
            metricHistories.size(),
            anomalyEvents.size(),
            alertStates.values().stream().filter(s -> s.isActive).count(),
            getCpuUsage(),
            getHeapUsageRatio() * 100,
            threadMXBean.getThreadCount(),
            getUptime()
        );
    }
    
    // ========== 内部类 ==========
    
    /**
     * 指标历史
     */
    public static class MetricHistory {
        private final String name;
        private final ConcurrentLinkedQueue<DataPoint> dataPoints = new ConcurrentLinkedQueue<>();
        private final int windowMinutes;
        
        MetricHistory(String name, int windowMinutes) {
            this.name = name;
            this.windowMinutes = windowMinutes;
        }
        
        void record(double value) {
            dataPoints.offer(new DataPoint(value, System.currentTimeMillis()));
        }
        
        void cleanup(long threshold) {
            dataPoints.removeIf(dp -> dp.timestamp < threshold);
        }
        
        double getMean() {
            return dataPoints.stream().mapToDouble(dp -> dp.value).average().orElse(0);
        }
        
        double getStdDev() {
            double mean = getMean();
            return Math.sqrt(dataPoints.stream()
                .mapToDouble(dp -> Math.pow(dp.value - mean, 2))
                .average().orElse(0));
        }
        
        double getMin() {
            return dataPoints.stream().mapToDouble(dp -> dp.value).min().orElse(0);
        }
        
        double getMax() {
            return dataPoints.stream().mapToDouble(dp -> dp.value).max().orElse(0);
        }
        
        double getP99() {
            double[] values = dataPoints.stream().mapToDouble(dp -> dp.value).sorted().toArray();
            if (values.length == 0) return 0;
            int index = (int) (values.length * 0.99);
            return values[Math.min(index, values.length - 1)];
        }
        
        long getCount() {
            return dataPoints.size();
        }
        
        double getRecentAverage(int minutes) {
            long threshold = System.currentTimeMillis() - minutes * 60 * 1000L;
            return dataPoints.stream()
                .filter(dp -> dp.timestamp >= threshold)
                .mapToDouble(dp -> dp.value)
                .average().orElse(0);
        }
        
        double getEarlierAverage(int startMinutes, int endMinutes) {
            long now = System.currentTimeMillis();
            long startThreshold = now - endMinutes * 60 * 1000L;
            long endThreshold = now - startMinutes * 60 * 1000L;
            return dataPoints.stream()
                .filter(dp -> dp.timestamp >= startThreshold && dp.timestamp < endThreshold)
                .mapToDouble(dp -> dp.value)
                .average().orElse(0);
        }
        
        record DataPoint(double value, long timestamp) {}
    }
    
    /**
     * 告警状态
     */
    public static class AlertState {
        final String key;
        volatile boolean isActive = false;
        final AtomicInteger consecutiveAnomalies = new AtomicInteger(0);
        volatile long lastAnomalyTime = 0;
        volatile long lastAlertTime = 0;
        
        AlertState(String key) {
            this.key = key;
        }
        
        void recordAnomaly() {
            long now = System.currentTimeMillis();
            if (now - lastAnomalyTime > 60000) { // 超过1分钟重置
                consecutiveAnomalies.set(0);
            }
            consecutiveAnomalies.incrementAndGet();
            lastAnomalyTime = now;
        }
        
        int getConsecutiveAnomalies() {
            return consecutiveAnomalies.get();
        }
        
        void activate() {
            isActive = true;
            lastAlertTime = System.currentTimeMillis();
        }
        
        boolean isInCooldown(int cooldownSeconds) {
            return System.currentTimeMillis() - lastAlertTime < cooldownSeconds * 1000L;
        }
        
        void checkAndReset() {
            if (System.currentTimeMillis() - lastAnomalyTime > 300000) { // 5分钟无异常
                isActive = false;
                consecutiveAnomalies.set(0);
            }
        }
    }
    
    /**
     * 告警监听器接口
     */
    @FunctionalInterface
    public interface AlertListener {
        void onAlert(Alert alert);
    }
    
    // ========== 数据类 ==========
    
    public enum AnomalyType {
        SPIKE, DROP, HIGH_LATENCY, HIGH_ERROR_RATE, MEMORY_PRESSURE, CPU_SPIKE
    }
    
    public enum AlertSeverity { INFO, WARNING, CRITICAL }
    
    public enum TrendDirection { INCREASING, STABLE, DECREASING }
    
    public enum BottleneckSeverity { LOW, MEDIUM, HIGH }
    
    public record AnomalyEvent(String id, String source, AnomalyType type, double value, 
                                String description, Instant timestamp) {}
    
    public record Alert(String id, String source, AlertSeverity severity, String description,
                        List<String> recommendations, Instant timestamp) {}
    
    public record MetricSummary(double mean, double min, double max, double p99, long count) {}
    
    public record PerformanceSnapshot(double cpuUsage, double memoryUsage, int threadCount,
                                       double gcPauseMs, long uptimeSeconds,
                                       Map<String, MetricSummary> metrics, Instant timestamp) {}
    
    public record TrendAnalysis(String metric, TrendDirection direction, double changeRate,
                                 double recentValue, double earlierValue) {}
    
    public record BottleneckInfo(String component, double severity, BottleneckSeverity level, 
                                  String recommendation) {}
    
    public record AnalyzerStats(int metricsCount, int anomalyQueueSize, long activeAlerts,
                                 double cpuUsage, double memoryUsage, int threadCount, long uptime) {}
}
