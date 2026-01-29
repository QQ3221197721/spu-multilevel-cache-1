package com.ecommerce.cache.optimization.v13;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * V13终极巅峰优化模块自动装配配置
 */
@Configuration
@ConditionalOnProperty(prefix = "cache.optimization.v13", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OptimizationV13Properties.class)
@ComponentScan(basePackageClasses = OptimizationV13AutoConfiguration.class)
public class OptimizationV13AutoConfiguration {
}
