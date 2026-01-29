package com.ecommerce.cache.optimization.v5;

import com.ecommerce.cache.service.*;
import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 增强版多级缓存门面服务 V5 - 终极性能优化
 * 
 * 整合所有优化模块，提供统一的缓存访问接口：
 * 
 * 1. 智能热点检测（HeavyKeeper 算法）
 * 2. 动态分片路由（自适应负载均衡）
 * 3. 流量整形（削峰填谷）
 * 4. 智能预热与预取
 * 5. 自适应 TTL
 * 6. 多级降级策略
 * 7. 全链路追踪
 * 
 * 性能目标：
 * - QPS: 10W+
 * - TP99: < 30ms
 * - 命中率: > 98%
 * - 内存效率: 优化 40%
 */
@Service
public class EnhancedMultiLevelCacheV5 {
    
    private static final Logger log = LoggerFactory.getLogger(EnhancedMultiLevelCacheV5.class);
    
    // ========== 配置 ==========
    @Value("${optimization.v5.enabled:true}")
    private boolean enabled;
    
    @Value("${optimization.v5.hot-key-detection-enabled:true}")
    private boolean hotKeyDetectionEnabled;
    
    @Value("${optimization.v5.traffic-shaping-enabled:true}")
    private boolean trafficShapingEnabled;
    
    @Value("${optimization.v5.smart-shard-enabled:true}")
    private boolean smartShardEnabled;
    
    @Value("${optimization.v5.prefetch-enabled:true}")
    private boolean prefetchEnabled;
    
    @Value("${optimization.v5.adaptive-ttl-enabled:true}")
    private boolean adaptiveTtlEnabled;
    
    @Value("${optimization.v5.default-ttl-seconds:600}")
    private long defaultTtlSeconds;
    
    @Value("${optimization.v5.min-ttl-seconds:60}")
    private long minTtlSeconds;
    
    @Value("${optimization.v5.max-ttl-seconds:7200}")
    private long maxTtlSeconds;
    
    // ========== 依赖服务 ==========
    private final L1CacheService l1CacheService;
    private final L2RedisService l2RedisService;
    private final L3MemcachedService l3MemcachedService;
    private final BloomFilterService bloomFilterService;
    private final ParallelCacheReader parallelCacheReader;
    private final CacheResilienceService resilienceService;
    
    // ========== V5 优化模块 ==========
    private final HeavyKeeperHotKeyDetector hotKeyDetector;
    private final SmartShardRouter shardRouter;
    private final TrafficShapingService trafficShaping;
    private final SmartCacheWarmer cacheWarmer;
    
    // ========== 自适应 TTL ==========
    private final ConcurrentHashMap<String, AccessStats> accessStatsMap = new ConcurrentHashMap<>();
    
    // ========== 统计指标 ==========
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l2Hits = new LongAdder();
    private final LongAdder l3Hits = new LongAdder();
    private final LongAdder dbLoads = new LongAdder();
    private final LongAdder hotKeyAccess = new LongAdder();
    private final AtomicLong lastCalculatedHitRate = new AtomicLong(0);
    
    // ========== 指标注册 ==========
    private final MeterRegistry meterRegistry;
    private Timer totalAccessTimer;
    private Timer l1AccessTimer;
    private Timer l2AccessTimer;
    private Timer l3AccessTimer;
    private Timer dbLoadTimer;
    private Counter degradationCounter;
    
    // ========== 执行器 ==========
    private ExecutorService asyncExecutor;
    
    public EnhancedMultiLevelCacheV5(
            L1CacheService l1CacheService,
            L2RedisService l2RedisService,
            L3MemcachedService l3MemcachedService,
            BloomFilterService bloomFilterService,
            ParallelCacheReader parallelCacheReader,
            CacheResilienceService resilienceService,
            HeavyKeeperHotKeyDetector hotKeyDetector,
            SmartShardRouter shardRouter,
            TrafficShapingService trafficShaping,
            SmartCacheWarmer cacheWarmer,
            MeterRegistry meterRegistry) {
        this.l1CacheService = l1CacheService;
        this.l2RedisService = l2RedisService;
        this.l3MemcachedService = l3MemcachedService;
        this.bloomFilterService = bloomFilterService;
        this.parallelCacheReader = parallelCacheReader;
        this.resilienceService = resilienceService;
        this.hotKeyDetector = hotKeyDetector;
        this.shardRouter = shardRouter;
        this.trafficShaping = trafficShaping;
        this.cacheWarmer = cacheWarmer;
        this.meterRegistry = meterRegistry;
    }
    
