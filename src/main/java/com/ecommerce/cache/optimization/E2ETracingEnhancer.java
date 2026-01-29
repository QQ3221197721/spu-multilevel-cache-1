package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 端到端链路追踪增强器 - 完整的缓存调用链监控
 * 
 * 核心特性:
 * 1. 全链路追踪 - L1/L2/L3/DB完整路径
 * 2. 延迟分析 - 各层级耗时统计
 * 3. 异常追踪 - 错误链路标记与归因
 * 4. 采样策略 - 自适应采样率调整
 * 5. 上下文传播 - 跨服务追踪上下文
 * 6. 实时告警 - 慢请求自动告警
 * 
 * 目标: 100%关键路径覆盖, 慢请求检测延迟 < 1s
 */
@Service
public class E2ETracingEnhancer {
    
    private static final Logger log = LoggerFactory.getLogger(E2ETracingEnhancer.class);
    
    // ========== 追踪上下文键 ==========
    private static final String TRACE_ID = "X-Trace-Id";
    private static final String SPAN_ID = "X-Span-Id";
    private static final String PARENT_SPAN_ID = "X-Parent-Span-Id";
    private static final String SAMPLE_FLAG = "X-Sample-Flag";
    
    // ========== 配置参数 ==========
    @Value("${optimization.tracing.enabled:true}")
    private boolean tracingEnabled;
    
    @Value("${optimization.tracing.sample-rate:0.1}")
    private double sampleRate;
    
    @Value("${optimization.tracing.slow-threshold-ms:100}")
    private long slowThresholdMs;
    
    @Value("${optimization.tracing.max-spans-per-trace:100}")
    private int maxSpansPerTrace;
    
    @Value("${optimization.tracing.retention-minutes:60}")
    private int retentionMinutes;
    
    @Value("${optimization.tracing.adaptive-sampling:true}")
    private boolean adaptiveSampling;
    
    // ========== 数据结构 ==========
    
    // 活跃追踪
    private final ConcurrentHashMap<String, TraceContext> activeTraces = new ConcurrentHashMap<>();
    
    // 已完成追踪（最近N条）
    private final ConcurrentLinkedDeque<CompletedTrace> completedTraces = new ConcurrentLinkedDeque<>();
    
    // 慢请求记录
    private final ConcurrentLinkedDeque<SlowRequest> slowRequests = new ConcurrentLinkedDeque<>();
    
    // 错误追踪
    private final ConcurrentLinkedDeque<ErrorTrace> errorTraces = new ConcurrentLinkedDeque<>();
    
    // 层级延迟统计
    private final ConcurrentHashMap<String, LatencyStats> layerLatencyStats = new ConcurrentHashMap<>();
    
    // 追踪上下文ThreadLocal
    private final ThreadLocal<TraceContext> currentTraceContext = new ThreadLocal<>();
    
    // 自适应采样率
    private volatile double adaptedSampleRate;
    
    // 依赖
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    
    // 指标
    private Timer traceTimer;
    private Counter traceCounter;
    private Counter slowRequestCounter;
    private Counter errorCounter;
    
    // 统计
    private final LongAdder totalTraces = new LongAdder();
    private final LongAdder sampledTraces = new LongAdder();
    private final LongAdder slowTraces = new LongAdder();
    
    public E2ETracingEnhancer(MeterRegistry meterRegistry, Tracer tracer) {
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        this.adaptedSampleRate = sampleRate;
        
        // 初始化层级延迟统计
        for (String layer : Arrays.asList("L1", "L2", "L3", "DB", "TOTAL")) {
            layerLatencyStats.put(layer, new LatencyStats());
        }
    }
    
