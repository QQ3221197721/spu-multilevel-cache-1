package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;

/**
 * V7 终极优化模块自动装配配置
 * 
 * 按依赖顺序初始化所有V7组件:
 * 1. OptimizationV7Properties - 配置属性
 * 2. ZeroAllocationPipeline - 零分配管道(无依赖)
 * 3. AIDrivenCachePredictor - AI预测引擎(无依赖)
 * 4. SmartDegradationOrchestrator - 降级编排器(无依赖)
 * 5. TopologyAwareRouter - 拓扑路由器(无依赖)
 * 6. RealTimeDiagnosticsEngine - 诊断引擎(无依赖)
 * 7. DistributedLockEnhancer - 锁增强器(依赖Redisson)
 * 8. CacheCoordinatorV7 - 协调器(依赖以上所有)
 * 9. OptimizationV7Controller - REST控制器
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(OptimizationV7Properties.class)
@ConditionalOnProperty(prefix = "optimization.v7", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OptimizationV7AutoConfiguration {
    
    /**
     * V7配置属性
     */
    @Bean
    @ConditionalOnMissingBean
    public OptimizationV7Properties optimizationV7Properties() {
        log.info("[V7自动装配] 创建配置属性Bean");
        return new OptimizationV7Properties();
    }
    
    /**
     * 零分配数据管道
     */
    @Bean
    @Order(1)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "optimization.v7", name = "zero-allocation-enabled", havingValue = "true", matchIfMissing = true)
    public ZeroAllocationPipeline zeroAllocationPipeline(
            OptimizationV7Properties properties,
            MeterRegistry meterRegistry) {
        log.info("[V7自动装配] 创建零分配管道Bean");
        return new ZeroAllocationPipeline(properties, meterRegistry);
    }
    
    /**
     * AI驱动缓存预测引擎
     */
    @Bean
    @Order(2)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "optimization.v7", name = "ai-prediction-enabled", havingValue = "true", matchIfMissing = true)
    public AIDrivenCachePredictor aiDrivenCachePredictor(
            OptimizationV7Properties properties,
            MeterRegistry meterRegistry) {
        log.info("[V7自动装配] 创建AI预测引擎Bean");
        return new AIDrivenCachePredictor(properties, meterRegistry);
    }
    
    /**
     * 智能降级编排器
     */
    @Bean
    @Order(3)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "optimization.v7", name = "smart-degradation-enabled", havingValue = "true", matchIfMissing = true)
    public SmartDegradationOrchestrator smartDegradationOrchestrator(
            OptimizationV7Properties properties,
            MeterRegistry meterRegistry) {
        log.info("[V7自动装配] 创建智能降级编排器Bean");
        return new SmartDegradationOrchestrator(properties, meterRegistry);
    }
    
    /**
     * 拓扑感知路由器
     */
    @Bean
    @Order(4)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "optimization.v7", name = "topology-aware-enabled", havingValue = "true", matchIfMissing = true)
    public TopologyAwareRouter topologyAwareRouter(
            OptimizationV7Properties properties,
            MeterRegistry meterRegistry) {
        log.info("[V7自动装配] 创建拓扑感知路由器Bean");
        return new TopologyAwareRouter(properties, meterRegistry);
    }
    
    /**
     * 实时性能诊断引擎
     */
    @Bean
    @Order(5)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "optimization.v7", name = "realtime-diagnostics-enabled", havingValue = "true", matchIfMissing = true)
    public RealTimeDiagnosticsEngine realTimeDiagnosticsEngine(
            OptimizationV7Properties properties,
            MeterRegistry meterRegistry) {
        log.info("[V7自动装配] 创建实时诊断引擎Bean");
        return new RealTimeDiagnosticsEngine(properties, meterRegistry);
    }
    
    /**
     * 分布式锁增强器
     */
    @Bean
    @Order(6)
    @ConditionalOnMissingBean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnProperty(prefix = "optimization.v7", name = "distributed-lock-enhanced", havingValue = "true", matchIfMissing = true)
    public DistributedLockEnhancer distributedLockEnhancer(
            OptimizationV7Properties properties,
            MeterRegistry meterRegistry,
            RedissonClient redissonClient) {
        log.info("[V7自动装配] 创建分布式锁增强器Bean");
        return new DistributedLockEnhancer(properties, meterRegistry, redissonClient);
    }
    
    /**
     * V7缓存协调器
     */
    @Bean
    @Order(10)
    @ConditionalOnMissingBean
    @DependsOn({
        "aiDrivenCachePredictor",
        "zeroAllocationPipeline", 
        "smartDegradationOrchestrator",
        "topologyAwareRouter",
        "realTimeDiagnosticsEngine",
        "distributedLockEnhancer"
    })
    public CacheCoordinatorV7 cacheCoordinatorV7(
            OptimizationV7Properties properties,
            MeterRegistry meterRegistry,
            AIDrivenCachePredictor aiPredictor,
            ZeroAllocationPipeline zeroAllocPipeline,
            SmartDegradationOrchestrator degradationOrchestrator,
            TopologyAwareRouter topologyRouter,
            RealTimeDiagnosticsEngine diagnosticsEngine,
            DistributedLockEnhancer lockEnhancer) {
        log.info("[V7自动装配] 创建V7缓存协调器Bean");
        return new CacheCoordinatorV7(
            properties,
            meterRegistry,
            aiPredictor,
            zeroAllocPipeline,
            degradationOrchestrator,
            topologyRouter,
            diagnosticsEngine,
            lockEnhancer
        );
    }
    
    /**
     * V7 REST控制器
     */
    @Bean
    @Order(20)
    @ConditionalOnMissingBean
    @DependsOn("cacheCoordinatorV7")
    public OptimizationV7Controller optimizationV7Controller(
            OptimizationV7Properties properties,
            CacheCoordinatorV7 coordinator,
            AIDrivenCachePredictor aiPredictor,
            ZeroAllocationPipeline zeroAllocPipeline,
            SmartDegradationOrchestrator degradationOrchestrator,
            TopologyAwareRouter topologyRouter,
            RealTimeDiagnosticsEngine diagnosticsEngine,
            DistributedLockEnhancer lockEnhancer) {
        log.info("[V7自动装配] 创建V7 REST控制器Bean");
        return new OptimizationV7Controller(
            properties,
            coordinator,
            aiPredictor,
            zeroAllocPipeline,
            degradationOrchestrator,
            topologyRouter,
            diagnosticsEngine,
            lockEnhancer
        );
    }
}
