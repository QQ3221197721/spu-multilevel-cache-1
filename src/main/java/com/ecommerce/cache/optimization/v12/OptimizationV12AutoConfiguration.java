package com.ecommerce.cache.optimization.v12;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * V12终极进化优化模块自动装配
 */
@Configuration
@ConditionalOnProperty(prefix = "optimization.v12", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OptimizationV12Properties.class)
@ComponentScan(basePackageClasses = OptimizationV12AutoConfiguration.class)
public class OptimizationV12AutoConfiguration {
    
}
