package com.ecommerce.cache.optimization.v5;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.ecommerce.cache.service.*;

/**
 * V5 优化模块自动配置
 * 
 * 自动装配所有 V5 优化组件，提供开箱即用的极致性能
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OptimizationV5Properties.class)
@ConditionalOnProperty(prefix = "optimization.v5", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OptimizationV5AutoConfiguration {
    
    private static final Logger log = LoggerFactory.getLogger(OptimizationV5AutoConfiguration.class);
    
    private final OptimizationV5Properties properties;
    
    public OptimizationV5AutoConfiguration(OptimizationV5Properties properties) {
        this.properties = properties;
        log.info("V5 Optimization AutoConfiguration initializing...");
    }
    
    /**
     * HeavyKeeper 热点检测器
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.heavy-keeper", name = "enabled", havingValue = "true", matchIfMissing = true)
    public HeavyKeeperHotKeyDetector heavyKeeperHotKeyDetector(MeterRegistry meterRegistry) {
        log.info("Creating HeavyKeeperHotKeyDetector bean");
        return new HeavyKeeperHotKeyDetector(meterRegistry);
    }
    
    /**
     * 智能分片路由器
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.shard", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SmartShardRouter smartShardRouter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        log.info("Creating SmartShardRouter bean");
        return new SmartShardRouter(redisTemplate, meterRegistry);
    }
    
    /**
     * 流量整形服务
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.traffic", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TrafficShapingService trafficShapingService(MeterRegistry meterRegistry) {
        log.info("Creating TrafficShapingService bean");
        return new TrafficShapingService(meterRegistry);
    }
    
    /**
     * 智能缓存预热器
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.warmer", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SmartCacheWarmer smartCacheWarmer(MeterRegistry meterRegistry) {
        log.info("Creating SmartCacheWarmer bean");
        return new SmartCacheWarmer(meterRegistry);
    }
    
    /**
     * 数据库访问优化器
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.database", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DatabaseAccessOptimizer databaseAccessOptimizer(MeterRegistry meterRegistry) {
        log.info("Creating DatabaseAccessOptimizer bean");
        return new DatabaseAccessOptimizer(meterRegistry);
    }
    
    /**
     * 增强版多级缓存 V5（主缓存服务）
     */
    @Bean
    @Primary
    public EnhancedMultiLevelCacheV5 enhancedMultiLevelCacheV5(
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
        log.info("Creating EnhancedMultiLevelCacheV5 bean");
        return new EnhancedMultiLevelCacheV5(
            l1CacheService, l2RedisService, l3MemcachedService,
            bloomFilterService, parallelCacheReader, resilienceService,
            hotKeyDetector, shardRouter, trafficShaping, cacheWarmer,
            meterRegistry
        );
    }
}

/**
 * V5 优化配置属性
 */
@ConfigurationProperties(prefix = "optimization.v5")
class OptimizationV5Properties {
    
    private boolean enabled = true;
    private boolean hotKeyDetectionEnabled = true;
    private boolean trafficShapingEnabled = true;
    private boolean smartShardEnabled = true;
    private boolean prefetchEnabled = true;
    private boolean adaptiveTtlEnabled = true;
    private long defaultTtlSeconds = 600;
    private long minTtlSeconds = 60;
    private long maxTtlSeconds = 7200;
    
    // HeavyKeeper 配置
    private HeavyKeeperConfig heavyKeeper = new HeavyKeeperConfig();
    
    // 分片配置
    private ShardConfig shard = new ShardConfig();
    
    // 流量整形配置
    private TrafficConfig traffic = new TrafficConfig();
    
    // 预热配置
    private WarmerConfig warmer = new WarmerConfig();
    
    // 数据库配置
    private DatabaseConfig database = new DatabaseConfig();
    
    // Getters and Setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isHotKeyDetectionEnabled() { return hotKeyDetectionEnabled; }
    public void setHotKeyDetectionEnabled(boolean hotKeyDetectionEnabled) { this.hotKeyDetectionEnabled = hotKeyDetectionEnabled; }
    public boolean isTrafficShapingEnabled() { return trafficShapingEnabled; }
    public void setTrafficShapingEnabled(boolean trafficShapingEnabled) { this.trafficShapingEnabled = trafficShapingEnabled; }
    public boolean isSmartShardEnabled() { return smartShardEnabled; }
    public void setSmartShardEnabled(boolean smartShardEnabled) { this.smartShardEnabled = smartShardEnabled; }
    public boolean isPrefetchEnabled() { return prefetchEnabled; }
    public void setPrefetchEnabled(boolean prefetchEnabled) { this.prefetchEnabled = prefetchEnabled; }
    public boolean isAdaptiveTtlEnabled() { return adaptiveTtlEnabled; }
    public void setAdaptiveTtlEnabled(boolean adaptiveTtlEnabled) { this.adaptiveTtlEnabled = adaptiveTtlEnabled; }
    public long getDefaultTtlSeconds() { return defaultTtlSeconds; }
    public void setDefaultTtlSeconds(long defaultTtlSeconds) { this.defaultTtlSeconds = defaultTtlSeconds; }
    public long getMinTtlSeconds() { return minTtlSeconds; }
    public void setMinTtlSeconds(long minTtlSeconds) { this.minTtlSeconds = minTtlSeconds; }
    public long getMaxTtlSeconds() { return maxTtlSeconds; }
    public void setMaxTtlSeconds(long maxTtlSeconds) { this.maxTtlSeconds = maxTtlSeconds; }
    public HeavyKeeperConfig getHeavyKeeper() { return heavyKeeper; }
    public void setHeavyKeeper(HeavyKeeperConfig heavyKeeper) { this.heavyKeeper = heavyKeeper; }
    public ShardConfig getShard() { return shard; }
    public void setShard(ShardConfig shard) { this.shard = shard; }
    public TrafficConfig getTraffic() { return traffic; }
    public void setTraffic(TrafficConfig traffic) { this.traffic = traffic; }
    public WarmerConfig getWarmer() { return warmer; }
    public void setWarmer(WarmerConfig warmer) { this.warmer = warmer; }
    public DatabaseConfig getDatabase() { return database; }
    public void setDatabase(DatabaseConfig database) { this.database = database; }
    
