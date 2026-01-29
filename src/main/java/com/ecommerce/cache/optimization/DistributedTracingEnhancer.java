package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 分布式追踪增强器
 * 
 * 基于 Micrometer Tracing 实现全链路追踪：
 * 1. 自动 Span 创建与传播
 * 2. 缓存操作追踪
 * 3. 跨服务追踪上下文
 * 4. 性能瓶颈定位
 * 5. 异常追踪与告警
 * 6. 采样策略控制
 * 
 * 兼容 OpenTelemetry、Zipkin、Jaeger
 * 
 * @author optimization-team
 * @version 2.0
 */
@Service
public class DistributedTracingEnhancer {
    
    private static final Logger log = LoggerFactory.getLogger(DistributedTracingEnhancer.class);
    
    // ==================== MDC 键 ====================
    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";
    private static final String PARENT_SPAN_ID_KEY = "parentSpanId";
    private static final String OPERATION_KEY = "operation";
    
    // ==================== 配置 ====================
    @Value("${optimization.tracing.enabled:true}")
    private boolean tracingEnabled;
    
    @Value("${optimization.tracing.sample-rate:0.1}")
    private double sampleRate;
    
    @Value("${optimization.tracing.slow-threshold-ms:100}")
    private long slowThresholdMs;
    
    // ==================== 追踪组件 ====================
    private final Tracer tracer;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    
    // ==================== Span 存储 ====================
    private final ConcurrentHashMap<String, SpanContext> activeSpans;
    private final ConcurrentLinkedDeque<TraceRecord> recentTraces;
    private static final int MAX_RECENT_TRACES = 10000;
    
    // ==================== 采样控制 ====================
    private final AdaptiveSampler sampler;
    
    // ==================== 性能指标 ====================
    private final LongAdder totalSpans;
    private final LongAdder sampledSpans;
    private final LongAdder slowSpans;
    private final LongAdder errorSpans;
    private final AtomicLong idGenerator;
    
    public DistributedTracingEnhancer(Tracer tracer,
                                       ObservationRegistry observationRegistry,
                                       MeterRegistry meterRegistry) {
        this.tracer = tracer;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        
        this.activeSpans = new ConcurrentHashMap<>();
        this.recentTraces = new ConcurrentLinkedDeque<>();
        this.sampler = new AdaptiveSampler(sampleRate);
        
        this.totalSpans = new LongAdder();
        this.sampledSpans = new LongAdder();
        this.slowSpans = new LongAdder();
        this.errorSpans = new LongAdder();
        this.idGenerator = new AtomicLong(0);
    }
    
    @PostConstruct
    public void initialize() {
        log.info("Distributed Tracing Enhancer initialized");
        log.info("Tracing enabled: {}, Sample rate: {}", tracingEnabled, sampleRate);
    }
    
    // ==================== 核心追踪 API ====================
    
    /**
     * 创建新的追踪 Span
     */
    public TracingSpan startSpan(String operationName) {
        return startSpan(operationName, Collections.emptyMap());
    }
    
    /**
     * 创建带标签的追踪 Span
     */
    public TracingSpan startSpan(String operationName, Map<String, String> tags) {
        if (!tracingEnabled) {
            return TracingSpan.NOOP;
        }
        
        totalSpans.increment();
        
        // 采样决策
        if (!sampler.shouldSample()) {
            return TracingSpan.NOOP;
        }
        
        sampledSpans.increment();
        
        // 生成 IDs
        String traceId = getCurrentTraceId();
        String spanId = generateSpanId();
        String parentSpanId = getCurrentSpanId();
        
        // 创建 Span 上下文
        SpanContext context = new SpanContext(
            traceId,
            spanId,
            parentSpanId,
            operationName,
            Instant.now(),
            new ConcurrentHashMap<>(tags)
        );
        
        activeSpans.put(spanId, context);
        
        // 设置 MDC
        MDC.put(TRACE_ID_KEY, traceId);
        MDC.put(SPAN_ID_KEY, spanId);
        MDC.put(OPERATION_KEY, operationName);
        if (parentSpanId != null) {
            MDC.put(PARENT_SPAN_ID_KEY, parentSpanId);
        }
        
        // 创建 Micrometer Span
        Span micrometerSpan = tracer.nextSpan().name(operationName);
        tags.forEach(micrometerSpan::tag);
        micrometerSpan.start();
        
        return new TracingSpanImpl(context, micrometerSpan, this);
    }
    
    /**
     * 在追踪上下文中执行
     */
    public <T> T executeWithTracing(String operationName, Supplier<T> operation) {
        return executeWithTracing(operationName, Collections.emptyMap(), operation);
    }
    
