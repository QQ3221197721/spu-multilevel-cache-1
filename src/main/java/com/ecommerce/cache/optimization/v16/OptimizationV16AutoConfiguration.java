package com.ecommerce.cache.optimization.v16;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * V16量子纠错优化模块自动配置
 * 
 * 根据配置条件自动装配V16量子纠错相关组件
 */
@Configuration
@EnableConfigurationProperties(OptimizationV16Properties.class)
@ConditionalOnProperty(name = "v16.enabled", havingValue = "true", matchIfMissing = true)
public class OptimizationV16AutoConfiguration {

    /**
     * 创建表面码缓存层
     */
    @Bean
    @ConditionalOnProperty(name = "v16.surface-code-cache.enabled", havingValue = "true", matchIfMissing = true)
    public SurfaceCodeCacheLayer surfaceCodeCacheLayer(MeterRegistry meterRegistry) {
        return new SurfaceCodeCacheLayer(meterRegistry);
    }

    /**
     * 创建色码缓存层
     */
    @Bean
    @ConditionalOnProperty(name = "v16.color-code-cache.enabled", havingValue = "true", matchIfMissing = true)
    public ColorCodeCacheLayer colorCodeCacheLayer(MeterRegistry meterRegistry) {
        return new ColorCodeCacheLayer(meterRegistry);
    }

    /**
     * 创建拓扑保护层
     */
    @Bean
    @ConditionalOnProperty(name = "v16.topological-protection.enabled", havingValue = "true", matchIfMissing = true)
    public TopologicalProtectionLayer topologicalProtectionLayer(MeterRegistry meterRegistry) {
        return new TopologicalProtectionLayer(meterRegistry);
    }

    /**
     * 创建动态纠错调度器
     */
    @Bean
    @ConditionalOnProperty(name = "v16.dynamic-scheduler.enabled", havingValue = "true", matchIfMissing = true)
    public DynamicErrorCorrectionScheduler dynamicErrorCorrectionScheduler(MeterRegistry meterRegistry) {
        return new DynamicErrorCorrectionScheduler(meterRegistry);
    }

    /**
     * 创建V16优化控制器
     */
    @Bean
    @ConditionalOnProperty(name = "v16.enabled", havingValue = "true", matchIfMissing = true)
    public OptimizationV16Controller optimizationV16Controller() {
        return new OptimizationV16Controller();
    }

    /**
     * 创建V16优化属性
     */
    @Bean
    public OptimizationV16Properties optimizationV16Properties() {
        return new OptimizationV16Properties();
    }
}