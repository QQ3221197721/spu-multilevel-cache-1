package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 优化增强自动配置
 * 
 * 配置所有增强版优化组件，包括缓存协调器、性能分析器等
 */
@Configuration
@ConditionalOnProperty(name = "optimization.enhanced.enabled", havingValue = "true", matchIfMissing = true)
public class OptimizationEnhancementAutoConfiguration {

    /**
     * 创建增强版缓存协调器
     */
    @Bean
    @ConditionalOnMissingBean(EnhancedCacheCoordinator.class)
    public EnhancedCacheCoordinator enhancedCacheCoordinator(MeterRegistry meterRegistry) {
        return new EnhancedCacheCoordinator(meterRegistry);
    }

    /**
     * 创建高级性能分析器
     */
    @Bean
    @ConditionalOnMissingBean(AdvancedPerformanceAnalyzer.class)
    public AdvancedPerformanceAnalyzer advancedPerformanceAnalyzer(MeterRegistry meterRegistry) {
        return new AdvancedPerformanceAnalyzer(meterRegistry);
    }

    /**
     * 创建缓存优化中枢
     */
    @Bean
    @ConditionalOnMissingBean(CacheOptimizationHub.class)
    public CacheOptimizationHub cacheOptimizationHub(MeterRegistry meterRegistry) {
        return new CacheOptimizationHub(meterRegistry);
    }
}