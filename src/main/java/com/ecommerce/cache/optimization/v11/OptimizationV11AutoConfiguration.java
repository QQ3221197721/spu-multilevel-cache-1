package com.ecommerce.cache.optimization.v11;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * V11超越极限优化模块自动装配
 */
@Configuration
@ConditionalOnProperty(prefix = "optimization.v11", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OptimizationV11Properties.class)
@ComponentScan(basePackageClasses = OptimizationV11AutoConfiguration.class)
public class OptimizationV11AutoConfiguration {
    
}
