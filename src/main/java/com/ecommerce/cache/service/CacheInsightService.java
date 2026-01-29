package com.ecommerce.cache.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * 缓存诊断与 Insight 服务
 * 提供：
 * 1. 实时缓存健康诊断
 * 2. 性能瓶颈分析
 * 3. 异常 Key 检测
 * 4. 优化建议生成
 */
@Service
public class CacheInsightService {
    
    private static final Logger log = LoggerFactory.getLogger(CacheInsightService.class);
    
    private final L1CacheService l1CacheService;
    private final L2RedisService l2RedisService;
    private final HotKeyDetectorService hotKeyDetectorService;
    private final BloomFilterService bloomFilterService;
    private final CacheResilienceService resilienceService;
    private final MultiLevelCacheService multiLevelCacheService;
    private final MeterRegistry meterRegistry;
    
    // 慢查询记录
    private final ConcurrentLinkedDeque<SlowQueryRecord> slowQueries = new ConcurrentLinkedDeque<>();
    private static final int MAX_SLOW_QUERIES = 1000;
    private static final long SLOW_QUERY_THRESHOLD_MS = 50;
    
    // 异常 Key 记录
    private final ConcurrentHashMap<String, AnomalyRecord> anomalyKeys = new ConcurrentHashMap<>();
    
    // 诊断历史
    private final ConcurrentLinkedDeque<DiagnosticReport> diagnosticHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_DIAGNOSTIC_HISTORY = 100;

    public CacheInsightService(L1CacheService l1CacheService,
                                L2RedisService l2RedisService,
                                HotKeyDetectorService hotKeyDetectorService,
                                BloomFilterService bloomFilterService,
                                CacheResilienceService resilienceService,
                                MultiLevelCacheService multiLevelCacheService,
                                MeterRegistry meterRegistry) {
        this.l1CacheService = l1CacheService;
        this.l2RedisService = l2RedisService;
        this.hotKeyDetectorService = hotKeyDetectorService;
        this.bloomFilterService = bloomFilterService;
        this.resilienceService = resilienceService;
        this.multiLevelCacheService = multiLevelCacheService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录慢查询
     */
    public void recordSlowQuery(String key, long durationMs, String cacheLevel) {
        if (durationMs >= SLOW_QUERY_THRESHOLD_MS) {
            SlowQueryRecord record = new SlowQueryRecord(
                key, durationMs, cacheLevel, System.currentTimeMillis());
            
            slowQueries.addFirst(record);
            while (slowQueries.size() > MAX_SLOW_QUERIES) {
                slowQueries.removeLast();
            }
            
            log.warn("Slow cache query detected: key={}, duration={}ms, level={}", 
                key, durationMs, cacheLevel);
        }
    }

    /**
     * 记录异常 Key
     */
    public void recordAnomaly(String key, AnomalyType type, String details) {
        AnomalyRecord record = anomalyKeys.compute(key, (k, existing) -> {
            if (existing == null) {
                return new AnomalyRecord(key, type, details, 1, System.currentTimeMillis());
            }
            return new AnomalyRecord(key, type, details, existing.count + 1, System.currentTimeMillis());
        });
        
        if (record.count() % 100 == 0) {
            log.warn("Anomaly key detected: key={}, type={}, count={}", key, type, record.count());
        }
    }

    /**
     * 执行全面诊断
     */
    public DiagnosticReport runDiagnostic() {
        log.info("Running cache diagnostic...");
        
        List<DiagnosticIssue> issues = new ArrayList<>();
        List<OptimizationSuggestion> suggestions = new ArrayList<>();
        
        // 1. 检查缓存命中率
        var cacheStats = multiLevelCacheService.getStats();
        if (cacheStats.totalHitRate() < 0.95) {
            issues.add(new DiagnosticIssue(
                IssueSeverity.WARNING,
                "LOW_HIT_RATE",
                String.format("缓存总命中率 %.2f%% 低于目标 95%%", cacheStats.totalHitRate() * 100)
            ));
            suggestions.add(new OptimizationSuggestion(
                "INCREASE_CACHE_SIZE",
                "建议增加 L1 缓存容量或延长 TTL",
                OptimizationPriority.HIGH
            ));
        }
        
        // 2. 检查热点 Key 状态
        var hotKeyStats = hotKeyDetectorService.getStats();
        if (hotKeyStats.hotKeyCount() > 100) {
            issues.add(new DiagnosticIssue(
                IssueSeverity.INFO,
                "HIGH_HOT_KEY_COUNT",
                String.format("当前热点 Key 数量 %d 较多", hotKeyStats.hotKeyCount())
            ));
        }
        
        // 3. 检查布隆过滤器填充率
        var bloomStats = bloomFilterService.getStats();
        if (bloomStats.fillRate() > 0.8) {
            issues.add(new DiagnosticIssue(
                IssueSeverity.WARNING,
                "BLOOM_FILTER_FULL",
                String.format("布隆过滤器填充率 %.2f%% 过高，误判率可能上升", bloomStats.fillRate() * 100)
            ));
            suggestions.add(new OptimizationSuggestion(
                "REBUILD_BLOOM_FILTER",
                "建议重建布隆过滤器或增加容量",
                OptimizationPriority.MEDIUM
            ));
        }
        
        // 4. 检查熔断器状态
        var resilienceStats = resilienceService.getStats();
        if (!"CLOSED".equals(resilienceStats.redisCircuitState())) {
            issues.add(new DiagnosticIssue(
                IssueSeverity.CRITICAL,
                "CIRCUIT_BREAKER_OPEN",
                String.format("Redis 熔断器状态: %s", resilienceStats.redisCircuitState())
            ));
            suggestions.add(new OptimizationSuggestion(
                "CHECK_REDIS_HEALTH",
                "Redis 可能存在问题，请检查连接和性能",
                OptimizationPriority.CRITICAL
            ));
        }
        
        // 5. 检查慢查询
        long recentSlowQueries = slowQueries.stream()
            .filter(q -> System.currentTimeMillis() - q.timestamp() < 60000)
            .count();
        if (recentSlowQueries > 10) {
            issues.add(new DiagnosticIssue(
                IssueSeverity.WARNING,
                "HIGH_SLOW_QUERY_COUNT",
                String.format("最近 1 分钟慢查询 %d 次", recentSlowQueries)
            ));
        }
        
        // 6. 检查异常 Key
        long highAnomalyKeys = anomalyKeys.values().stream()
            .filter(a -> a.count() > 100)
            .count();
        if (highAnomalyKeys > 0) {
            issues.add(new DiagnosticIssue(
                IssueSeverity.WARNING,
                "ANOMALY_KEYS_DETECTED",
                String.format("检测到 %d 个高频异常 Key", highAnomalyKeys)
            ));
        }
        
        // 生成报告
        HealthStatus healthStatus = determineHealthStatus(issues);
        DiagnosticReport report = new DiagnosticReport(
            LocalDateTime.now(),
            healthStatus,
            issues,
            suggestions,
            new DiagnosticMetrics(
                cacheStats.totalHitRate(),
                cacheStats.l1HitRate(),
                hotKeyStats.hotKeyCount(),
                recentSlowQueries,
                highAnomalyKeys,
                resilienceStats.redisCircuitState()
            )
        );
        
        // 保存诊断历史
        diagnosticHistory.addFirst(report);
        while (diagnosticHistory.size() > MAX_DIAGNOSTIC_HISTORY) {
            diagnosticHistory.removeLast();
        }
        
        log.info("Diagnostic completed: status={}, issues={}, suggestions={}", 
            healthStatus, issues.size(), suggestions.size());
        
        return report;
    }

    /**
     * 定时执行诊断（每 5 分钟）
     */
    @Scheduled(fixedRate = 300000)
    public void scheduledDiagnostic() {
        try {
            runDiagnostic();
        } catch (Exception e) {
            log.error("Scheduled diagnostic failed", e);
        }
    }

    /**
     * 获取慢查询列表
     */
    public List<SlowQueryRecord> getSlowQueries(int limit) {
        return slowQueries.stream().limit(limit).toList();
    }

    /**
     * 获取异常 Key 列表
     */
    public List<AnomalyRecord> getAnomalyKeys(int limit) {
        return anomalyKeys.values().stream()
            .sorted(Comparator.comparingInt(AnomalyRecord::count).reversed())
            .limit(limit)
            .toList();
    }

    /**
     * 获取诊断历史
     */
    public List<DiagnosticReport> getDiagnosticHistory(int limit) {
        return diagnosticHistory.stream().limit(limit).toList();
    }

    /**
     * 清理过期数据
     */
    @Scheduled(fixedRate = 3600000) // 每小时
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - 24 * 3600 * 1000; // 24小时前
        
        // 清理慢查询
        slowQueries.removeIf(q -> q.timestamp() < cutoff);
        
        // 清理异常 Key
        anomalyKeys.entrySet().removeIf(e -> e.getValue().lastSeen() < cutoff);
        
        log.debug("Insight data cleanup completed");
    }

