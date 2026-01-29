package com.ecommerce.cache.optimization.v6;

import com.ecommerce.cache.service.*;
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

/**
 * V6 优化模块自动配置
 * 
 * 自动装配所有 V6 优化组件，提供开箱即用的极致性能
 * 
 * 性能目标:
 * - QPS: 15W+
 * - TP99: < 20ms
 * - 命中率: > 99%
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OptimizationV6Properties.class)
@ConditionalOnProperty(prefix = "optimization.v6", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OptimizationV6AutoConfiguration {
    
    private static final Logger log = LoggerFactory.getLogger(OptimizationV6AutoConfiguration.class);
    
    private final OptimizationV6Properties properties;
    
    public OptimizationV6AutoConfiguration(OptimizationV6Properties properties) {
        this.properties = properties;
        log.info("V6 Optimization AutoConfiguration initializing - Ultimate Performance Edition");
    }
    
    /**
     * 无锁缓存引擎
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.v6.lock-free", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LockFreeCacheEngine lockFreeCacheEngine(MeterRegistry meterRegistry) {
        log.info("Creating LockFreeCacheEngine bean");
        return new LockFreeCacheEngine(meterRegistry);
    }
    
    /**
     * 自适应内存管理器
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.v6.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AdaptiveMemoryManager adaptiveMemoryManager(MeterRegistry meterRegistry) {
        log.info("Creating AdaptiveMemoryManager bean");
        return new AdaptiveMemoryManager(meterRegistry);
    }
    
    /**
     * 智能预测预取引擎
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.v6.prefetch", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PredictivePrefetchEngine predictivePrefetchEngine(MeterRegistry meterRegistry) {
        log.info("Creating PredictivePrefetchEngine bean");
        return new PredictivePrefetchEngine(meterRegistry);
    }
    
    /**
     * 动态热点迁移器
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.v6.hotspot", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DynamicHotspotMigrator dynamicHotspotMigrator(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry) {
        log.info("Creating DynamicHotspotMigrator bean");
        return new DynamicHotspotMigrator(redisTemplate, meterRegistry);
    }
    
    /**
     * 自适应熔断器 V6
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.v6.circuit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AdaptiveCircuitBreakerV6 adaptiveCircuitBreakerV6(MeterRegistry meterRegistry) {
        log.info("Creating AdaptiveCircuitBreakerV6 bean");
        return new AdaptiveCircuitBreakerV6(meterRegistry);
    }
    
    /**
     * 增量同步引擎
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.v6.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
    public IncrementalSyncEngine incrementalSyncEngine(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry) {
        log.info("Creating IncrementalSyncEngine bean");
        return new IncrementalSyncEngine(redisTemplate, meterRegistry);
    }
    
    /**
     * 缓存健康监测器 V6
     */
    @Bean
    @ConditionalOnProperty(prefix = "optimization.v6.health", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CacheHealthMonitorV6 cacheHealthMonitorV6(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            AdaptiveMemoryManager memoryManager) {
        log.info("Creating CacheHealthMonitorV6 bean");
        return new CacheHealthMonitorV6(redisTemplate, meterRegistry, memoryManager);
    }
    
    /**
     * 智能缓存协同引擎 V6（主缓存服务）
     */
    @Bean
    @Primary
    public CacheCoordinatorV6 cacheCoordinatorV6(
            L1CacheService l1CacheService,
            L2RedisService l2RedisService,
            L3MemcachedService l3MemcachedService,
            BloomFilterService bloomFilterService,
            CacheResilienceService resilienceService,
            MeterRegistry meterRegistry,
            LockFreeCacheEngine lockFreeEngine,
            AdaptiveMemoryManager memoryManager,
            PredictivePrefetchEngine prefetchEngine,
            AdaptiveCircuitBreakerV6 circuitBreaker) {
        log.info("Creating CacheCoordinatorV6 bean - Primary Cache Service");
        
        CacheCoordinatorV6 coordinator = new CacheCoordinatorV6(
            l1CacheService, l2RedisService, l3MemcachedService,
            bloomFilterService, resilienceService, meterRegistry
        );
        
        // 注入V6组件
        coordinator.setLockFreeEngine(lockFreeEngine);
        coordinator.setMemoryManager(memoryManager);
        coordinator.setPrefetchEngine(prefetchEngine);
        coordinator.setCircuitBreaker(circuitBreaker);
        
        return coordinator;
    }
}

/**
 * V6 优化配置属性
 */
@ConfigurationProperties(prefix = "optimization.v6")
class OptimizationV6Properties {
    
    private boolean enabled = true;
    private boolean smartRoutingEnabled = true;
    private boolean predictiveEnabled = true;
    private boolean adaptiveTtlEnabled = true;
    private long defaultTtlSeconds = 600;
    
    // 无锁缓存配置
    private LockFreeConfig lockFree = new LockFreeConfig();
    
    // 内存管理配置
    private MemoryConfig memory = new MemoryConfig();
    
    // 预取配置
    private PrefetchConfig prefetch = new PrefetchConfig();
    