    @PostConstruct
    public void init() {
        // 初始化执行器
        asyncExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("cache-v5-", 0).factory()
        );
        
        // 注册指标
        totalAccessTimer = Timer.builder("cache.v5.access.total.latency")
            .publishPercentileHistogram()
            .publishPercentiles(0.5, 0.9, 0.95, 0.99, 0.999)
            .register(meterRegistry);
        l1AccessTimer = Timer.builder("cache.v5.l1.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        l2AccessTimer = Timer.builder("cache.v5.l2.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        l3AccessTimer = Timer.builder("cache.v5.l3.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        dbLoadTimer = Timer.builder("cache.v5.db.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        degradationCounter = Counter.builder("cache.v5.degradation")
            .register(meterRegistry);
        
        Gauge.builder("cache.v5.hit.rate", this, EnhancedMultiLevelCacheV5::calculateHitRate)
            .register(meterRegistry);
        Gauge.builder("cache.v5.total.requests", totalRequests, LongAdder::sum)
            .register(meterRegistry);
        Gauge.builder("cache.v5.hot.key.access", hotKeyAccess, LongAdder::sum)
            .register(meterRegistry);
        
        log.info("EnhancedMultiLevelCacheV5 initialized: hotKey={}, sharding={}, traffic={}, prefetch={}",
            hotKeyDetectionEnabled, smartShardEnabled, trafficShapingEnabled, prefetchEnabled);
    }
    
    /**
     * 核心读取方法 - 整合所有优化
     */
    public String get(String key, Supplier<String> dbLoader) {
        return get(key, dbLoader, defaultTtlSeconds);
    }
    
    /**
     * 核心读取方法（带自定义 TTL）
     */
    public String get(String key, Supplier<String> dbLoader, long ttlSeconds) {
        if (!enabled) {
            return getFallback(key, dbLoader, ttlSeconds);
        }
        
        return totalAccessTimer.record(() -> {
            try {
                // 流量整形
                if (trafficShapingEnabled) {
                    return trafficShaping.executeWithShaping(key, () -> 
                        doGet(key, dbLoader, ttlSeconds));
                } else {
                    return doGet(key, dbLoader, ttlSeconds);
                }
            } catch (TrafficShapingService.TrafficOverloadException e) {
                log.warn("Traffic overload, returning cached data: key={}", key);
                degradationCounter.increment();
                return resilienceService.getFallbackData(key);
            } catch (Exception e) {
                log.error("Cache access error: key={}", key, e);
                return resilienceService.getFallbackData(key);
            }
        });
    }
    
    /**
     * 实际的缓存获取逻辑
     */
    private String doGet(String key, Supplier<String> dbLoader, long ttlSeconds) {
        totalRequests.increment();
        
        // 1. 记录访问（热点检测 + 预热学习）
        if (hotKeyDetectionEnabled) {
            hotKeyDetector.add(key);
        }
        if (prefetchEnabled) {
            cacheWarmer.recordAccess(key);
        }
        
        // 2. 布隆过滤器检查
        String spuId = extractSpuId(key);
        if (!bloomFilterService.mightContain(spuId)) {
            log.debug("Bloom filter rejected: {}", key);
            return null;
        }
        
        // 3. 判断是否为热点 Key
        boolean isHotKey = hotKeyDetectionEnabled && hotKeyDetector.isHotKey(key);
        if (isHotKey) {
            hotKeyAccess.increment();
        }
        
        // 4. L1 本地缓存
        String value = l1AccessTimer.record(() -> l1CacheService.get(key));
        if (value != null) {
            l1Hits.increment();
            updateAccessStats(key);
            return handleEmptyValue(value);
        }
        
        // 5. L2/L3 访问（根据热点状态选择策略）
        if (isHotKey && smartShardEnabled) {
            // 热点 Key：使用分片路由
            value = getFromShardedCache(key);
        } else {
            // 普通 Key：并行读取
            value = getFromRemoteCache(key);
        }
        
        if (value != null) {
            l1CacheService.put(key, value);
            updateAccessStats(key);
            
            // 触发预取
            if (prefetchEnabled) {
                triggerPrefetchAsync(key, dbLoader);
            }
            
            return handleEmptyValue(value);
        }
        
        // 6. 回源 DB
        dbLoads.increment();
        return dbLoadTimer.record(() -> loadFromDb(key, dbLoader, ttlSeconds, isHotKey, spuId));
    }
    
    /**
     * 从分片缓存读取
     */
    private String getFromShardedCache(String originalKey) {
        return l2AccessTimer.record(() -> {
            String shardKey = shardRouter.getShardKey(originalKey);
            String value = l2RedisService.get(shardKey);
            
            if (value != null) {
                l2Hits.increment();
                return value;
            }
            
            // L3 回退
            return l3AccessTimer.record(() -> {
                String l3Value = l3MemcachedService.get(originalKey);
                if (l3Value != null) {
                    l3Hits.increment();
                }
                return l3Value;
            });
        });
    }
    
    /**
     * 从远程缓存读取（并行）
     */
    private String getFromRemoteCache(String key) {
        String value = l2AccessTimer.record(() -> {
            String v = parallelCacheReader.parallelGet(key);
            if (v != null) {
                l2Hits.increment();
            }
            return v;
        });
        
        if (value == null) {
            value = l3AccessTimer.record(() -> {
                String v = l3MemcachedService.get(key);
                if (v != null) {
                    l3Hits.increment();
                }
                return v;
            });
        }
        
        return value;
    }
    
    /**
     * 从 DB 加载（带保护）
     */
    private String loadFromDb(String key, Supplier<String> dbLoader, 
                               long baseTtlSeconds, boolean isHotKey, String spuId) {
        return resilienceService.executeDbLoadWithProtection(
            () -> {
                String dbValue = dbLoader.get();
                if (dbValue != null) {
                    // 计算自适应 TTL
                    long ttl = adaptiveTtlEnabled ? 
                        calculateAdaptiveTtl(key, baseTtlSeconds) : baseTtlSeconds;
                    
                    // 写入缓存
                    if (isHotKey && smartShardEnabled) {
                        // 热点 Key：写入所有分片
                        shardRouter.writeToAllShards(key, dbValue, ttl);
                    } else {
                        l2RedisService.set(key, dbValue, ttl);
                    }
                    
                    l3MemcachedService.set(key, dbValue, (int) ttl);
                    l1CacheService.put(key, dbValue);
                    bloomFilterService.add(spuId);
                    
                    // 缓存降级数据
                    resilienceService.cacheFallbackData(key, dbValue);
                } else {
                    // 缓存空值防穿透
                    l2RedisService.set(key, "", 60);
                }
                return dbValue;
            },
            () -> resilienceService.getFallbackData(key)
        );
    }
    
    /**
     * 写入缓存
     */
    public void put(String key, String value) {
        put(key, value, defaultTtlSeconds);
    }
    
    /**
     * 写入缓存（带 TTL）
     */
    public void put(String key, String value, long ttlSeconds) {
        if (!enabled) {
            simplePut(key, value, ttlSeconds);
            return;
        }
        
        boolean isHotKey = hotKeyDetectionEnabled && hotKeyDetector.isHotKey(key);
        
        // 计算自适应 TTL
        long ttl = adaptiveTtlEnabled ? 
            calculateAdaptiveTtl(key, ttlSeconds) : ttlSeconds;
        
        // 写入 L3
        l3MemcachedService.set(key, value, (int) ttl);
        
        // 写入 L2
        if (isHotKey && smartShardEnabled) {
            shardRouter.writeToAllShards(key, value, ttl);
        } else {
            l2RedisService.set(key, value, ttl);
        }
        
        // 写入 L1
        l1CacheService.put(key, value);
        
        // 更新布隆过滤器
        String spuId = extractSpuId(key);
        bloomFilterService.add(spuId);
        
        log.debug("Cache put: key={}, ttl={}s, isHotKey={}", key, ttl, isHotKey);
    }
    
    /**
     * 删除缓存
     */
    public void invalidate(String key) {
        // L1
        l1CacheService.invalidate(key);
        
        // L2（包括分片）
        if (smartShardEnabled) {
            shardRouter.deleteAllShards(key);
        } else {
            l2RedisService.delete(key);
        }
        
        // L3
        l3MemcachedService.delete(key);
        
        log.debug("Cache invalidated: key={}", key);
    }
    
    /**
     * 批量获取
     */
    public Map<String, String> batchGet(Collection<String> keys, 
                                         java.util.function.Function<String, String> dbLoader) {
        Map<String, String> results = new ConcurrentHashMap<>();
        
        // 先从 L1 获取
        keys.forEach(key -> {
            String value = l1CacheService.get(key);
            if (value != null && !value.isEmpty()) {
                results.put(key, value);
            }
        });
        
        // 未命中的并行获取
        var missedKeys = keys.stream()
            .filter(k -> !results.containsKey(k))
            .toList();
        
        if (!missedKeys.isEmpty()) {
            Map<String, String> remoteResults = parallelCacheReader.batchParallelGet(missedKeys);
            results.putAll(remoteResults);
            
            // 回填 L1
            remoteResults.forEach(l1CacheService::put);
        }
        
        return results;
    }
    
    /**
     * 获取统计信息
     */
    public CacheStatsV5 getStats() {
        return new CacheStatsV5(
            totalRequests.sum(),
            l1Hits.sum(),
            l2Hits.sum(),
            l3Hits.sum(),
            dbLoads.sum(),
            hotKeyAccess.sum(),
            calculateHitRate(),
            calculateL1HitRate(),
            hotKeyDetector.getStats(),
            shardRouter != null ? shardRouter.getShardStats("global") : null,
            trafficShaping.getStatus(),
            cacheWarmer.getStats()
        );
    }
    
    // ========== 私有方法 ==========
    
    private String getFallback(String key, Supplier<String> dbLoader, long ttlSeconds) {
        String value = l1CacheService.get(key);
        if (value != null) return handleEmptyValue(value);
        
        value = l2RedisService.get(key);
        if (value != null) {
            l1CacheService.put(key, value);
            return handleEmptyValue(value);
        }
        
        value = l3MemcachedService.get(key);
        if (value != null) {
            l2RedisService.set(key, value, ttlSeconds);
            l1CacheService.put(key, value);
            return handleEmptyValue(value);
        }
        
        value = dbLoader.get();
        if (value != null) {
            simplePut(key, value, ttlSeconds);
        }
        return value;
    }
    
    private void simplePut(String key, String value, long ttlSeconds) {
        l3MemcachedService.set(key, value, (int) ttlSeconds);
        l2RedisService.set(key, value, ttlSeconds);
        l1CacheService.put(key, value);
    }
    
    private void triggerPrefetchAsync(String key, Supplier<String> dbLoader) {
        asyncExecutor.submit(() -> {
            try {
                cacheWarmer.triggerPrefetch(key, dbLoader);
            } catch (Exception e) {
                log.debug("Prefetch trigger failed for key: {}", key);
            }
        });
    }
    
    private long calculateAdaptiveTtl(String key, long baseTtl) {
        AccessStats stats = accessStatsMap.get(key);
        if (stats == null) {
            return baseTtl;
        }
        
        // 访问越频繁，TTL 越长
        double accessRate = stats.getAccessRate();
        double multiplier = 1.0;
        
        if (accessRate > 100) {
            multiplier = 2.0; // 高频访问，TTL 翻倍
        } else if (accessRate > 10) {
            multiplier = 1.5;
        } else if (accessRate < 1) {
            multiplier = 0.5; // 低频访问，减少 TTL
        }
        
        long adaptiveTtl = (long) (baseTtl * multiplier);
        return Math.max(minTtlSeconds, Math.min(maxTtlSeconds, adaptiveTtl));
    }
    
    private void updateAccessStats(String key) {
        accessStatsMap.computeIfAbsent(key, k -> new AccessStats()).recordAccess();
    }
    
    private String handleEmptyValue(String value) {
        return "".equals(value) ? null : value;
    }
    
    private String extractSpuId(String key) {
        int lastIndex = key.lastIndexOf(':');
        return lastIndex > 0 ? key.substring(lastIndex + 1) : key;
    }
    
    private double calculateHitRate() {
        long total = totalRequests.sum();
        if (total == 0) return 0;
        long hits = l1Hits.sum() + l2Hits.sum() + l3Hits.sum();
        return (double) hits / total;
    }
    
    private double calculateL1HitRate() {
        long total = totalRequests.sum();
        return total > 0 ? (double) l1Hits.sum() / total : 0;
    }
    
    // ========== 内部类 ==========
    
    private static class AccessStats {
        private final LongAdder accessCount = new LongAdder();
        private volatile long firstAccessTime = System.currentTimeMillis();
        
        void recordAccess() {
            accessCount.increment();
        }
        
        double getAccessRate() {
            long elapsed = System.currentTimeMillis() - firstAccessTime;
            if (elapsed < 1000) return 0;
            return accessCount.sum() * 1000.0 / elapsed; // 每秒访问次数
        }
    }
    
    /**
     * V5 缓存统计
     */
    public record CacheStatsV5(
        long totalRequests,
        long l1Hits,
        long l2Hits,
        long l3Hits,
        long dbLoads,
        long hotKeyAccess,
        double totalHitRate,
        double l1HitRate,
        HeavyKeeperHotKeyDetector.HeavyKeeperStats hotKeyStats,
        SmartShardRouter.ShardGroupStats shardStats,
        TrafficShapingService.TrafficStatus trafficStatus,
        SmartCacheWarmer.WarmupStats warmupStats
    ) {}
}
