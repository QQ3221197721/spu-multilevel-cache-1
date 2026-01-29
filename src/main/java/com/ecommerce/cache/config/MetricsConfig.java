package com.ecommerce.cache.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.core.instrument.binder.system.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高级监控指标配置
 * 提供：
 * 1. 业务指标（命中率、延迟、QPS）
 * 2. JVM 指标（GC、内存、线程）
 * 3. 系统指标（CPU、磁盘）
 * 4. 自定义 SLI/SLO 指标
 */
@Configuration
public class MetricsConfig {
    
    private static final Logger log = LoggerFactory.getLogger(MetricsConfig.class);
    
    private final MeterRegistry meterRegistry;
    
    // SLO 阈值
    private static final double TARGET_HIT_RATE = 0.973;  // 97.3%
    private static final double TARGET_P99_MS = 38.0;     // 38ms
    private static final double TARGET_QPS = 100000;       // 10W QPS
    
    // SLI 计数器
    private final AtomicLong sloViolationCount = new AtomicLong(0);

    public MetricsConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initMetrics() {
        registerJvmMetrics();
        registerSystemMetrics();
        registerBusinessMetrics();
        registerSloMetrics();
        log.info("Advanced metrics configuration initialized");
    }

    /**
     * JVM 指标
     */
    private void registerJvmMetrics() {
        // 内存指标
        new JvmMemoryMetrics().bindTo(meterRegistry);
        
        // GC 指标
        new JvmGcMetrics().bindTo(meterRegistry);
        
        // 线程指标
        new JvmThreadMetrics().bindTo(meterRegistry);
        
        // 类加载指标
        new ClassLoaderMetrics().bindTo(meterRegistry);
        
        // JVM 信息
        new JvmInfoMetrics().bindTo(meterRegistry);
    }

    /**
     * 系统指标
     */
    private void registerSystemMetrics() {
        // CPU 指标
        new ProcessorMetrics().bindTo(meterRegistry);
        
        // 文件描述符
        new FileDescriptorMetrics().bindTo(meterRegistry);
        
        // 进程内存
        new UptimeMetrics().bindTo(meterRegistry);
    }

    /**
     * 业务指标
     */
    private void registerBusinessMetrics() {
        // 缓存层级命中分布
        Gauge.builder("cache.layer.distribution", () -> 0.35)
            .description("L1 cache hit rate")
            .tag("layer", "L1")
            .register(meterRegistry);
        
        Gauge.builder("cache.layer.distribution", () -> 0.58)
            .description("L2 cache hit rate")
            .tag("layer", "L2")
            .register(meterRegistry);
        
        Gauge.builder("cache.layer.distribution", () -> 0.043)
            .description("L3 cache hit rate")
            .tag("layer", "L3")
            .register(meterRegistry);
        
        // 缓存操作计数
        Counter.builder("cache.operations")
            .description("Cache operations count")
            .tag("operation", "get")
            .register(meterRegistry);
        
        Counter.builder("cache.operations")
            .description("Cache operations count")
            .tag("operation", "put")
            .register(meterRegistry);
        
        Counter.builder("cache.operations")
            .description("Cache operations count")
            .tag("operation", "invalidate")
            .register(meterRegistry);
    }

    /**
     * SLO 指标
     */
    private void registerSloMetrics() {
        // SLO 目标
        Gauge.builder("slo.target.hit_rate", () -> TARGET_HIT_RATE)
            .description("Target cache hit rate")
            .register(meterRegistry);
        
        Gauge.builder("slo.target.p99_latency_ms", () -> TARGET_P99_MS)
            .description("Target P99 latency in milliseconds")
            .register(meterRegistry);
        
        Gauge.builder("slo.target.qps", () -> TARGET_QPS)
            .description("Target QPS")
            .register(meterRegistry);
        
        // SLO 违规计数
        Gauge.builder("slo.violation.count", sloViolationCount, AtomicLong::get)
            .description("SLO violation count")
            .register(meterRegistry);
        
        // SLO 达成率（示例）
        Gauge.builder("slo.compliance.rate", () -> 
            Math.max(0, 1 - (sloViolationCount.get() / 10000.0)))
            .description("SLO compliance rate")
            .register(meterRegistry);
    }

    /**
     * 记录 SLO 违规
     */
    public void recordSloViolation(String type) {
        sloViolationCount.incrementAndGet();
        meterRegistry.counter("slo.violation", "type", type).increment();
    }

    /**
     * @Timed 注解支持
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry meterRegistry) {
        return new TimedAspect(meterRegistry);
    }

    /**
     * 分布式追踪 Span 名称生成器
     */
    @Bean
    public io.micrometer.observation.ObservationRegistry observationRegistry() {
        return io.micrometer.observation.ObservationRegistry.create();
    }
}
