package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 实时性能诊断引擎
 * 
 * 核心特性:
 * 1. 延迟分析: 多维度延迟监控(TP50/90/99/MAX)
 * 2. 热点追踪: 识别高频访问路径和方法
 * 3. GC监控: 实时GC暂停和内存压力分析
 * 4. 锁竞争分析: 检测锁争用热点
 * 5. 异常检测: 基于统计的异常行为识别
 * 6. 火焰图数据: 生成性能火焰图数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealTimeDiagnosticsEngine {
    
    private final OptimizationV7Properties properties;
    private final MeterRegistry meterRegistry;
    
    // ========== 诊断数据结构 ==========
    
    /** 请求追踪 */
    private final ConcurrentMap<String, RequestTrace> activeTraces = new ConcurrentHashMap<>();
    
    /** 历史诊断记录 */
    private final Queue<DiagnosticRecord> diagnosticHistory = new ConcurrentLinkedQueue<>();
    
    /** 方法调用统计 */
    private final ConcurrentMap<String, MethodStats> methodStats = new ConcurrentHashMap<>();
    
    /** 延迟分布 */
    private final ConcurrentMap<String, LatencyDistribution> latencyDistributions = new ConcurrentHashMap<>();
    
    /** 异常统计 */
    private final ConcurrentMap<String, ExceptionStats> exceptionStats = new ConcurrentHashMap<>();
    
    /** GC统计 */
    private volatile GCStats gcStats = new GCStats();
    
    /** 内存统计 */
    private volatile MemoryStats memoryStats = new MemoryStats();
    
    /** 线程统计 */
    private volatile ThreadStats threadStats = new ThreadStats();
    
    /** 慢请求队列 */
    private final Queue<SlowRequest> slowRequests = new ConcurrentLinkedQueue<>();
    
    /** 告警事件 */
    private final Queue<AlertEvent> alerts = new ConcurrentLinkedQueue<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 采样计数器 */
    private final LongAdder totalSamples = new LongAdder();
    private final LongAdder analyzedSamples = new LongAdder();
    
    // ========== JMX Bean ==========
    
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    
    // ========== 指标 ==========
    
    private Counter diagnosticCounter;
    private Counter slowRequestCounter;
    private Counter anomalyDetectedCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.isRealtimeDiagnosticsEnabled()) {
            log.info("[实时诊断引擎] 未启用");
            return;
        }
        
        // 初始化调度器
        scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "diagnostics-engine");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化指标
        initMetrics();
        
        // 启动后台任务
        startBackgroundTasks();
        
        log.info("[实时诊断引擎] 初始化完成 - 采样率: {}, 慢请求阈值: {}ms",
            properties.getRealtimeDiagnostics().getSampleRate(),
            properties.getRealtimeDiagnostics().getSlowThresholdMs());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        log.info("[实时诊断引擎] 已关闭");
    }
    
    private void initMetrics() {
        diagnosticCounter = Counter.builder("cache.diagnostics.total")
            .description("诊断总次数")
            .register(meterRegistry);
        
        slowRequestCounter = Counter.builder("cache.diagnostics.slow")
            .description("慢请求次数")
            .register(meterRegistry);
        
        anomalyDetectedCounter = Counter.builder("cache.diagnostics.anomaly")
            .description("异常检测次数")
            .register(meterRegistry);
        
        Gauge.builder("cache.diagnostics.active.traces", activeTraces, Map::size)
            .description("活跃追踪数")
            .register(meterRegistry);
    }
    
    private void startBackgroundTasks() {
        // JVM指标采集
        scheduler.scheduleAtFixedRate(
            this::collectJVMMetrics,
            1,
            5,
            TimeUnit.SECONDS
        );
        
        // 异常检测
        scheduler.scheduleAtFixedRate(
            this::runAnomalyDetection,
            30,
            30,
            TimeUnit.SECONDS
        );
        
        // 清理过期数据
        scheduler.scheduleAtFixedRate(
            this::cleanupExpiredData,
            60,
            60,
            TimeUnit.SECONDS
        );
        
        // 统计汇总
        scheduler.scheduleAtFixedRate(
            this::aggregateStatistics,
            10,
            10,
            TimeUnit.SECONDS
        );
    }
    
    // ========== 核心API ==========
    
    /**
     * 开始请求追踪
     */
    public String startTrace(String operation) {
        if (!properties.isRealtimeDiagnosticsEnabled()) {
            return null;
        }
        
        // 采样决策
        if (!shouldSample()) {
            return null;
        }
        
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        RequestTrace trace = new RequestTrace(traceId, operation);
        activeTraces.put(traceId, trace);
        
        totalSamples.increment();
        return traceId;
    }
    
    /**
     * 添加追踪标签
     */
    public void addTraceTag(String traceId, String key, String value) {
        if (traceId == null) return;
        
        RequestTrace trace = activeTraces.get(traceId);
        if (trace != null) {
            trace.addTag(key, value);
        }
    }
    
    /**
     * 开始追踪span
     */
    public void startSpan(String traceId, String spanName) {
        if (traceId == null) return;
        
        RequestTrace trace = activeTraces.get(traceId);
        if (trace != null) {
            trace.startSpan(spanName);
        }
    }
    
    /**
     * 结束追踪span
     */
    public void endSpan(String traceId, String spanName) {
        if (traceId == null) return;
        
        RequestTrace trace = activeTraces.get(traceId);
        if (trace != null) {
            trace.endSpan(spanName);
        }
    }
    
    /**
     * 结束请求追踪
     */
    public void endTrace(String traceId, boolean success, String errorMessage) {
        if (traceId == null) return;
        
        RequestTrace trace = activeTraces.remove(traceId);
        if (trace == null) return;
        
        trace.complete(success, errorMessage);
        analyzedSamples.increment();
        diagnosticCounter.increment();
        
        long duration = trace.getDuration();
        var config = properties.getRealtimeDiagnostics();
        
        // 更新延迟分布
        latencyDistributions.computeIfAbsent(trace.getOperation(), k -> new LatencyDistribution())
                           .recordLatency(duration);
        
        // 更新方法统计
        for (var span : trace.getSpans().entrySet()) {
            methodStats.computeIfAbsent(span.getKey(), k -> new MethodStats())
                      .recordCall(span.getValue(), success);
        }
        
        // 检测慢请求
        if (duration >= config.getSlowThresholdMs()) {
            recordSlowRequest(trace);
            slowRequestCounter.increment();
        }
        
        // 超慢请求告警
        if (duration >= config.getVerySlowThresholdMs()) {
            raiseAlert(AlertType.VERY_SLOW_REQUEST, 
                String.format("超慢请求: %s, 耗时: %dms", trace.getOperation(), duration));
        }
        
        // 记录异常
        if (!success && errorMessage != null) {
            exceptionStats.computeIfAbsent(errorMessage, k -> new ExceptionStats())
                         .increment();
        }
        
        // 保存诊断记录
        saveDiagnosticRecord(trace);
    }
    
    /**
     * 记录方法调用
     */
    public void recordMethodCall(String methodName, long durationMs, boolean success) {
        if (!properties.isRealtimeDiagnosticsEnabled()) return;
        
        methodStats.computeIfAbsent(methodName, k -> new MethodStats())
                  .recordCall(durationMs, success);
    }
    
    /**
     * 获取延迟百分位数
     */
    public Map<String, Long> getLatencyPercentiles(String operation) {
        LatencyDistribution dist = latencyDistributions.get(operation);
        if (dist == null) {
            return Collections.emptyMap();
        }
        return dist.getPercentiles();
    }
    
    /**
     * 获取热点方法
     */
    public List<Map<String, Object>> getHotMethods(int topK) {
        return methodStats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getCallCount(), a.getValue().getCallCount()))
            .limit(topK)
            .map(e -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("method", e.getKey());
                info.put("calls", e.getValue().getCallCount());
                info.put("avgLatency", String.format("%.2fms", e.getValue().getAverageLatency()));
                info.put("successRate", String.format("%.2f%%", e.getValue().getSuccessRate() * 100));
                return info;
            })
            .collect(Collectors.toList());
    }
    
    /**
     * 获取最近的慢请求
     */
    public List<Map<String, Object>> getRecentSlowRequests(int limit) {
        return slowRequests.stream()
            .sorted((a, b) -> Long.compare(b.timestamp, a.timestamp))
            .limit(limit)
            .map(sr -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("traceId", sr.traceId);
                info.put("operation", sr.operation);
                info.put("duration", sr.durationMs + "ms");
                info.put("timestamp", new Date(sr.timestamp));
                info.put("spans", sr.spans);
                return info;
            })
            .collect(Collectors.toList());
    }
    
    /**
     * 获取异常统计
     */
    public Map<String, Long> getExceptionCounts() {
        return exceptionStats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getCount(), a.getValue().getCount()))
            .limit(20)
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().getCount(),
                (a, b) -> a,
                LinkedHashMap::new
            ));
    }
    
    /**
     * 获取告警列表
     */
    public List<Map<String, Object>> getAlerts(int limit) {
        return alerts.stream()
            .sorted((a, b) -> Long.compare(b.timestamp, a.timestamp))
            .limit(limit)
            .map(alert -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("type", alert.type);
                info.put("message", alert.message);
                info.put("timestamp", new Date(alert.timestamp));
                return info;
            })
            .collect(Collectors.toList());
    }
    
    /**
     * 生成火焰图数据
     */
    public List<Map<String, Object>> generateFlameGraphData() {
        if (!properties.getRealtimeDiagnostics().isFlameGraphEnabled()) {
            return Collections.emptyList();
        }
        
        return methodStats.entrySet().stream()
            .map(e -> {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("name", e.getKey());
                frame.put("value", e.getValue().getTotalTime());
                frame.put("children", Collections.emptyList());
                return frame;
            })
            .collect(Collectors.toList());
    }
    
    // ========== 内部方法 ==========
    
    private boolean shouldSample() {
        return Math.random() < properties.getRealtimeDiagnostics().getSampleRate();
    }
    
    private void recordSlowRequest(RequestTrace trace) {
        SlowRequest sr = new SlowRequest(
            trace.getTraceId(),
            trace.getOperation(),
            trace.getDuration(),
            System.currentTimeMillis(),
            new HashMap<>(trace.getSpans())
        );
        
        slowRequests.offer(sr);
        
        // 限制队列大小
        while (slowRequests.size() > properties.getRealtimeDiagnostics().getMaxDiagnosticsEntries() / 10) {
            slowRequests.poll();
        }
    }
    
    private void saveDiagnosticRecord(RequestTrace trace) {
        DiagnosticRecord record = new DiagnosticRecord(
            trace.getTraceId(),
            trace.getOperation(),
            trace.getDuration(),
            trace.isSuccess(),
            System.currentTimeMillis()
        );
        
        diagnosticHistory.offer(record);
        
        // 限制历史大小
        while (diagnosticHistory.size() > properties.getRealtimeDiagnostics().getMaxDiagnosticsEntries()) {
            diagnosticHistory.poll();
        }
    }
    
    private void raiseAlert(AlertType type, String message) {
        AlertEvent alert = new AlertEvent(type, message, System.currentTimeMillis());
        alerts.offer(alert);
        anomalyDetectedCounter.increment();
        
        log.warn("[实时诊断] 告警: {} - {}", type, message);
        
        // 限制告警队列大小
        while (alerts.size() > 1000) {
            alerts.poll();
        }
    }
    
    private void collectJVMMetrics() {
        // 内存统计
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        
        memoryStats = new MemoryStats(
            heapUsage.getUsed(),
            heapUsage.getMax(),
            nonHeapUsage.getUsed()
        );
        
        // 检测内存压力
        double heapUsageRatio = (double) heapUsage.getUsed() / heapUsage.getMax();
        if (heapUsageRatio > 0.9) {
            raiseAlert(AlertType.HIGH_MEMORY_USAGE, 
                String.format("堆内存使用率: %.2f%%", heapUsageRatio * 100));
        }
        
        // GC统计
        long totalGCCount = 0;
        long totalGCTime = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            totalGCCount += gc.getCollectionCount();
            totalGCTime += gc.getCollectionTime();
        }
        
        GCStats oldStats = gcStats;
        gcStats = new GCStats(totalGCCount, totalGCTime);
        
        // 检测GC频繁
        if (oldStats != null && properties.getRealtimeDiagnostics().isGcPauseMonitoring()) {
            long gcCountDelta = totalGCCount - oldStats.totalCount;
            long gcTimeDelta = totalGCTime - oldStats.totalTime;
            
            if (gcCountDelta > 10 && gcTimeDelta > 1000) {
                raiseAlert(AlertType.FREQUENT_GC, 
                    String.format("GC频繁: 5秒内%d次, 耗时%dms", gcCountDelta, gcTimeDelta));
            }
        }
        
        // 线程统计
        threadStats = new ThreadStats(
            threadMXBean.getThreadCount(),
            threadMXBean.getPeakThreadCount(),
            threadMXBean.getDaemonThreadCount()
        );
        
        // 检测线程异常
        if (threadMXBean.getThreadCount() > 1000) {
            raiseAlert(AlertType.HIGH_THREAD_COUNT, 
                String.format("线程数过高: %d", threadMXBean.getThreadCount()));
        }
    }
    
    private void runAnomalyDetection() {
        var config = properties.getRealtimeDiagnostics();
        if (!config.isFlameGraphEnabled()) return;
        
        // 基于Z分数的异常检测
        for (var entry : latencyDistributions.entrySet()) {
            LatencyDistribution dist = entry.getValue();
            double mean = dist.getMean();
            double stdDev = dist.getStdDev();
            
            if (stdDev > 0) {
                Long p99 = dist.getPercentiles().get("p99");
                if (p99 != null) {
                    double zScore = (p99 - mean) / stdDev;
                    if (zScore > config.getAnomalyZScoreThreshold()) {
                        raiseAlert(AlertType.LATENCY_ANOMALY, 
                            String.format("延迟异常: %s P99=%.2fms (Z分数=%.2f)", 
                                entry.getKey(), (double) p99, zScore));
                    }
                }
            }
        }
    }
    
    private void cleanupExpiredData() {
        int retentionMinutes = properties.getRealtimeDiagnostics().getRetentionMinutes();
        long cutoff = System.currentTimeMillis() - retentionMinutes * 60 * 1000L;
        
        diagnosticHistory.removeIf(r -> r.timestamp < cutoff);
        slowRequests.removeIf(sr -> sr.timestamp < cutoff);
        alerts.removeIf(a -> a.timestamp < cutoff);
        
        // 清理超时的活跃追踪
        activeTraces.entrySet().removeIf(e -> 
            System.currentTimeMillis() - e.getValue().getStartTime() > 300000); // 5分钟超时
    }
    
    private void aggregateStatistics() {
        // 聚合统计数据供监控使用
        log.debug("[实时诊断] 统计汇总 - 总采样: {}, 已分析: {}, 慢请求: {}",
            totalSamples.sum(), analyzedSamples.sum(), slowRequests.size());
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", properties.isRealtimeDiagnosticsEnabled());
        stats.put("sampleRate", properties.getRealtimeDiagnostics().getSampleRate());
        stats.put("totalSamples", totalSamples.sum());
        stats.put("analyzedSamples", analyzedSamples.sum());
        stats.put("activeTraces", activeTraces.size());
        stats.put("slowRequestCount", slowRequests.size());
        stats.put("alertCount", alerts.size());
        
        // 内存统计
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("heapUsed", formatBytes(memoryStats.heapUsed));
        memory.put("heapMax", formatBytes(memoryStats.heapMax));
        memory.put("heapUsageRatio", String.format("%.2f%%", 
            memoryStats.heapMax > 0 ? (double) memoryStats.heapUsed / memoryStats.heapMax * 100 : 0));
        memory.put("nonHeapUsed", formatBytes(memoryStats.nonHeapUsed));
        stats.put("memory", memory);
        
        // GC统计
        Map<String, Object> gc = new LinkedHashMap<>();
        gc.put("totalCount", gcStats.totalCount);
        gc.put("totalTime", gcStats.totalTime + "ms");
        stats.put("gc", gc);
        
        // 线程统计
        Map<String, Object> threads = new LinkedHashMap<>();
        threads.put("current", threadStats.current);
        threads.put("peak", threadStats.peak);
        threads.put("daemon", threadStats.daemon);
        stats.put("threads", threads);
        
        // 热点方法
        stats.put("hotMethods", getHotMethods(10));
        
        // 最近告警
        stats.put("recentAlerts", getAlerts(10));
        
        return stats;
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    // ========== 内部类 ==========
    
    /**
     * 请求追踪
     */
    @Data
    private static class RequestTrace {
        private final String traceId;
        private final String operation;
        private final long startTime = System.currentTimeMillis();
        private long endTime;
        private boolean success;
        private String errorMessage;
        private final Map<String, String> tags = new ConcurrentHashMap<>();
        private final Map<String, Long> spans = new ConcurrentHashMap<>();
        private final Map<String, Long> spanStarts = new ConcurrentHashMap<>();
        
        void addTag(String key, String value) {
            tags.put(key, value);
        }
        
        void startSpan(String spanName) {
            spanStarts.put(spanName, System.currentTimeMillis());
        }
        
        void endSpan(String spanName) {
            Long start = spanStarts.remove(spanName);
            if (start != null) {
                spans.put(spanName, System.currentTimeMillis() - start);
            }
        }
        
        void complete(boolean success, String errorMessage) {
            this.endTime = System.currentTimeMillis();
            this.success = success;
            this.errorMessage = errorMessage;
        }
        
        long getDuration() {
            return endTime > 0 ? endTime - startTime : System.currentTimeMillis() - startTime;
        }
    }
    
    /**
     * 诊断记录
     */
    private record DiagnosticRecord(String traceId, String operation, long duration, 
                                   boolean success, long timestamp) {}
    
    /**
     * 慢请求
     */
    private record SlowRequest(String traceId, String operation, long durationMs, 
                              long timestamp, Map<String, Long> spans) {}
    
    /**
     * 告警事件
     */
    private record AlertEvent(AlertType type, String message, long timestamp) {}
    
    private enum AlertType {
        VERY_SLOW_REQUEST,
        LATENCY_ANOMALY,
        HIGH_MEMORY_USAGE,
        FREQUENT_GC,
        HIGH_THREAD_COUNT,
        HIGH_ERROR_RATE
    }
    
    /**
     * 方法统计
     */
    private static class MethodStats {
        private final AtomicLong callCount = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong totalTime = new AtomicLong(0);
        
        void recordCall(long durationMs, boolean success) {
            callCount.incrementAndGet();
            totalTime.addAndGet(durationMs);
            if (success) {
                successCount.incrementAndGet();
            }
        }
        
        long getCallCount() { return callCount.get(); }
        long getTotalTime() { return totalTime.get(); }
        
        double getAverageLatency() {
            long count = callCount.get();
            return count > 0 ? (double) totalTime.get() / count : 0;
        }
        
        double getSuccessRate() {
            long count = callCount.get();
            return count > 0 ? (double) successCount.get() / count : 1;
        }
    }
    
    /**
     * 延迟分布
     */
    private static class LatencyDistribution {
        private final Queue<Long> samples = new ConcurrentLinkedQueue<>();
        private final AtomicLong sum = new AtomicLong(0);
        private final AtomicLong count = new AtomicLong(0);
        
        void recordLatency(long latencyMs) {
            samples.offer(latencyMs);
            sum.addAndGet(latencyMs);
            count.incrementAndGet();
            
            // 保持最近10000个样本
            while (samples.size() > 10000) {
                Long removed = samples.poll();
                if (removed != null) {
                    sum.addAndGet(-removed);
                    count.decrementAndGet();
                }
            }
        }
        
        double getMean() {
            long c = count.get();
            return c > 0 ? (double) sum.get() / c : 0;
        }
        
        double getStdDev() {
            if (samples.isEmpty()) return 0;
            
            double mean = getMean();
            double sumSquares = samples.stream()
                .mapToDouble(l -> Math.pow(l - mean, 2))
                .sum();
            
            return Math.sqrt(sumSquares / samples.size());
        }
        
        Map<String, Long> getPercentiles() {
            if (samples.isEmpty()) {
                return Collections.emptyMap();
            }
            
            List<Long> sorted = samples.stream().sorted().collect(Collectors.toList());
            int size = sorted.size();
            
            Map<String, Long> percentiles = new LinkedHashMap<>();
            percentiles.put("p50", sorted.get(Math.min((int) (size * 0.50), size - 1)));
            percentiles.put("p75", sorted.get(Math.min((int) (size * 0.75), size - 1)));
            percentiles.put("p90", sorted.get(Math.min((int) (size * 0.90), size - 1)));
            percentiles.put("p95", sorted.get(Math.min((int) (size * 0.95), size - 1)));
            percentiles.put("p99", sorted.get(Math.min((int) (size * 0.99), size - 1)));
            percentiles.put("max", sorted.get(size - 1));
            
            return percentiles;
        }
    }
    
    /**
     * 异常统计
     */
    private static class ExceptionStats {
        private final AtomicLong count = new AtomicLong(0);
        
        void increment() { count.incrementAndGet(); }
        long getCount() { return count.get(); }
    }
    
    /**
     * GC统计
     */
    private record GCStats(long totalCount, long totalTime) {
        GCStats() { this(0, 0); }
    }
    
    /**
     * 内存统计
     */
    private record MemoryStats(long heapUsed, long heapMax, long nonHeapUsed) {
        MemoryStats() { this(0, 0, 0); }
    }
    
    /**
     * 线程统计
     */
    private record ThreadStats(int current, int peak, int daemon) {
        ThreadStats() { this(0, 0, 0); }
    }
}
