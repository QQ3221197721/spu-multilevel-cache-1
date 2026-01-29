package com.ecommerce.cache.service;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 缓存击穿保护器
 * 使用 Redisson 公平锁 + DCL 双重检查防止缓存击穿
 * 锁等待时间：3s，锁持有时间：10s
 */
@Service
public class CacheBreakdownProtector {
    
    private static final Logger log = LoggerFactory.getLogger(CacheBreakdownProtector.class);
    
    // 锁前缀
    private static final String LOCK_PREFIX = "cache:lock:";
    // 等待锁超时（毫秒）
    private static final long WAIT_TIME_MS = 3000;
    // 持有锁超时（毫秒）
    private static final long LEASE_TIME_MS = 10000;
    
    private final RedissonClient redissonClient;
    private final L1CacheService l1CacheService;
    private final L2RedisService l2RedisService;

    public CacheBreakdownProtector(RedissonClient redissonClient,
                                    L1CacheService l1CacheService,
                                    L2RedisService l2RedisService) {
        this.redissonClient = redissonClient;
        this.l1CacheService = l1CacheService;
        this.l2RedisService = l2RedisService;
    }

    /**
     * 防击穿获取数据（DCL 双重检查锁）
     * @param key 缓存 Key
     * @param dbLoader 回源 DB 加载函数
     * @param ttlSeconds 缓存过期时间
     * @return 数据
     */
    public String getWithBreakdownProtection(String key, Supplier<String> dbLoader, long ttlSeconds) {
        // ========== 第一次检查：直接读缓存 ==========
        String value = l1CacheService.get(key);
        if (value != null) {
            return value;
        }
        
        value = l2RedisService.get(key);
        if (value != null) {
            l1CacheService.put(key, value);
            return value;
        }
        
        // ========== 缓存未命中，加锁回源 ==========
        RLock lock = redissonClient.getFairLock(LOCK_PREFIX + key);
        
        try {
            // 尝试获取公平锁
            boolean acquired = lock.tryLock(WAIT_TIME_MS, LEASE_TIME_MS, TimeUnit.MILLISECONDS);
            
            if (!acquired) {
                // 获取锁超时，返回降级数据或抛异常
                log.warn("Failed to acquire lock for key: {}, timeout: {}ms", key, WAIT_TIME_MS);
                return getDefaultValue(key);
            }
            
            try {
                // ========== 第二次检查：获取锁后再次检查缓存 ==========
                // （可能其他线程已经加载完成）
                value = l2RedisService.get(key);
                if (value != null) {
                    l1CacheService.put(key, value);
                    log.debug("Cache hit after acquiring lock: {}", key);
                    return value;
                }
                
                // ========== 回源 DB ==========
                log.info("Loading from DB with lock, key: {}", key);
                long startTime = System.currentTimeMillis();
                
                value = dbLoader.get();
                
                long duration = System.currentTimeMillis() - startTime;
                log.info("DB load completed, key: {}, duration: {}ms, found: {}", 
                    key, duration, value != null);
                
                if (value != null) {
                    // 写入缓存
                    l2RedisService.set(key, value, ttlSeconds);
                    l1CacheService.put(key, value);
                } else {
                    // 写入空值缓存，防止穿透（短过期时间）
                    l2RedisService.set(key, "", 60);
                    log.debug("Stored empty value for key: {}", key);
                }
                
                return value;
                
            } finally {
                // 释放锁
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.debug("Lock released for key: {}", key);
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Lock interrupted for key: {}", key, e);
            return getDefaultValue(key);
        } catch (Exception e) {
            log.error("Error during breakdown protection for key: {}", key, e);
            return getDefaultValue(key);
        }
    }

    /**
     * 带超时的加锁回源
     */
    public String getWithTimeout(String key, Supplier<String> dbLoader, 
                                  long ttlSeconds, long timeoutMs) {
        RLock lock = redissonClient.getFairLock(LOCK_PREFIX + key);
        
        try {
            boolean acquired = lock.tryLock(timeoutMs, LEASE_TIME_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                return null;
            }
            
            try {
                String value = l2RedisService.get(key);
                if (value != null) {
                    return value;
                }
                
                value = dbLoader.get();
                if (value != null) {
                    l2RedisService.set(key, value, ttlSeconds);
                }
                return value;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 降级默认值
     * 可以根据业务需求返回兜底数据
     */
    private String getDefaultValue(String key) {
        // 返回降级数据，如空对象、兜底数据等
        log.warn("Returning default value for key: {}", key);
        return null;
    }

    /**
     * 手动释放锁（用于异常情况）
     */
    public void releaseLock(String key) {
        RLock lock = redissonClient.getFairLock(LOCK_PREFIX + key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("Lock manually released for key: {}", key);
        }
    }

    /**
     * 检查锁是否被持有
     */
    public boolean isLocked(String key) {
        RLock lock = redissonClient.getFairLock(LOCK_PREFIX + key);
        return lock.isLocked();
    }
}