    private HealthStatus determineHealthStatus(List<DiagnosticIssue> issues) {
        boolean hasCritical = issues.stream().anyMatch(i -> i.severity() == IssueSeverity.CRITICAL);
        boolean hasWarning = issues.stream().anyMatch(i -> i.severity() == IssueSeverity.WARNING);
        
        if (hasCritical) return HealthStatus.CRITICAL;
        if (hasWarning) return HealthStatus.DEGRADED;
        return HealthStatus.HEALTHY;
    }

    // 数据记录类
    public record SlowQueryRecord(String key, long durationMs, String cacheLevel, long timestamp) {}
    
    public record AnomalyRecord(String key, AnomalyType type, String details, int count, long lastSeen) {}
    
    public record DiagnosticIssue(IssueSeverity severity, String code, String message) {}
    
    public record OptimizationSuggestion(String code, String description, OptimizationPriority priority) {}
    
    public record DiagnosticMetrics(
        double totalHitRate,
        double l1HitRate,
        int hotKeyCount,
        long recentSlowQueries,
        long anomalyKeyCount,
        String circuitBreakerState
    ) {}
    
    public record DiagnosticReport(
        LocalDateTime timestamp,
        HealthStatus status,
        List<DiagnosticIssue> issues,
        List<OptimizationSuggestion> suggestions,
        DiagnosticMetrics metrics
    ) {}

    public enum AnomalyType {
        FREQUENT_MISS,      // 频繁未命中
        LARGE_VALUE,        // 大 Value
        TTL_ANOMALY,        // TTL 异常
        ACCESS_SPIKE        // 访问尖峰
    }

    public enum IssueSeverity {
        INFO, WARNING, CRITICAL
    }

    public enum OptimizationPriority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public enum HealthStatus {
        HEALTHY, DEGRADED, CRITICAL
    }
}