    /**
     * 在追踪上下文中执行（带标签）
     */
    public <T> T executeWithTracing(String operationName, Map<String, String> tags, Supplier<T> operation) {
        TracingSpan span = startSpan(operationName, tags);
        try {
            T result = operation.get();
            span.setStatus(SpanStatus.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(SpanStatus.ERROR);
            span.recordException(e);
            throw e;
        } finally {
            span.finish();
        }
    }
    
    /**
     * 异步追踪
     */
    public <T> CompletableFuture<T> executeAsyncWithTracing(String operationName, 
                                                             Supplier<CompletableFuture<T>> operation) {
        TracingSpan span = startSpan(operationName);
        String traceId = getCurrentTraceId();
        String spanId = getCurrentSpanId();
        
        return operation.get()
            .whenComplete((result, error) -> {
                // 恢复追踪上下文
                MDC.put(TRACE_ID_KEY, traceId);
                MDC.put(SPAN_ID_KEY, spanId);
                
                if (error != null) {
                    span.setStatus(SpanStatus.ERROR);
                    span.recordException(error);
                } else {
                    span.setStatus(SpanStatus.OK);
                }
                span.finish();
            });
    }
    
    // ==================== 缓存操作追踪 ====================
    
    /**
     * 追踪缓存读取
     */
    public <T> T traceCacheGet(String cacheLayer, String key, Supplier<T> getter) {
        Map<String, String> tags = Map.of(
            "cache.layer", cacheLayer,
            "cache.key", truncateKey(key),
            "cache.operation", "GET"
        );
        
        return executeWithTracing("cache." + cacheLayer + ".get", tags, () -> {
            T result = getter.get();
            if (result != null) {
                recordCacheHit(cacheLayer);
            } else {
                recordCacheMiss(cacheLayer);
            }
            return result;
        });
    }
    
    /**
     * 追踪缓存写入
     */
    public void traceCachePut(String cacheLayer, String key, Runnable putter) {
        Map<String, String> tags = Map.of(
            "cache.layer", cacheLayer,
            "cache.key", truncateKey(key),
            "cache.operation", "PUT"
        );
        
        executeWithTracing("cache." + cacheLayer + ".put", tags, () -> {
            putter.run();
            return null;
        });
    }
    
    /**
     * 追踪缓存删除
     */
    public void traceCacheInvalidate(String cacheLayer, String key, Runnable invalidator) {
        Map<String, String> tags = Map.of(
            "cache.layer", cacheLayer,
            "cache.key", truncateKey(key),
            "cache.operation", "INVALIDATE"
        );
        
        executeWithTracing("cache." + cacheLayer + ".invalidate", tags, () -> {
            invalidator.run();
            return null;
        });
    }
    
    // ==================== 跨服务追踪 ====================
    
    /**
     * 提取追踪上下文（用于跨服务传播）
     */
    public Map<String, String> extractTracingContext() {
        Map<String, String> context = new HashMap<>();
        
        String traceId = MDC.get(TRACE_ID_KEY);
        String spanId = MDC.get(SPAN_ID_KEY);
        
        if (traceId != null) {
            context.put("X-Trace-Id", traceId);
        }
        if (spanId != null) {
            context.put("X-Span-Id", spanId);
        }
        
        return context;
    }
    
    /**
     * 注入追踪上下文（从上游服务接收）
     */
    public void injectTracingContext(Map<String, String> headers) {
        String traceId = headers.get("X-Trace-Id");
        String parentSpanId = headers.get("X-Span-Id");
        
        if (traceId != null) {
            MDC.put(TRACE_ID_KEY, traceId);
        }
        if (parentSpanId != null) {
            MDC.put(PARENT_SPAN_ID_KEY, parentSpanId);
        }
    }
    
    // ==================== 内部方法 ====================
    
    void finishSpan(SpanContext context, SpanStatus status, Throwable error) {
        context.endTime = Instant.now();
        context.status = status;
        context.error = error;
        
        activeSpans.remove(context.spanId);
        
        // 计算耗时
        long durationMs = java.time.Duration.between(context.startTime, context.endTime).toMillis();
        
        // 检查慢 Span
        if (durationMs > slowThresholdMs) {
            slowSpans.increment();
            log.warn("Slow span detected: operation={}, duration={}ms, traceId={}", 
                context.operationName, durationMs, context.traceId);
        }
        
        // 检查错误
        if (status == SpanStatus.ERROR) {
            errorSpans.increment();
        }
        
        // 记录追踪
        recordTrace(new TraceRecord(
            context.traceId,
            context.spanId,
            context.parentSpanId,
            context.operationName,
            context.startTime,
            context.endTime,
            durationMs,
            status,
            error != null ? error.getMessage() : null,
            new HashMap<>(context.tags)
        ));
        
        // 清理 MDC
        MDC.remove(SPAN_ID_KEY);
        MDC.remove(OPERATION_KEY);
    }
    
    private void recordTrace(TraceRecord record) {
        recentTraces.addFirst(record);
        while (recentTraces.size() > MAX_RECENT_TRACES) {
            recentTraces.pollLast();
        }
    }
    
    private void recordCacheHit(String layer) {
        meterRegistry.counter("cache.hit", "layer", layer).increment();
    }
    
    private void recordCacheMiss(String layer) {
        meterRegistry.counter("cache.miss", "layer", layer).increment();
    }
    
    private String getCurrentTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        return traceId != null ? traceId : generateTraceId();
    }
    
    private String getCurrentSpanId() {
        return MDC.get(SPAN_ID_KEY);
    }
    
