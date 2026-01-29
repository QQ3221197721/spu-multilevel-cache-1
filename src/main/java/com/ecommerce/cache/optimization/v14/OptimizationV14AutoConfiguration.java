package com.ecommerce.cache.optimization.v14;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * V14超神优化模块自动装配配置
 */
@Configuration
@ConditionalOnProperty(prefix = "cache.optimization.v14", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OptimizationV14Properties.class)
@ComponentScan(basePackageClasses = OptimizationV14AutoConfiguration.class)
public class OptimizationV14AutoConfiguration {
}