    // 热点迁移配置
    private HotspotConfig hotspot = new HotspotConfig();
    
    // 熔断配置
    private CircuitConfig circuit = new CircuitConfig();
    
    // 同步配置
    private SyncConfig sync = new SyncConfig();
    
    // 健康检查配置
    private HealthConfig health = new HealthConfig();
    
    // Getters and Setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isSmartRoutingEnabled() { return smartRoutingEnabled; }
    public void setSmartRoutingEnabled(boolean smartRoutingEnabled) { this.smartRoutingEnabled = smartRoutingEnabled; }
    public boolean isPredictiveEnabled() { return predictiveEnabled; }
    public void setPredictiveEnabled(boolean predictiveEnabled) { this.predictiveEnabled = predictiveEnabled; }
    public boolean isAdaptiveTtlEnabled() { return adaptiveTtlEnabled; }
    public void setAdaptiveTtlEnabled(boolean adaptiveTtlEnabled) { this.adaptiveTtlEnabled = adaptiveTtlEnabled; }
    public long getDefaultTtlSeconds() { return defaultTtlSeconds; }
    public void setDefaultTtlSeconds(long defaultTtlSeconds) { this.defaultTtlSeconds = defaultTtlSeconds; }
    public LockFreeConfig getLockFree() { return lockFree; }
    public void setLockFree(LockFreeConfig lockFree) { this.lockFree = lockFree; }
    public MemoryConfig getMemory() { return memory; }
    public void setMemory(MemoryConfig memory) { this.memory = memory; }
    public PrefetchConfig getPrefetch() { return prefetch; }
    public void setPrefetch(PrefetchConfig prefetch) { this.prefetch = prefetch; }
    public HotspotConfig getHotspot() { return hotspot; }
    public void setHotspot(HotspotConfig hotspot) { this.hotspot = hotspot; }
    public CircuitConfig getCircuit() { return circuit; }
    public void setCircuit(CircuitConfig circuit) { this.circuit = circuit; }
    public SyncConfig getSync() { return sync; }
    public void setSync(SyncConfig sync) { this.sync = sync; }
    public HealthConfig getHealth() { return health; }
    public void setHealth(HealthConfig health) { this.health = health; }
    
    /**
     * 无锁缓存配置
     */
    public static class LockFreeConfig {
        private boolean enabled = true;
        private int segmentCount = 256;
        private int segmentCapacity = 1024;
        private long cleanupIntervalMs = 1000;
        private int maxRetries = 10;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getSegmentCount() { return segmentCount; }
        public void setSegmentCount(int segmentCount) { this.segmentCount = segmentCount; }
        public int getSegmentCapacity() { return segmentCapacity; }
        public void setSegmentCapacity(int segmentCapacity) { this.segmentCapacity = segmentCapacity; }
        public long getCleanupIntervalMs() { return cleanupIntervalMs; }
        public void setCleanupIntervalMs(long cleanupIntervalMs) { this.cleanupIntervalMs = cleanupIntervalMs; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }
    
    /**
     * 内存管理配置
     */
    public static class MemoryConfig {
        private boolean enabled = true;
        private double warningThreshold = 0.70;
        private double criticalThreshold = 0.85;
        private double emergencyThreshold = 0.95;
        private double targetUtilization = 0.75;
        private long checkIntervalMs = 500;
        private int evictionBatchSize = 100;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getWarningThreshold() { return warningThreshold; }
        public void setWarningThreshold(double warningThreshold) { this.warningThreshold = warningThreshold; }
        public double getCriticalThreshold() { return criticalThreshold; }
        public void setCriticalThreshold(double criticalThreshold) { this.criticalThreshold = criticalThreshold; }
        public double getEmergencyThreshold() { return emergencyThreshold; }
        public void setEmergencyThreshold(double emergencyThreshold) { this.emergencyThreshold = emergencyThreshold; }
        public double getTargetUtilization() { return targetUtilization; }
        public void setTargetUtilization(double targetUtilization) { this.targetUtilization = targetUtilization; }
        public long getCheckIntervalMs() { return checkIntervalMs; }
        public void setCheckIntervalMs(long checkIntervalMs) { this.checkIntervalMs = checkIntervalMs; }
        public int getEvictionBatchSize() { return evictionBatchSize; }
        public void setEvictionBatchSize(int evictionBatchSize) { this.evictionBatchSize = evictionBatchSize; }
    }
    
    /**
     * 预取配置
     */
    public static class PrefetchConfig {
        private boolean enabled = true;
        private int maxConcurrent = 50;
        private double threshold = 0.6;
        private int markovDepth = 3;
        private double associationMinSupport = 0.1;
        private int windowSizeMinutes = 30;
        private int maxPrefetchQueue = 5000;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
        public int getMarkovDepth() { return markovDepth; }
        public void setMarkovDepth(int markovDepth) { this.markovDepth = markovDepth; }
        public double getAssociationMinSupport() { return associationMinSupport; }
        public void setAssociationMinSupport(double associationMinSupport) { this.associationMinSupport = associationMinSupport; }
        public int getWindowSizeMinutes() { return windowSizeMinutes; }
        public void setWindowSizeMinutes(int windowSizeMinutes) { this.windowSizeMinutes = windowSizeMinutes; }
        public int getMaxPrefetchQueue() { return maxPrefetchQueue; }
        public void setMaxPrefetchQueue(int maxPrefetchQueue) { this.maxPrefetchQueue = maxPrefetchQueue; }
    }
    
