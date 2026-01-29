package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * 自适应TTL管理器 - 根据访问模式动态调整缓存过期时间
 * 
 * 核心策略:
 * 1. 访问频率感知 - 高频访问数据延长TTL
 * 2. 时间段自适应 - 根据访问高峰期动态调整
 * 3. 数据新鲜度权衡 - 平衡缓存命中率与数据实时性
 * 4. 热点衰减补偿 - 热点数据降温后逐步缩短TTL
 * 5. 业务优先级 - 不同业务类型采用不同策略
 * 
 * 目标: 动态TTL使缓存效率提升20%, 减少无效缓存占用30%
 */
@Service
public class AdaptiveTTLManager {
    
    private static final Logger log = LoggerFactory.getLogger(AdaptiveTTLManager.class);
    
    // ========== 配置参数 ==========
    @Value("${optimization.ttl.enabled:true}")
    private boolean enabled;
    
    @Value("${optimization.ttl.min-seconds:60}")
    private long minTtlSeconds;
    
    @Value("${optimization.ttl.max-seconds:7200}")
    private long maxTtlSeconds;
    
    @Value("${optimization.ttl.base-seconds:600}")
    private long baseTtlSeconds;
    
    @Value("${optimization.ttl.access-boost-factor:1.5}")
    private double accessBoostFactor;
    
    @Value("${optimization.ttl.decay-factor:0.9}")
    private double decayFactor;
    
    @Value("${optimization.ttl.peak-hour-boost:1.3}")
    private double peakHourBoost;
    
    // ========== 数据结构 ==========
    
    // 访问统计 (key -> 统计信息)
    private final ConcurrentHashMap<String, AccessStats> accessStatsMap = new ConcurrentHashMap<>();
    
    // 动态TTL缓存 (key -> 计算的TTL)
    private final ConcurrentHashMap<String, Long> dynamicTtlCache = new ConcurrentHashMap<>();
    
    // 业务类型TTL策略
    private final ConcurrentHashMap<String, TTLStrategy> businessStrategies = new ConcurrentHashMap<>();
    
    // 小时级访问分布
    private final ConcurrentHashMap<Integer, LongAdder> hourlyAccessDistribution = new ConcurrentHashMap<>();
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Counter ttlExtensionCounter;
    private Counter ttlReductionCounter;
    
    public AdaptiveTTLManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 初始化24小时访问分布
        for (int i = 0; i < 24; i++) {
            hourlyAccessDistribution.put(i, new LongAdder());
        }
        
