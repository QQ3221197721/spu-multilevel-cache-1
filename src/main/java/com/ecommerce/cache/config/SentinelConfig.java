package com.ecommerce.cache.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sentinel 流量控制配置
 * <p>
 * 启用 Sentinel 注解支持（@SentinelResource），
 * 规则加载由 CacheResilienceService 的 @PostConstruct 完成。
 * 生产环境可通过 Sentinel Dashboard 动态推送规则。
 */
@Configuration
public class SentinelConfig {

    /**
     * 启用 @SentinelResource 注解的 AOP 切面
     */
    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }
}
