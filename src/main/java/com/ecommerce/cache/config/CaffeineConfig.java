package com.ecommerce.cache.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Weigher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置
 * 采用 W-TinyLFU 淘汰策略，命中率比 LRU 高 15%
 * 目标：单机 10K 热 Key，命中率 35%，延迟 0.5ms
 */
@Configuration
@ConfigurationProperties(prefix = "multilevel-cache.caffeine")
public class CaffeineConfig {
    
    private static final Logger log = LoggerFactory.getLogger(CaffeineConfig.class);
    
    private int initialCapacity = 1000;
    private long maximumSize = 10000;
    private long maximumWeight = 256; // MB
    private long expireAfterWrite = 300; // seconds
    private long expireAfterAccess = 180; // seconds
    private long refreshAfterWrite = 60; // seconds
    private boolean recordStats = true;
    private boolean weakKeys = false;
    private boolean softValues = false;

    /**
     * SPU 详情本地缓存
     * 采用 W-TinyLFU 淘汰策略，自动识别热点数据
     */
    @Bean("spuLocalCache")
    public Cache<String, String> spuLocalCache(MeterRegistry meterRegistry) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
            // 初始容量，避免扩容开销
            .initialCapacity(initialCapacity)
            // 基于权重的容量限制（按序列化后字节数）
            .maximumWeight(maximumWeight * 1024 * 1024)
            .weigher((Weigher<String, String>) (key, value) -> 
                key.length() * 2 + value.length() * 2) // 估算字符串内存占用
            // 写入后过期（核心配置）
            .expireAfterWrite(expireAfterWrite, TimeUnit.SECONDS)
            // 移除监听器，用于监控和日志
            .removalListener((key, value, cause) -> {
                if (cause == RemovalCause.SIZE) {
                    log.debug("Cache evicted due to size: key={}", key);
                } else if (cause == RemovalCause.EXPIRED) {
                    log.debug("Cache expired: key={}", key);
                }
            });
        
        // 开启统计（生产环境建议开启，用于监控命中率）
        if (recordStats) {
            builder.recordStats();
        }
        
        Cache<String, String> cache = builder.build();
        
        // 注册 Micrometer 指标
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "spu_local_cache");
        
        log.info("SPU local cache initialized: initialCapacity={}, maximumWeight={}MB, expireAfterWrite={}s",
            initialCapacity, maximumWeight, expireAfterWrite);
        
        return cache;
    }

    /**
     * 热 Key 本地缓存（更大容量，更长过期时间）
     */
    @Bean("hotKeyLocalCache")
    public Cache<String, String> hotKeyLocalCache(MeterRegistry meterRegistry) {
        Cache<String, String> cache = Caffeine.newBuilder()
            .initialCapacity(500)
            .maximumSize(5000)
            .expireAfterWrite(600, TimeUnit.SECONDS) // 热 Key 缓存 10 分钟
            .recordStats()
            .removalListener((key, value, cause) -> {
                if (cause == RemovalCause.SIZE || cause == RemovalCause.EXPIRED) {
                    log.debug("Hot key cache evicted: key={}, cause={}", key, cause);
                }
            })
            .build();
        
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "hot_key_local_cache");
        
        log.info("Hot key local cache initialized: maximumSize=5000, expireAfterWrite=600s");
        
        return cache;
    }

    /**
     * 空值缓存（防穿透）
     */
    @Bean("nullValueCache")
    public Cache<String, Boolean> nullValueCache(MeterRegistry meterRegistry) {
        Cache<String, Boolean> cache = Caffeine.newBuilder()
            .initialCapacity(100)
            .maximumSize(1000)
            .expireAfterWrite(60, TimeUnit.SECONDS) // 空值缓存 1 分钟
            .recordStats()
            .build();
        
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "null_value_cache");
        
        return cache;
    }

    // Getters and Setters
    public int getInitialCapacity() { return initialCapacity; }
    public void setInitialCapacity(int initialCapacity) { this.initialCapacity = initialCapacity; }
    
    public long getMaximumSize() { return maximumSize; }
    public void setMaximumSize(long maximumSize) { this.maximumSize = maximumSize; }
    
    public long getMaximumWeight() { return maximumWeight; }
    public void setMaximumWeight(long maximumWeight) { this.maximumWeight = maximumWeight; }
    
    public long getExpireAfterWrite() { return expireAfterWrite; }
    public void setExpireAfterWrite(long expireAfterWrite) { this.expireAfterWrite = expireAfterWrite; }
    
    public long getExpireAfterAccess() { return expireAfterAccess; }
    public void setExpireAfterAccess(long expireAfterAccess) { this.expireAfterAccess = expireAfterAccess; }
    
    public long getRefreshAfterWrite() { return refreshAfterWrite; }
    public void setRefreshAfterWrite(long refreshAfterWrite) { this.refreshAfterWrite = refreshAfterWrite; }
    
    public boolean isRecordStats() { return recordStats; }
    public void setRecordStats(boolean recordStats) { this.recordStats = recordStats; }
    
    public boolean isWeakKeys() { return weakKeys; }
    public void setWeakKeys(boolean weakKeys) { this.weakKeys = weakKeys; }
    
    public boolean isSoftValues() { return softValues; }
    public void setSoftValues(boolean softValues) { this.softValues = softValues; }
}
