package com.ecommerce.cache.optimization.v15;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * V15终极神王优化模块自动装配配置
 */
@Configuration
@ConditionalOnProperty(prefix = "cache.optimization.v15", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OptimizationV15Properties.class)
@ComponentScan(basePackageClasses = OptimizationV15AutoConfiguration.class)
public class OptimizationV15AutoConfiguration {
}
