package com.ecommerce.cache.optimization.v10;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * V10量子优化模块自动装配
 */
@Configuration
@ConditionalOnProperty(prefix = "optimization.v10", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OptimizationV10Properties.class)
@ComponentScan(basePackageClasses = OptimizationV10AutoConfiguration.class)
public class OptimizationV10AutoConfiguration {
    
}