    @PostConstruct
    public void init() {
        traceTimer = Timer.builder("cache.trace.duration")
            .publishPercentileHistogram()
            .publishPercentiles(0.5, 0.9, 0.95, 0.99)
            .register(meterRegistry);
        traceCounter = Counter.builder("cache.trace.total").register(meterRegistry);
        slowRequestCounter = Counter.builder("cache.trace.slow").register(meterRegistry);
        errorCounter = Counter.builder("cache.trace.errors").register(meterRegistry);
        
        Gauge.builder("cache.trace.active", activeTraces, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("cache.trace.sample.rate", () -> adaptedSampleRate)
            .register(meterRegistry);
        
        // 为每个层级注册延迟指标
        layerLatencyStats.forEach((layer, stats) -> {
            Gauge.builder("cache.trace.layer.avg.latency", stats, LatencyStats::getAvgLatency)
                .tag("layer", layer)
                .register(meterRegistry);
            Gauge.builder("cache.trace.layer.p99.latency", stats, LatencyStats::getP99Latency)
                .tag("layer", layer)
                .register(meterRegistry);
        });
        
        log.info("E2ETracingEnhancer initialized: sampleRate={}, slowThreshold={}ms",
            sampleRate, slowThresholdMs);
    }
    
    /**
     * 开始追踪
     */
    public TraceContext startTrace(String operation, String key) {
        if (!tracingEnabled) return null;
        
        // 采样决策
        boolean shouldSample = shouldSample();
        if (!shouldSample) return null;
        
        String traceId = generateTraceId();
        String spanId = generateSpanId();
        
        TraceContext context = new TraceContext(
            traceId, spanId, null, operation, key,
            System.currentTimeMillis(), new ConcurrentLinkedDeque<>(),
            new AtomicReference<>(null), shouldSample
        );
        
        activeTraces.put(traceId, context);
        currentTraceContext.set(context);
        
        // 设置MDC用于日志关联
        MDC.put(TRACE_ID, traceId);
        MDC.put(SPAN_ID, spanId);
        
        // 创建Micrometer追踪Span
        if (tracer != null) {
            Span span = tracer.nextSpan()
                .name(operation)
                .tag("cache.key", key)
                .start();
            context.micrometerSpan.set(span);
        }
        
        totalTraces.increment();
        sampledTraces.increment();
        traceCounter.increment();
        
        return context;
    }
    
    /**
     * 开始子Span
     */
    public SpanContext startSpan(String layer, String operation) {
        TraceContext trace = currentTraceContext.get();
        if (trace == null || !trace.sampled) return null;
        
        String spanId = generateSpanId();
        SpanContext span = new SpanContext(
            spanId, trace.spanId, layer, operation,
            System.currentTimeMillis(), new AtomicLong(0),
            new AtomicReference<>(null), new ConcurrentHashMap<>()
        );
        
        trace.spans.add(span);
        
        return span;
    }
    
    /**
     * 结束子Span
     */
    public void endSpan(SpanContext span) {
        if (span == null) return;
        
        long duration = System.currentTimeMillis() - span.startTime;
        span.duration.set(duration);
        
        // 记录层级延迟
        LatencyStats stats = layerLatencyStats.get(span.layer);
        if (stats != null) {
            stats.recordLatency(duration);
        }
        
        // 检查是否为慢请求
        if (duration > slowThresholdMs) {
            log.debug("Slow span detected: layer={}, operation={}, duration={}ms",
                span.layer, span.operation, duration);
        }
    }
    
    /**
     * 记录错误
     */
    public void recordError(SpanContext span, Throwable error) {
        if (span == null) return;
        
        span.error.set(error);
        span.tags.put("error", "true");
        span.tags.put("error.message", error.getMessage());
        span.tags.put("error.type", error.getClass().getSimpleName());
        
        errorCounter.increment();
    }
    
    /**
     * 添加标签
     */
    public void addTag(SpanContext span, String key, String value) {
        if (span != null) {
            span.tags.put(key, value);
        }
    }
    
    /**
     * 结束追踪
     */
    public void endTrace(TraceContext context) {
        if (context == null) return;
        
        long duration = System.currentTimeMillis() - context.startTime;
        
        // 记录总延迟
        LatencyStats totalStats = layerLatencyStats.get("TOTAL");
        if (totalStats != null) {
            totalStats.recordLatency(duration);
        }
        
        traceTimer.record(java.time.Duration.ofMillis(duration));
        
        // 检查是否为慢请求
        if (duration > slowThresholdMs) {
            recordSlowRequest(context, duration);
        }
        
        // 检查是否有错误
        boolean hasError = context.spans.stream().anyMatch(s -> s.error.get() != null);
        if (hasError) {
            recordErrorTrace(context);
        }
        
        // 完成追踪
        CompletedTrace completed = new CompletedTrace(
            context.traceId, context.operation, context.key,
            context.startTime, duration,
            new ArrayList<>(context.spans), hasError
        );
        
        // 保存已完成追踪
        completedTraces.addLast(completed);
        while (completedTraces.size() > 10000) {
            completedTraces.pollFirst();
        }
        
        // 结束Micrometer Span
        Span micrometerSpan = context.micrometerSpan.get();
        if (micrometerSpan != null) {
            if (hasError) {
                micrometerSpan.tag("error", "true");
            }
            micrometerSpan.end();
        }
        
        // 清理
        activeTraces.remove(context.traceId);
        currentTraceContext.remove();
        MDC.remove(TRACE_ID);
        MDC.remove(SPAN_ID);
    }
    
    /**
     * 记录慢请求
     */
    private void recordSlowRequest(TraceContext context, long duration) {
        slowTraces.increment();
        slowRequestCounter.increment();
        
        // 计算各层级耗时
        Map<String, Long> layerDurations = new HashMap<>();
        for (SpanContext span : context.spans) {
            layerDurations.merge(span.layer, span.duration.get(), Long::sum);
        }
        
        SlowRequest slow = new SlowRequest(
            context.traceId, context.operation, context.key,
            context.startTime, duration, layerDurations,
            identifyBottleneck(layerDurations)
        );
        
        slowRequests.addLast(slow);
        while (slowRequests.size() > 1000) {
            slowRequests.pollFirst();
        }
        
        log.warn("Slow request: traceId={}, operation={}, duration={}ms, bottleneck={}",
            context.traceId, context.operation, duration, slow.bottleneck);
    }
    
    /**
     * 记录错误追踪
     */
    private void recordErrorTrace(TraceContext context) {
        // 收集所有错误
        List<String> errors = new ArrayList<>();
        for (SpanContext span : context.spans) {
            Throwable error = span.error.get();
            if (error != null) {
                errors.add(span.layer + ": " + error.getMessage());
            }
        }
        
        ErrorTrace errorTrace = new ErrorTrace(
            context.traceId, context.operation, context.key,
            context.startTime, errors
        );
        
        errorTraces.addLast(errorTrace);
        while (errorTraces.size() > 500) {
            errorTraces.pollFirst();
        }
    }
    
    /**
     * 识别瓶颈
     */
    private String identifyBottleneck(Map<String, Long> layerDurations) {
        return layerDurations.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("UNKNOWN");
    }
    
    /**
     * 采样决策
     */
    private boolean shouldSample() {
        if (!adaptiveSampling) {
            return ThreadLocalRandom.current().nextDouble() < sampleRate;
        }
        return ThreadLocalRandom.current().nextDouble() < adaptedSampleRate;
    }
    
    /**
     * 定期调整采样率
     */
    @Scheduled(fixedRate = 60000) // 1分钟
    public void adjustSampleRate() {
        if (!adaptiveSampling) return;
        
        // 根据慢请求比例调整
        long total = totalTraces.sum();
        long slow = slowTraces.sum();
        
        if (total > 100) {
            double slowRatio = (double) slow / total;
            
            // 慢请求多时提高采样率以便分析
            if (slowRatio > 0.1) {
                adaptedSampleRate = Math.min(1.0, adaptedSampleRate * 1.5);
            } else if (slowRatio < 0.01 && adaptedSampleRate > sampleRate) {
                adaptedSampleRate = Math.max(sampleRate, adaptedSampleRate * 0.8);
            }
            
            // 重置计数器
            totalTraces.reset();
            slowTraces.reset();
        }
    }
    
    /**
     * 定期清理过期追踪
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void cleanupExpiredTraces() {
        long threshold = System.currentTimeMillis() - (retentionMinutes * 60000L);
        
        // 清理已完成追踪
        completedTraces.removeIf(t -> t.startTime < threshold);
        
        // 清理慢请求
        slowRequests.removeIf(r -> r.timestamp < threshold);
        
        // 清理错误追踪
        errorTraces.removeIf(e -> e.timestamp < threshold);
        
        // 清理超时的活跃追踪
        long activeThreshold = System.currentTimeMillis() - 60000; // 1分钟超时
        activeTraces.entrySet().removeIf(e -> e.getValue().startTime < activeThreshold);
        
        log.debug("Cleaned up expired traces, remaining: completed={}, slow={}, errors={}",
            completedTraces.size(), slowRequests.size(), errorTraces.size());
    }
    
    /**
     * 获取当前追踪上下文
     */
    public TraceContext getCurrentTrace() {
        return currentTraceContext.get();
    }
    
    /**
     * 从请求头恢复追踪上下文
     */
    public TraceContext restoreFromHeaders(Map<String, String> headers) {
        String traceId = headers.get(TRACE_ID);
        String parentSpanId = headers.get(SPAN_ID);
        String sampleFlag = headers.get(SAMPLE_FLAG);
        
        if (traceId == null) return null;
        
        boolean sampled = "1".equals(sampleFlag) || shouldSample();
        if (!sampled) return null;
        
        String spanId = generateSpanId();
        
        TraceContext context = new TraceContext(
            traceId, spanId, parentSpanId, "restored", null,
            System.currentTimeMillis(), new ConcurrentLinkedDeque<>(),
            new AtomicReference<>(null), sampled
        );
        
        activeTraces.put(traceId, context);
        currentTraceContext.set(context);
        
        MDC.put(TRACE_ID, traceId);
        MDC.put(SPAN_ID, spanId);
        
        return context;
    }
    
    /**
     * 生成请求头用于传播
     */
    public Map<String, String> getHeadersForPropagation() {
        TraceContext context = currentTraceContext.get();
        if (context == null) return Collections.emptyMap();
        
        Map<String, String> headers = new HashMap<>();
        headers.put(TRACE_ID, context.traceId);
        headers.put(SPAN_ID, context.spanId);
        headers.put(SAMPLE_FLAG, context.sampled ? "1" : "0");
        if (context.parentSpanId != null) {
            headers.put(PARENT_SPAN_ID, context.parentSpanId);
        }
        return headers;
    }
    
    /**
     * 获取追踪统计
     */
    public TracingStats getStats() {
        Map<String, LayerLatency> layerLatencies = new HashMap<>();
        layerLatencyStats.forEach((layer, stats) -> {
            layerLatencies.put(layer, new LayerLatency(
                stats.getAvgLatency(),
                stats.getP50Latency(),
                stats.getP99Latency(),
                stats.getMaxLatency()
            ));
        });
        
        return new TracingStats(
            tracingEnabled,
            adaptedSampleRate,
            activeTraces.size(),
            completedTraces.size(),
            slowRequests.size(),
            errorTraces.size(),
            layerLatencies
        );
    }
    
    /**
     * 获取最近的慢请求
     */
    public List<SlowRequest> getRecentSlowRequests(int limit) {
        List<SlowRequest> result = new ArrayList<>();
        Iterator<SlowRequest> it = slowRequests.descendingIterator();
        while (it.hasNext() && result.size() < limit) {
            result.add(it.next());
        }
        return result;
    }
    
    /**
     * 获取追踪详情
     */
    public Optional<CompletedTrace> getTrace(String traceId) {
        return completedTraces.stream()
            .filter(t -> t.traceId.equals(traceId))
            .findFirst();
    }
    
    // ========== 辅助方法 ==========
    
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    
    private String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
    
    // ========== 内部类 ==========
    
    public record TraceContext(
        String traceId,
        String spanId,
        String parentSpanId,
        String operation,
        String key,
        long startTime,
        ConcurrentLinkedDeque<SpanContext> spans,
        AtomicReference<Span> micrometerSpan,
        boolean sampled
    ) {}
    
    public record SpanContext(
        String spanId,
        String parentSpanId,
        String layer,
        String operation,
        long startTime,
        AtomicLong duration,
        AtomicReference<Throwable> error,
        ConcurrentHashMap<String, String> tags
    ) {}
    
    public record CompletedTrace(
        String traceId,
        String operation,
        String key,
        long startTime,
        long duration,
        List<SpanContext> spans,
        boolean hasError
    ) {}
    
    public record SlowRequest(
        String traceId,
        String operation,
        String key,
        long timestamp,
        long duration,
        Map<String, Long> layerDurations,
        String bottleneck
    ) {}
    
    public record ErrorTrace(
        String traceId,
        String operation,
        String key,
        long timestamp,
        List<String> errors
    ) {}
    
    private static class LatencyStats {
        private final LongAdder count = new LongAdder();
        private final LongAdder sum = new LongAdder();
        private volatile long max = 0;
        private final ConcurrentSkipListMap<Long, LongAdder> histogram = new ConcurrentSkipListMap<>();
        
        void recordLatency(long latency) {
            count.increment();
            sum.add(latency);
            
            if (latency > max) max = latency;
            
            // 简单直方图
            long bucket = (latency / 10) * 10; // 10ms桶
            histogram.computeIfAbsent(bucket, k -> new LongAdder()).increment();
        }
        
        double getAvgLatency() {
            long c = count.sum();
            return c > 0 ? (double) sum.sum() / c : 0;
        }
        
        long getMaxLatency() {
            return max;
        }
        
        long getP50Latency() {
            return getPercentile(0.5);
        }
        
        long getP99Latency() {
            return getPercentile(0.99);
        }
        
        private long getPercentile(double percentile) {
            long total = count.sum();
            if (total == 0) return 0;
            
            long target = (long) (total * percentile);
            long cumulative = 0;
            
            for (Map.Entry<Long, LongAdder> entry : histogram.entrySet()) {
                cumulative += entry.getValue().sum();
                if (cumulative >= target) {
                    return entry.getKey();
                }
            }
            return max;
        }
    }
    
    public record LayerLatency(
        double avgMs,
        long p50Ms,
        long p99Ms,
        long maxMs
    ) {}
    
    public record TracingStats(
        boolean enabled,
        double sampleRate,
        int activeTraces,
        int completedTraces,
        int slowRequests,
        int errorTraces,
        Map<String, LayerLatency> layerLatencies
    ) {}
}
