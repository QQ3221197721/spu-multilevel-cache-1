package com.ecommerce.cache.monitor;

import com.ecommerce.cache.service.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存监控指标收集器
 * 收集多级缓存的命中率、延迟、QPS 等指标
 * 暴露给 Prometheus 进行采集
 */
@Component
public class CacheMetricsCollector {
    
    private static final Logger log = LoggerFactory.getLogger(CacheMetricsCollector.class);
    
    private final MeterRegistry meterRegistry;
    private final L1CacheService l1CacheService;
    private final HotKeyDetectorService hotKeyDetectorService;
    private final BloomFilterService bloomFilterService;
    private final CacheAvalancheProtector avalancheProtector;
    
    // 计数器
    private final AtomicLong l1Hits = new AtomicLong(0);
    private final AtomicLong l1Misses = new AtomicLong(0);
    private final AtomicLong l2Hits = new AtomicLong(0);
    private final AtomicLong l2Misses = new AtomicLong(0);
    private final AtomicLong l3Hits = new AtomicLong(0);
    private final AtomicLong l3Misses = new AtomicLong(0);
    private final AtomicLong dbQueries = new AtomicLong(0);

    public CacheMetricsCollector(MeterRegistry meterRegistry,
                                  L1CacheService l1CacheService,
                                  HotKeyDetectorService hotKeyDetectorService,
                                  BloomFilterService bloomFilterService,
                                  CacheAvalancheProtector avalancheProtector) {
        this.meterRegistry = meterRegistry;
        this.l1CacheService = l1CacheService;
        this.hotKeyDetectorService = hotKeyDetectorService;
        this.bloomFilterService = bloomFilterService;
        this.avalancheProtector = avalancheProtector;
        
        registerMetrics();
    }

    private void registerMetrics() {
        // L1 缓存命中率
        meterRegistry.gauge("cache.l1.hit_rate", l1CacheService, L1CacheService::getHitRate);
        meterRegistry.gauge("cache.l1.size", l1CacheService, L1CacheService::size);
        
        // 热点 Key 统计
        meterRegistry.gauge("cache.hotkey.count", hotKeyDetectorService, HotKeyDetectorService::getHotKeyCount);
        
        // 布隆过滤器统计
        meterRegistry.gauge("cache.bloom.count", bloomFilterService, BloomFilterService::count);
        
        // 缓存命中计数器
        meterRegistry.counter("cache.hits", Tags.of("level", "l1"));
        meterRegistry.counter("cache.hits", Tags.of("level", "l2"));
        meterRegistry.counter("cache.hits", Tags.of("level", "l3"));
        meterRegistry.counter("cache.misses", Tags.of("level", "l1"));
        meterRegistry.counter("cache.misses", Tags.of("level", "l2"));
        meterRegistry.counter("cache.misses", Tags.of("level", "l3"));
        meterRegistry.counter("cache.db_queries");
        
        // 缓存操作延迟
        Timer.builder("cache.latency")
            .tag("level", "l1")
            .tag("operation", "get")
            .register(meterRegistry);
        
        Timer.builder("cache.latency")
            .tag("level", "l2")
            .tag("operation", "get")
            .register(meterRegistry);
        
        Timer.builder("cache.latency")
            .tag("level", "l3")
            .tag("operation", "get")
            .register(meterRegistry);
    }

    /**
     * 记录 L1 命中
     */
    public void recordL1Hit() {
        l1Hits.incrementAndGet();
        meterRegistry.counter("cache.hits", Tags.of("level", "l1")).increment();
    }

    /**
     * 记录 L1 未命中
     */
    public void recordL1Miss() {
        l1Misses.incrementAndGet();
        meterRegistry.counter("cache.misses", Tags.of("level", "l1")).increment();
    }

    /**
     * 记录 L2 命中
     */
    public void recordL2Hit() {
        l2Hits.incrementAndGet();
        meterRegistry.counter("cache.hits", Tags.of("level", "l2")).increment();
    }

    /**
     * 记录 L2 未命中
     */
    public void recordL2Miss() {
        l2Misses.incrementAndGet();
        meterRegistry.counter("cache.misses", Tags.of("level", "l2")).increment();
    }

    /**
     * 记录 L3 命中
     */
    public void recordL3Hit() {
        l3Hits.incrementAndGet();
        meterRegistry.counter("cache.hits", Tags.of("level", "l3")).increment();
    }

    /**
     * 记录 L3 未命中
     */
    public void recordL3Miss() {
        l3Misses.incrementAndGet();
        meterRegistry.counter("cache.misses", Tags.of("level", "l3")).increment();
    }

    /**
     * 记录 DB 查询
     */
    public void recordDbQuery() {
        dbQueries.incrementAndGet();
        meterRegistry.counter("cache.db_queries").increment();
    }

    /**
     * 记录缓存操作延迟
     */
    public void recordLatency(String level, String operation, long durationNanos) {
        Timer timer = meterRegistry.timer("cache.latency", 
            Tags.of("level", level, "operation", operation));
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 定时打印缓存统计（每分钟）
     */
    @Scheduled(fixedRate = 60000)
    public void logCacheStats() {
        double l1HitRate = l1CacheService.getHitRate() * 100;
        long l1Size = l1CacheService.size();
        int hotKeyCount = hotKeyDetectorService.getHotKeyCount();
        long bloomCount = bloomFilterService.count();
        
        // 计算总命中率
        long totalHits = l1Hits.get() + l2Hits.get() + l3Hits.get();
        long totalRequests = totalHits + dbQueries.get();
        double totalHitRate = totalRequests > 0 ? (double) totalHits / totalRequests * 100 : 0;
        
        log.info("Cache Stats: L1 hit rate={:.2f}%, L1 size={}, hot keys={}, bloom count={}, total hit rate={:.2f}%",
            l1HitRate, l1Size, hotKeyCount, bloomCount, totalHitRate);
        
        // 雪崩风险检查
        var riskStats = avalancheProtector.getRiskStats();
        if (!"LOW".equals(riskStats.riskLevel())) {
            log.warn("Avalanche risk: level={}, expiring1min={}, expiring5min={}", 
                riskStats.riskLevel(), riskStats.expiring1Min(), riskStats.expiring5Min());
        }
    }

    /**
     * 获取缓存统计摘要
     */
    public CacheStatsSummary getStatsSummary() {
        long totalHits = l1Hits.get() + l2Hits.get() + l3Hits.get();
        long totalRequests = totalHits + dbQueries.get();
        
        return new CacheStatsSummary(
            l1CacheService.getHitRate(),
            l1Hits.get(),
            l1Misses.get(),
            l2Hits.get(),
            l2Misses.get(),
            l3Hits.get(),
            l3Misses.get(),
            dbQueries.get(),
            totalRequests > 0 ? (double) totalHits / totalRequests : 0,
            l1CacheService.size(),
            hotKeyDetectorService.getHotKeyCount(),
            bloomFilterService.count()
        );
    }

    public record CacheStatsSummary(
        double l1HitRate,
        long l1Hits,
        long l1Misses,
        long l2Hits,
        long l2Misses,
        long l3Hits,
        long l3Misses,
        long dbQueries,
        double totalHitRate,
        long l1Size,
        int hotKeyCount,
        long bloomFilterCount
    ) {}
}