        // 初始化默认业务策略
        initDefaultStrategies();
    }
    
    private void initDefaultStrategies() {
        // SPU详情 - 允许较长缓存
        businessStrategies.put("spu:detail", new TTLStrategy(
            "spu_detail", 600, 3600, 1.2, 0.95, true
        ));
        
        // 价格信息 - 需要较高实时性
        businessStrategies.put("spu:price", new TTLStrategy(
            "spu_price", 30, 300, 1.1, 0.8, true
        ));
        
        // 库存信息 - 高实时性要求
        businessStrategies.put("spu:stock", new TTLStrategy(
            "spu_stock", 10, 60, 1.0, 0.7, false
        ));
        
        // 商品列表 - 中等缓存
        businessStrategies.put("spu:list", new TTLStrategy(
            "spu_list", 300, 1800, 1.3, 0.9, true
        ));
        
        // 用户数据 - 个性化，较短TTL
        businessStrategies.put("user:", new TTLStrategy(
            "user_data", 120, 600, 1.1, 0.85, true
        ));
    }
    
    @PostConstruct
    public void init() {
        ttlExtensionCounter = Counter.builder("cache.ttl.extensions").register(meterRegistry);
        ttlReductionCounter = Counter.builder("cache.ttl.reductions").register(meterRegistry);
        
        Gauge.builder("cache.ttl.tracked.keys", accessStatsMap, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("cache.ttl.strategies.count", businessStrategies, ConcurrentHashMap::size)
            .register(meterRegistry);
        
        log.info("AdaptiveTTLManager initialized: base={}s, min={}s, max={}s",
            baseTtlSeconds, minTtlSeconds, maxTtlSeconds);
    }
    
    /**
     * 计算自适应TTL - 核心方法
     */
    public long calculateAdaptiveTTL(String key) {
        if (!enabled) return baseTtlSeconds;
        
        // 检查缓存
        Long cached = dynamicTtlCache.get(key);
        if (cached != null) return cached;
        
        // 获取业务策略
        TTLStrategy strategy = getStrategyForKey(key);
        
        // 获取访问统计
        AccessStats stats = accessStatsMap.get(key);
        
        // 计算基础TTL
        long ttl = strategy != null ? strategy.baseTtl : baseTtlSeconds;
        
        // 1. 访问频率调整
        if (stats != null) {
            double accessFactor = calculateAccessFactor(stats);
            ttl = (long) (ttl * accessFactor);
        }
        
        // 2. 时间段调整
        ttl = applyTimeOfDayFactor(ttl);
        
        // 3. 热点补偿
        if (stats != null && stats.isHot()) {
            ttl = (long) (ttl * 1.5);
        }
        
        // 4. 应用策略限制
        long minTtl = strategy != null ? strategy.minTtl : minTtlSeconds;
        long maxTtl = strategy != null ? strategy.maxTtl : maxTtlSeconds;
        ttl = Math.max(minTtl, Math.min(maxTtl, ttl));
        
        // 5. 添加随机抖动防止缓存雪崩
        ttl = addJitter(ttl);
        
        // 缓存计算结果
        dynamicTtlCache.put(key, ttl);
        
        return ttl;
    }
    
    /**
     * 记录访问事件
     */
    public void recordAccess(String key) {
        if (!enabled) return;
        
        long now = System.currentTimeMillis();
        int hour = Instant.now().atZone(java.time.ZoneId.systemDefault()).getHour();
        
        // 更新小时访问分布
        hourlyAccessDistribution.get(hour).increment();
        
        // 更新key访问统计
        accessStatsMap.compute(key, (k, stats) -> {
            if (stats == null) {
                return new AccessStats(now);
            }
            stats.recordAccess(now);
            return stats;
        });
        
        // 清除TTL缓存以便重新计算
        dynamicTtlCache.remove(key);
    }
    
    /**
     * 批量记录访问
     */
    public void recordBatchAccess(Collection<String> keys) {
        keys.forEach(this::recordAccess);
    }
    
    /**
     * 计算访问因子
     */
    private double calculateAccessFactor(AccessStats stats) {
        // 基于访问频率
        double qps = stats.getRecentQPS();
        double frequencyFactor;
        
        if (qps > 100) {
            frequencyFactor = accessBoostFactor * 1.5; // 超高频
        } else if (qps > 50) {
            frequencyFactor = accessBoostFactor * 1.2; // 高频
        } else if (qps > 10) {
            frequencyFactor = accessBoostFactor;        // 中频
        } else if (qps > 1) {
            frequencyFactor = 1.0;                      // 低频
        } else {
            frequencyFactor = 0.8;                      // 极低频，缩短TTL
        }
        
        // 基于访问趋势
        double trend = stats.getAccessTrend();
        if (trend > 1.2) {
            frequencyFactor *= 1.1; // 上升趋势
        } else if (trend < 0.8) {
            frequencyFactor *= 0.9; // 下降趋势
        }
        
        return frequencyFactor;
    }
    
    /**
     * 应用时间段因子
     */
    private long applyTimeOfDayFactor(long baseTtl) {
        int currentHour = Instant.now().atZone(java.time.ZoneId.systemDefault()).getHour();
        
        // 获取当前小时相对访问量
        long currentHourAccess = hourlyAccessDistribution.get(currentHour).sum();
        long totalAccess = hourlyAccessDistribution.values().stream()
            .mapToLong(LongAdder::sum).sum();
        
        if (totalAccess == 0) return baseTtl;
        
        double hourlyRatio = (double) currentHourAccess / totalAccess * 24;
        
        // 高峰时段延长TTL
        if (hourlyRatio > 1.5) {
            ttlExtensionCounter.increment();
            return (long) (baseTtl * peakHourBoost);
        } else if (hourlyRatio < 0.5) {
            ttlReductionCounter.increment();
            return (long) (baseTtl * 0.8);
        }
        
        return baseTtl;
    }
    
    /**
     * 添加TTL抖动
     */
    private long addJitter(long ttl) {
        // 添加±10%的随机抖动
        double jitterFactor = 0.9 + Math.random() * 0.2;
        return (long) (ttl * jitterFactor);
    }
    
    /**
     * 获取key对应的策略
     */
    private TTLStrategy getStrategyForKey(String key) {
        for (Map.Entry<String, TTLStrategy> entry : businessStrategies.entrySet()) {
            if (key.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    /**
     * 注册自定义业务策略
     */
    public void registerStrategy(String keyPrefix, TTLStrategy strategy) {
        businessStrategies.put(keyPrefix, strategy);
        log.info("Registered TTL strategy: {} -> {}", keyPrefix, strategy);
    }
    
    /**
     * 获取延长后的TTL（用于续期场景）
     */
    public long getExtendedTTL(String key, long currentTtl) {
        AccessStats stats = accessStatsMap.get(key);
        if (stats == null || !stats.isHot()) {
            return currentTtl;
        }
        
        // 热点数据延长50%
        TTLStrategy strategy = getStrategyForKey(key);
        long maxTtl = strategy != null ? strategy.maxTtl : maxTtlSeconds;
        
        long extended = (long) (currentTtl * 1.5);
        return Math.min(extended, maxTtl);
    }
    
    /**
     * 定期清理过期统计
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void cleanupExpiredStats() {
        long threshold = System.currentTimeMillis() - 3600000; // 1小时前
        
        // 清理低活跃key的统计
        accessStatsMap.entrySet().removeIf(entry -> 
            entry.getValue().getLastAccessTime() < threshold && 
            entry.getValue().getTotalAccess() < 10);
        
        // 清理TTL缓存
        dynamicTtlCache.clear();
        
        log.debug("Cleaned up expired TTL stats, remaining: {}", accessStatsMap.size());
    }
    
    /**
     * 定期重置小时统计
     */
    @Scheduled(cron = "0 0 * * * ?") // 每小时
    public void decayHourlyStats() {
        // 衰减历史数据
        hourlyAccessDistribution.values().forEach(adder -> {
            long current = adder.sum();
            adder.reset();
            adder.add((long) (current * decayFactor));
        });
    }
    
    /**
     * 获取管理器统计
     */
    public TTLManagerStats getStats() {
        int hotKeyCount = (int) accessStatsMap.values().stream()
            .filter(AccessStats::isHot).count();
        
        OptionalDouble avgTtl = dynamicTtlCache.values().stream()
            .mapToLong(l -> l).average();
        
        return new TTLManagerStats(
            enabled,
            accessStatsMap.size(),
            hotKeyCount,
            dynamicTtlCache.size(),
            businessStrategies.size(),
            avgTtl.orElse(baseTtlSeconds),
            minTtlSeconds,
            maxTtlSeconds,
            baseTtlSeconds,
            getHourlyDistribution()
        );
    }
    
    private Map<Integer, Long> getHourlyDistribution() {
        return hourlyAccessDistribution.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
    }
    
    // ========== 内部类 ==========
    
    /**
     * 访问统计信息
     */
    private static class AccessStats {
        private final LongAdder totalAccess = new LongAdder();
        private final LongAdder recentAccess = new LongAdder(); // 最近1分钟
        private volatile long lastAccessTime;
        private volatile long lastMinuteReset;
        private volatile long previousMinuteAccess;
        
        AccessStats(long timestamp) {
            this.lastAccessTime = timestamp;
            this.lastMinuteReset = timestamp;
            this.totalAccess.increment();
            this.recentAccess.increment();
        }
        
        void recordAccess(long timestamp) {
            totalAccess.increment();
            lastAccessTime = timestamp;
            
            // 检查是否需要重置分钟计数
            if (timestamp - lastMinuteReset > 60000) {
                previousMinuteAccess = recentAccess.sum();
                recentAccess.reset();
                lastMinuteReset = timestamp;
            }
            recentAccess.increment();
        }
        
        double getRecentQPS() {
            long elapsed = System.currentTimeMillis() - lastMinuteReset;
            if (elapsed <= 0) return 0;
            return (double) recentAccess.sum() / (elapsed / 1000.0);
        }
        
        double getAccessTrend() {
            if (previousMinuteAccess <= 0) return 1.0;
            return (double) recentAccess.sum() / previousMinuteAccess;
        }
        
        boolean isHot() {
            return getRecentQPS() > 10;
        }
        
        long getLastAccessTime() {
            return lastAccessTime;
        }
        
        long getTotalAccess() {
            return totalAccess.sum();
        }
    }
    
    /**
     * TTL策略配置
     */
    public record TTLStrategy(
        String name,
        long minTtl,
        long maxTtl,
        double boostFactor,
        double decayFactor,
        boolean adaptiveEnabled
    ) {}
    
    /**
     * 管理器统计
     */
    public record TTLManagerStats(
        boolean enabled,
        int trackedKeyCount,
        int hotKeyCount,
        int cachedTtlCount,
        int strategyCount,
        double avgTtlSeconds,
        long minTtlSeconds,
        long maxTtlSeconds,
        long baseTtlSeconds,
        Map<Integer, Long> hourlyDistribution
    ) {}
}