    private String generateTraceId() {
        return String.format("%016x%016x", 
            System.currentTimeMillis(), 
            idGenerator.incrementAndGet());
    }
    
    private String generateSpanId() {
        return String.format("%016x", idGenerator.incrementAndGet());
    }
    
    private String truncateKey(String key) {
        return key.length() > 50 ? key.substring(0, 47) + "..." : key;
    }
    
    // ==================== 管理 API ====================
    
    public void setSampleRate(double rate) {
        this.sampler.setRate(rate);
    }
    
    public List<TraceRecord> getRecentTraces(int limit) {
        List<TraceRecord> result = new ArrayList<>();
        Iterator<TraceRecord> it = recentTraces.iterator();
        while (it.hasNext() && result.size() < limit) {
            result.add(it.next());
        }
        return result;
    }
    
    public List<TraceRecord> getSlowTraces(int limit) {
        return recentTraces.stream()
            .filter(t -> t.durationMs > slowThresholdMs)
            .limit(limit)
            .toList();
    }
    
    public List<TraceRecord> getErrorTraces(int limit) {
        return recentTraces.stream()
            .filter(t -> t.status == SpanStatus.ERROR)
            .limit(limit)
            .toList();
    }
    
    public TracingStats getStats() {
        return new TracingStats(
            tracingEnabled,
            sampleRate,
            totalSpans.sum(),
            sampledSpans.sum(),
            slowSpans.sum(),
            errorSpans.sum(),
            activeSpans.size(),
            recentTraces.size()
        );
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 自适应采样器
     */
    private static class AdaptiveSampler {
        private volatile double rate;
        private final ThreadLocalRandom random = ThreadLocalRandom.current();
        
        AdaptiveSampler(double rate) {
            this.rate = rate;
        }
        
        boolean shouldSample() {
            return random.nextDouble() < rate;
        }
        
        void setRate(double rate) {
            this.rate = Math.max(0, Math.min(1, rate));
        }
    }
    
    /**
     * Span 上下文
     */
    private static class SpanContext {
        final String traceId;
        final String spanId;
        final String parentSpanId;
        final String operationName;
        final Instant startTime;
        final Map<String, String> tags;
        
        volatile Instant endTime;
        volatile SpanStatus status;
        volatile Throwable error;
        
        SpanContext(String traceId, String spanId, String parentSpanId,
                    String operationName, Instant startTime, Map<String, String> tags) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.operationName = operationName;
            this.startTime = startTime;
            this.tags = tags;
            this.status = SpanStatus.UNKNOWN;
        }
    }
    
    /**
     * Span 状态
     */
    public enum SpanStatus {
        UNKNOWN, OK, ERROR
    }
    
    /**
     * 追踪 Span 接口
     */
    public interface TracingSpan {
        TracingSpan NOOP = new NoopTracingSpan();
        
        void setTag(String key, String value);
        void setStatus(SpanStatus status);
        void recordException(Throwable error);
        void finish();
    }
    
    /**
     * 空操作 Span
     */
    private static class NoopTracingSpan implements TracingSpan {
        @Override public void setTag(String key, String value) {}
        @Override public void setStatus(SpanStatus status) {}
        @Override public void recordException(Throwable error) {}
        @Override public void finish() {}
    }
    
    /**
     * 追踪 Span 实现
     */
    private static class TracingSpanImpl implements TracingSpan {
        private final SpanContext context;
        private final Span micrometerSpan;
        private final DistributedTracingEnhancer enhancer;
        
        TracingSpanImpl(SpanContext context, Span micrometerSpan, 
                        DistributedTracingEnhancer enhancer) {
            this.context = context;
            this.micrometerSpan = micrometerSpan;
            this.enhancer = enhancer;
        }
        
        @Override
        public void setTag(String key, String value) {
            context.tags.put(key, value);
            micrometerSpan.tag(key, value);
        }
        
        @Override
        public void setStatus(SpanStatus status) {
            context.status = status;
        }
        
        @Override
        public void recordException(Throwable error) {
            context.error = error;
            micrometerSpan.error(error);
        }
        
        @Override
        public void finish() {
            micrometerSpan.end();
            enhancer.finishSpan(context, context.status, context.error);
        }
    }
    
    /**
     * 追踪记录
     */
    public record TraceRecord(
        String traceId,
        String spanId,
        String parentSpanId,
        String operationName,
        Instant startTime,
        Instant endTime,
        long durationMs,
        SpanStatus status,
        String errorMessage,
        Map<String, String> tags
    ) {}
    
    /**
     * 追踪统计
     */
    public record TracingStats(
        boolean enabled,
        double sampleRate,
        long totalSpans,
        long sampledSpans,
        long slowSpans,
        long errorSpans,
        int activeSpans,
        int recentTraces
    ) {
        public double getActualSampleRate() {
            return totalSpans > 0 ? (double) sampledSpans / totalSpans : 0;
        }
        
        public double getErrorRate() {
            return sampledSpans > 0 ? (double) errorSpans / sampledSpans : 0;
        }
    }
}
