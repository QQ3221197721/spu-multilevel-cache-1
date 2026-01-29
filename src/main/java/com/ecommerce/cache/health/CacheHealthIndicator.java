package com.ecommerce.cache.health;

import com.ecommerce.cache.service.L1CacheService;
import com.ecommerce.cache.service.L2RedisService;
import com.ecommerce.cache.service.L3MemcachedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 多级缓存健康检查
 */
@Slf4j
@Component("cacheHealthIndicator")
@RequiredArgsConstructor
public class CacheHealthIndicator implements HealthIndicator {
    
    private final L1CacheService l1CacheService;
    private final L2RedisService l2RedisService;
    private final L3MemcachedService l3MemcachedService;
    
    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();
        boolean allHealthy = true;
        
        // 1. 检查 L1 Caffeine
        try {
            boolean l1Healthy = checkL1Cache();
            details.put("l1_caffeine", l1Healthy ? "UP" : "DOWN");
            details.put("l1_size", l1CacheService.size());
            if (!l1Healthy) allHealthy = false;
        } catch (Exception e) {
            log.error("L1 cache health check failed", e);
            details.put("l1_caffeine", "DOWN");
            details.put("l1_error", e.getMessage());
            allHealthy = false;
        }
        
        // 2. 检查 L2 Redis
        try {
            boolean l2Healthy = checkL2Redis();
            details.put("l2_redis", l2Healthy ? "UP" : "DOWN");
            if (!l2Healthy) allHealthy = false;
        } catch (Exception e) {
            log.error("L2 Redis health check failed", e);
            details.put("l2_redis", "DOWN");
            details.put("l2_error", e.getMessage());
            allHealthy = false;
        }
        
        // 3. 检查 L3 Memcached
        try {
            boolean l3Healthy = checkL3Memcached();
            details.put("l3_memcached", l3Healthy ? "UP" : "DOWN");
            if (!l3Healthy) allHealthy = false;
        } catch (Exception e) {
            log.error("L3 Memcached health check failed", e);
            details.put("l3_memcached", "DOWN");
            details.put("l3_error", e.getMessage());
            allHealthy = false;
        }
        
        if (allHealthy) {
            return Health.up().withDetails(details).build();
        } else {
            return Health.down().withDetails(details).build();
        }
    }
    
    private boolean checkL1Cache() {
        // L1 是本地缓存，只要 JVM 正常就健康
        return l1CacheService != null;
    }
    
    private boolean checkL2Redis() {
        // 通过执行简单命令检测 Redis 连通性
        String testKey = "health:check:redis";
        String testValue = String.valueOf(System.currentTimeMillis());
        
        l2RedisService.set(testKey, testValue, 10);
        String retrieved = l2RedisService.get(testKey);
        
        return testValue.equals(retrieved);
    }
    
    private boolean checkL3Memcached() {
        // 通过执行简单命令检测 Memcached 连通性
        String testKey = "health:check:memcached";
        String testValue = String.valueOf(System.currentTimeMillis());
        
        l3MemcachedService.set(testKey, testValue, 10);
        String retrieved = l3MemcachedService.get(testKey);
        
        return testValue.equals(retrieved);
    }
}