    /**
     * HeavyKeeper 配置
     */
    public static class HeavyKeeperConfig {
        private boolean enabled = true;
        private int depth = 4;
        private int width = 65536;
        private double decayRate = 1.08;
        private int topK = 100;
        private long hotThreshold = 1000;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDepth() { return depth; }
        public void setDepth(int depth) { this.depth = depth; }
        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }
        public double getDecayRate() { return decayRate; }
        public void setDecayRate(double decayRate) { this.decayRate = decayRate; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public long getHotThreshold() { return hotThreshold; }
        public void setHotThreshold(long hotThreshold) { this.hotThreshold = hotThreshold; }
    }
    
    /**
     * 分片配置
     */
    public static class ShardConfig {
        private boolean enabled = true;
        private int minCount = 4;
        private int maxCount = 64;
        private long scaleUpThreshold = 5000;
        private long scaleDownThreshold = 500;
        private int virtualNodes = 150;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMinCount() { return minCount; }
        public void setMinCount(int minCount) { this.minCount = minCount; }
        public int getMaxCount() { return maxCount; }
        public void setMaxCount(int maxCount) { this.maxCount = maxCount; }
        public long getScaleUpThreshold() { return scaleUpThreshold; }
        public void setScaleUpThreshold(long scaleUpThreshold) { this.scaleUpThreshold = scaleUpThreshold; }
        public long getScaleDownThreshold() { return scaleDownThreshold; }
        public void setScaleDownThreshold(long scaleDownThreshold) { this.scaleDownThreshold = scaleDownThreshold; }
        public int getVirtualNodes() { return virtualNodes; }
        public void setVirtualNodes(int virtualNodes) { this.virtualNodes = virtualNodes; }
    }
    
    /**
     * 流量整形配置
     */
    public static class TrafficConfig {
        private boolean enabled = true;
        private int leakyBucketRate = 10000;
        private int tokenBucketCapacity = 50000;
        private int tokenRefillRate = 10000;
        private int maxQueueSize = 10000;
        private long queueTimeoutMs = 100;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getLeakyBucketRate() { return leakyBucketRate; }
        public void setLeakyBucketRate(int leakyBucketRate) { this.leakyBucketRate = leakyBucketRate; }
        public int getTokenBucketCapacity() { return tokenBucketCapacity; }
        public void setTokenBucketCapacity(int tokenBucketCapacity) { this.tokenBucketCapacity = tokenBucketCapacity; }
        public int getTokenRefillRate() { return tokenRefillRate; }
        public void setTokenRefillRate(int tokenRefillRate) { this.tokenRefillRate = tokenRefillRate; }
        public int getMaxQueueSize() { return maxQueueSize; }
        public void setMaxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; }
        public long getQueueTimeoutMs() { return queueTimeoutMs; }
        public void setQueueTimeoutMs(long queueTimeoutMs) { this.queueTimeoutMs = queueTimeoutMs; }
    }
    
    /**
     * 预热配置
     */
    public static class WarmerConfig {
        private boolean enabled = true;
        private int startupBatchSize = 1000;
        private int parallelThreads = 16;
        private double prefetchThreshold = 0.7;
        private int maxPrefetchQueue = 10000;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getStartupBatchSize() { return startupBatchSize; }
        public void setStartupBatchSize(int startupBatchSize) { this.startupBatchSize = startupBatchSize; }
        public int getParallelThreads() { return parallelThreads; }
        public void setParallelThreads(int parallelThreads) { this.parallelThreads = parallelThreads; }
        public double getPrefetchThreshold() { return prefetchThreshold; }
        public void setPrefetchThreshold(double prefetchThreshold) { this.prefetchThreshold = prefetchThreshold; }
        public int getMaxPrefetchQueue() { return maxPrefetchQueue; }
        public void setMaxPrefetchQueue(int maxPrefetchQueue) { this.maxPrefetchQueue = maxPrefetchQueue; }
    }
    
    /**
     * 数据库配置
     */
    public static class DatabaseConfig {
        private boolean enabled = true;
        private int minPoolSize = 10;
        private int maxPoolSize = 100;
        private boolean adaptivePoolEnabled = true;
        private long slowQueryThresholdMs = 100;
        private boolean batchEnabled = true;
        private int batchSize = 100;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMinPoolSize() { return minPoolSize; }
        public void setMinPoolSize(int minPoolSize) { this.minPoolSize = minPoolSize; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public boolean isAdaptivePoolEnabled() { return adaptivePoolEnabled; }
        public void setAdaptivePoolEnabled(boolean adaptivePoolEnabled) { this.adaptivePoolEnabled = adaptivePoolEnabled; }
        public long getSlowQueryThresholdMs() { return slowQueryThresholdMs; }
        public void setSlowQueryThresholdMs(long slowQueryThresholdMs) { this.slowQueryThresholdMs = slowQueryThresholdMs; }
        public boolean isBatchEnabled() { return batchEnabled; }
        public void setBatchEnabled(boolean batchEnabled) { this.batchEnabled = batchEnabled; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }
}
