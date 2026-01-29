package com.ecommerce.cache;

import com.ecommerce.cache.optimization.OptimizationEnhancementAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Import;

/**
 * SPU缓存服务增强版启动类
 * 
 * 集成了所有优化组件，包括V17全面优化增强模块
 */
@SpringBootApplication(scanBasePackages = {
    "com.ecommerce.cache.config",
    "com.ecommerce.cache.constant", 
    "com.ecommerce.cache.consumer",
    "com.ecommerce.cache.controller", 
    "com.ecommerce.cache.dto",
    "com.ecommerce.cache.entity", 
    "com.ecommerce.cache.exception",
    "com.ecommerce.cache.health", 
    "com.ecommerce.cache.init",
    "com.ecommerce.cache.monitor", 
    "com.ecommerce.cache.optimization",
    "com.ecommerce.cache.repository", 
    "com.ecommerce.cache.service",
    "com.ecommerce.gateway"
})
@EnableCaching
@Import({OptimizationEnhancementAutoConfiguration.class})
public class SpuCacheEnhancedApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SpuCacheEnhancedApplication.class);
        app.run(args);
    }
}