    /**
     * 热点迁移配置
     */
    public static class HotspotConfig {
        private boolean enabled = true;
        private long detectionIntervalMs = 1000;
        private long hotThreshold = 1000;
        private long coldThreshold = 10;
        private int migrationBatchSize = 100;
        private int shardCount = 16;
        private double decayFactor = 0.9;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getDetectionIntervalMs() { return detectionIntervalMs; }
        public void setDetectionIntervalMs(long detectionIntervalMs) { this.detectionIntervalMs = detectionIntervalMs; }
        public long getHotThreshold() { return hotThreshold; }
        public void setHotThreshold(long hotThreshold) { this.hotThreshold = hotThreshold; }
        public long getColdThreshold() { return coldThreshold; }
        public void setColdThreshold(long coldThreshold) { this.coldThreshold = coldThreshold; }
        public int getMigrationBatchSize() { return migrationBatchSize; }
        public void setMigrationBatchSize(int migrationBatchSize) { this.migrationBatchSize = migrationBatchSize; }
        public int getShardCount() { return shardCount; }
        public void setShardCount(int shardCount) { this.shardCount = shardCount; }
        public double getDecayFactor() { return decayFactor; }
        public void setDecayFactor(double decayFactor) { this.decayFactor = decayFactor; }
    }
    
    /**
     * 熔断配置
     */
    public static class CircuitConfig {
        private boolean enabled = true;
        private double failureThreshold = 0.5;
        private double slowCallThreshold = 0.8;
        private long slowCallDurationMs = 500;
        private int minimumCalls = 10;
        private int windowSizeSeconds = 60;
        private int waitDurationSeconds = 30;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(double failureThreshold) { this.failureThreshold = failureThreshold; }
        public double getSlowCallThreshold() { return slowCallThreshold; }
        public void setSlowCallThreshold(double slowCallThreshold) { this.slowCallThreshold = slowCallThreshold; }
        public long getSlowCallDurationMs() { return slowCallDurationMs; }
        public void setSlowCallDurationMs(long slowCallDurationMs) { this.slowCallDurationMs = slowCallDurationMs; }
        public int getMinimumCalls() { return minimumCalls; }
        public void setMinimumCalls(int minimumCalls) { this.minimumCalls = minimumCalls; }
        public int getWindowSizeSeconds() { return windowSizeSeconds; }
        public void setWindowSizeSeconds(int windowSizeSeconds) { this.windowSizeSeconds = windowSizeSeconds; }
        public int getWaitDurationSeconds() { return waitDurationSeconds; }
        public void setWaitDurationSeconds(int waitDurationSeconds) { this.waitDurationSeconds = waitDurationSeconds; }
    }
    
    /**
     * 同步配置
     */
    public static class SyncConfig {
        private boolean enabled = true;
        private int batchSize = 100;
        private long flushIntervalMs = 50;
        private int maxPending = 10000;
        private int parallelWorkers = 4;
        private int retryCount = 3;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public long getFlushIntervalMs() { return flushIntervalMs; }
        public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
        public int getMaxPending() { return maxPending; }
        public void setMaxPending(int maxPending) { this.maxPending = maxPending; }
        public int getParallelWorkers() { return parallelWorkers; }
        public void setParallelWorkers(int parallelWorkers) { this.parallelWorkers = parallelWorkers; }
        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    }
    
    /**
     * 健康检查配置
     */
    public static class HealthConfig {
        private boolean enabled = true;
        private long checkIntervalMs = 5000;
        private long timeoutMs = 3000;
        private int failureThreshold = 3;
        private int recoveryThreshold = 2;
        private int alertCooldownSeconds = 300;
        private boolean selfHealingEnabled = true;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getCheckIntervalMs() { return checkIntervalMs; }
        public void setCheckIntervalMs(long checkIntervalMs) { this.checkIntervalMs = checkIntervalMs; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public int getRecoveryThreshold() { return recoveryThreshold; }
        public void setRecoveryThreshold(int recoveryThreshold) { this.recoveryThreshold = recoveryThreshold; }
        public int getAlertCooldownSeconds() { return alertCooldownSeconds; }
        public void setAlertCooldownSeconds(int alertCooldownSeconds) { this.alertCooldownSeconds = alertCooldownSeconds; }
        public boolean isSelfHealingEnabled() { return selfHealingEnabled; }
        public void setSelfHealingEnabled(boolean selfHealingEnabled) { this.selfHealingEnabled = selfHealingEnabled; }
    }
}
