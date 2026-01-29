package com.ecommerce.cache.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 多级缓存门面服务 - 增强版
 * 统一封装 L1/L2/L3 缓存访问逻辑
 * 
 * 优化特性：
 * 1. 并行读取 L2/L3（First-Win 策略）
 * 2. 异步刷新机制防止缓存雪崩
 * 3. 熔断降级保护
 * 4. 详细的性能指标采集
 * 
 * 总命中率目标：97.3%，TP99：38ms
 */
@Service
public class MultiLevelCacheService {
    
    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheService.class);
    
    private static final long DEFAULT_TTL_SECONDS = 600;
    
    private final L1CacheService l1CacheService;
    private final L2RedisService l2RedisService;
    private final L3MemcachedService l3MemcachedService;
    private final BloomFilterService bloomFilterService;
    private final CacheBreakdownProtector breakdownProtector;
    private final HotKeyDetectorService hotKeyDetectorService;
    private final ParallelCacheReader parallelCacheReader;
    private final CacheResilienceService resilienceService;
    private final MeterRegistry meterRegistry;
    
    // 异步刷新执行器
    private final ExecutorService asyncRefreshExecutor;
    
    // 异步刷新队列（去重）
    private final ConcurrentHashMap<String, CompletableFuture<String>> refreshingKeys = new ConcurrentHashMap<>();
    
    // 性能指标
    private final Timer l1HitTimer;
    private final Timer l2HitTimer;
    private final Timer l3HitTimer;
    private final Timer dbLoadTimer;
    private final Counter l1HitCounter;
    private final Counter l2HitCounter;
    private final Counter l3HitCounter;
    private final Counter cacheMissCounter;
    
    @Value("${multilevel-cache.async-refresh.threshold-ttl:120}")
    private long asyncRefreshThresholdTtl;
    
    @Value("${multilevel-cache.parallel-read.enabled:true}")
    private boolean parallelReadEnabled;

    public MultiLevelCacheService(L1CacheService l1CacheService,
                                   L2RedisService l2RedisService,
                                   L3MemcachedService l3MemcachedService,
                                   BloomFilterService bloomFilterService,
                                   CacheBreakdownProtector breakdownProtector,
                                   HotKeyDetectorService hotKeyDetectorService,
                                   ParallelCacheReader parallelCacheReader,
                                   CacheResilienceService resilienceService,
                                   MeterRegistry meterRegistry) {
        this.l1CacheService = l1CacheService;
        this.l2RedisService = l2RedisService;
        this.l3MemcachedService = l3MemcachedService;
        this.bloomFilterService = bloomFilterService;
        this.breakdownProtector = breakdownProtector;
        this.hotKeyDetectorService = hotKeyDetectorService;
        this.parallelCacheReader = parallelCacheReader;
        this.resilienceService = resilienceService;
        this.meterRegistry = meterRegistry;
        
        // 初始化虚拟线程执行器
        this.asyncRefreshExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        // 初始化指标
        this.l1HitTimer = Timer.builder("cache.l1.hit.latency").register(meterRegistry);
        this.l2HitTimer = Timer.builder("cache.l2.hit.latency").register(meterRegistry);
        this.l3HitTimer = Timer.builder("cache.l3.hit.latency").register(meterRegistry);
        this.dbLoadTimer = Timer.builder("cache.db.load.latency").register(meterRegistry);
        this.l1HitCounter = Counter.builder("cache.l1.hits").register(meterRegistry);
        this.l2HitCounter = Counter.builder("cache.l2.hits").register(meterRegistry);
        this.l3HitCounter = Counter.builder("cache.l3.hits").register(meterRegistry);
        this.cacheMissCounter = Counter.builder("cache.misses").register(meterRegistry);
    }

    /**
     * 多级缓存读取（核心方法）
     * L1 → L2 → L3 → DB
     */
    public String get(String key, Supplier<String> dbLoader) {
        return get(key, dbLoader, DEFAULT_TTL_SECONDS);
    }

    /**
     * 多级缓存读取（带自定义 TTL）- 增强版
     * 特性：并行读取、异步刷新、熔断保护
     */
    public String get(String key, Supplier<String> dbLoader, long ttlSeconds) {
        // ========== 0. 布隆过滤器防穿透 ==========
        String spuId = extractSpuId(key);
        if (!bloomFilterService.mightContain(spuId)) {
            log.debug("Bloom filter rejected: {}", key);
            return null;
        }
        
        // ========== 1. 记录访问，检测热点 ==========
        boolean isHotKey = hotKeyDetectorService.recordAccess(key);
        
        // ========== 2. L1 本地缓存 ==========
        String value = l1HitTimer.record(() -> l1CacheService.get(key));
        if (value != null) {
            l1HitCounter.increment();
            log.debug("L1 hit: {}", key);
            return handleEmptyValue(value);
        }
        
        // ========== 3. L2/L3 并行读取（可配置） ==========
        if (parallelReadEnabled) {
            value = parallelCacheReader.parallelGet(key);
            if (value != null) {
                l2HitCounter.increment();
                l1CacheService.put(key, value);
                // 异步检查是否需要刷新
                triggerAsyncRefreshIfNeeded(key, dbLoader, ttlSeconds);
                return handleEmptyValue(value);
            }
        } else {
            // 传统顺序读取
            value = getFromL2WithResilience(key, isHotKey);
            if (value != null) {
                l2HitCounter.increment();
                log.debug("L2 hit: {}", key);
                l1CacheService.put(key, value);
                triggerAsyncRefreshIfNeeded(key, dbLoader, ttlSeconds);
                return handleEmptyValue(value);
            }
            
            value = getFromL3WithResilience(key);
            if (value != null) {
                l3HitCounter.increment();
                log.debug("L3 hit: {}", key);
                l2RedisService.set(key, value, ttlSeconds);
                l1CacheService.put(key, value);
                return handleEmptyValue(value);
            }
        }
        
        // ========== 4. 回源 DB（带击穿保护 + 熔断） ==========
        cacheMissCounter.increment();
        log.info("All cache miss, loading from DB: {}", key);
        
        return dbLoadTimer.record(() -> 
            loadFromDbWithProtection(key, dbLoader, ttlSeconds, isHotKey, spuId));
    }
    
    /**
     * 带熔断保护的 L2 读取
     */
    private String getFromL2WithResilience(String key, boolean isHotKey) {
        return resilienceService.executeRedisWithProtection(
            () -> isHotKey ? l2RedisService.getHotKey(key) : l2RedisService.get(key),
            () -> null
        );
    }
    
    /**
     * 带熔断保护的 L3 读取
     */
    private String getFromL3WithResilience(String key) {
        return resilienceService.executeMemcachedWithProtection(
            () -> l3MemcachedService.get(key),
            () -> null
        );
    }
    
    /**
     * 带保护的数据库回源
     */
    private String loadFromDbWithProtection(String key, Supplier<String> dbLoader, 
                                             long ttlSeconds, boolean isHotKey, String spuId) {
        return resilienceService.executeDbLoadWithProtection(
            () -> breakdownProtector.getWithBreakdownProtection(key, () -> {
                String dbValue = dbLoader.get();
                if (dbValue != null) {
                    // 回填所有层级
                    l3MemcachedService.set(key, dbValue, (int) ttlSeconds);
                    if (isHotKey) {
                        l2RedisService.setHotKey(key, dbValue, ttlSeconds);
                    } else {
                        l2RedisService.set(key, dbValue, ttlSeconds);
                    }
                    l1CacheService.put(key, dbValue);
                    bloomFilterService.add(spuId);
                    // 缓存降级数据
                    resilienceService.cacheFallbackData(key, dbValue);
                } else {
                    l2RedisService.set(key, "", 60);
                }
                return dbValue;
            }, ttlSeconds),
            // 降级策略：返回缓存的兆底数据
            () -> resilienceService.getFallbackData(key)
        );
    }
    
    /**
     * 异步刷新机制：TTL 低于阈值时触发后台刷新
     * 防止缓存雪崩，延长缓存有效期
     */
    private void triggerAsyncRefreshIfNeeded(String key, Supplier<String> dbLoader, long ttlSeconds) {
        // 检查是否已在刷新中
        if (refreshingKeys.containsKey(key)) {
            return;
        }
        
        // 检查 TTL
        Long currentTtl = l2RedisService.ttl(key);
        if (currentTtl != null && currentTtl > 0 && currentTtl < asyncRefreshThresholdTtl) {
            CompletableFuture<String> refreshFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    log.debug("Async refreshing cache: {}", key);
                    String newValue = dbLoader.get();
                    if (newValue != null) {
                        l3MemcachedService.set(key, newValue, (int) ttlSeconds);
                        l2RedisService.set(key, newValue, ttlSeconds);
                        l1CacheService.put(key, newValue);
                        log.debug("Async refresh completed: {}", key);
                    }
                    return newValue;
                } finally {
                    refreshingKeys.remove(key);
                }
            }, asyncRefreshExecutor);
            
            refreshingKeys.put(key, refreshFuture);
        }
    }

    /**
     * 仅从缓存获取（不回源 DB）
     */
    public String getFromCacheOnly(String key) {
        // L1
        String value = l1CacheService.get(key);
        if (value != null) return handleEmptyValue(value);
        
        // 并行读取 L2/L3
        if (parallelReadEnabled) {
            value = parallelCacheReader.parallelGet(key);
            if (value != null) {
                l1CacheService.put(key, value);
                return handleEmptyValue(value);
            }
        } else {
            // L2
            value = l2RedisService.get(key);
            if (value != null) {
                l1CacheService.put(key, value);
                return handleEmptyValue(value);
            }
            
            // L3
            value = l3MemcachedService.get(key);
            if (value != null) {
                l2RedisService.set(key, value);
                l1CacheService.put(key, value);
                return handleEmptyValue(value);
            }
        }
        
        return null;
    }
    
    /**
     * 批量获取（高性能并行读取）
     */
    public Map<String, String> batchGet(Collection<String> keys, 
                                         java.util.function.Function<String, String> dbLoader) {
        // 先从 L1 获取
        Map<String, String> results = new ConcurrentHashMap<>();
        keys.forEach(key -> {
            String value = l1CacheService.get(key);
            if (value != null && !value.isEmpty()) {
                results.put(key, value);
            }
        });
        
        // 未命中的从 L2/L3 并行获取
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
     * 写入多级缓存
     */
    public void put(String key, String value) {
        put(key, value, DEFAULT_TTL_SECONDS);
    }

    /**
     * 写入多级缓存（带 TTL）
     */
    public void put(String key, String value, long ttlSeconds) {
        boolean isHotKey = hotKeyDetectorService.isHotKey(key);
        
        // L3
        l3MemcachedService.set(key, value, (int) ttlSeconds);
        
        // L2
        if (isHotKey) {
            l2RedisService.setHotKey(key, value, ttlSeconds);
        } else {
            l2RedisService.set(key, value, ttlSeconds);
        }
        
        // L1
        l1CacheService.put(key, value);
        
        // 布隆过滤器
        String spuId = extractSpuId(key);
        bloomFilterService.add(spuId);
        
        log.debug("Cache put: {}", key);
    }

    /**
     * 多级缓存删除
     * 顺序：L1 → L2 → L3（先删本地，后删远程）
     */
    public void invalidate(String key) {
        l1CacheService.invalidate(key);
        l2RedisService.deleteHotKey(key);
        l3MemcachedService.delete(key);
        log.info("Cache invalidated: {}", key);
    }

    /**
     * 批量删除缓存
     */
    public void invalidateAll(Iterable<String> keys) {
        for (String key : keys) {
            invalidate(key);
        }
    }

    /**
     * 刷新缓存（删除后重新加载）
     */
    public String refresh(String key, Supplier<String> dbLoader) {
        invalidate(key);
        return get(key, dbLoader);
    }

    /**
     * 预热缓存
     */
    public void preheat(String key, String value, long ttlSeconds) {
        put(key, value, ttlSeconds);
        log.info("Cache preheated: {}", key);
    }

    /**
     * 处理空值缓存（防穿透）
     */
    private String handleEmptyValue(String value) {
        return "".equals(value) ? null : value;
    }

    /**
     * 从 Key 中提取 SPU ID
     */
    private String extractSpuId(String key) {
        // key 格式：spu:detail:{spuId}
        int lastIndex = key.lastIndexOf(':');
        return lastIndex > 0 ? key.substring(lastIndex + 1) : key;
    }

    /**
     * 获取缓存统计信息（增强版）
     */
    public CacheStats getStats() {
        return new CacheStats(
            l1CacheService.getHitRate(),
            l1CacheService.size(),
            l1HitCounter.count(),
            l2HitCounter.count(),
            l3HitCounter.count(),
            cacheMissCounter.count(),
            calculateTotalHitRate(),
            refreshingKeys.size()
        );
    }
    
    private double calculateTotalHitRate() {
        double total = l1HitCounter.count() + l2HitCounter.count() + l3HitCounter.count() + cacheMissCounter.count();
        if (total == 0) return 0;
        return (l1HitCounter.count() + l2HitCounter.count() + l3HitCounter.count()) / total;
    }

    public record CacheStats(
        double l1HitRate, 
        long l1Size,
        double l1Hits,
        double l2Hits,
        double l3Hits,
        double misses,
        double totalHitRate,
        int asyncRefreshingCount
    ) {}
}
