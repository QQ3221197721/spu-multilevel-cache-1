package com.ecommerce.cache.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * L2 Redis 分布式缓存服务
 * 基于 Redis 7 Cluster 实现，10 主 10 从
 * 命中率目标：58%，延迟：2ms，支撑 80W OPS
 */
@Service
public class L2RedisService {
    
    private static final Logger log = LoggerFactory.getLogger(L2RedisService.class);
    
    // 默认过期时间 10 分钟
    private static final long DEFAULT_TTL_SECONDS = 600;
    // 随机扰动范围 1-5 分钟（防雪崩）
    private static final long TTL_RANDOM_RANGE_SECONDS = 300;
    
    private final StringRedisTemplate redisTemplate;
    private final HotKeyShardService hotKeyShardService;

    public L2RedisService(StringRedisTemplate redisTemplate, 
                          HotKeyShardService hotKeyShardService) {
        this.redisTemplate = redisTemplate;
        this.hotKeyShardService = hotKeyShardService;
    }

    /**
     * 获取缓存
     */
    public String get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("Redis get error, key: {}", key, e);
            return null;
        }
    }

    /**
     * 获取热点 Key（自动分片）
     */
    public String getHotKey(String key) {
        try {
            return hotKeyShardService.getHotKey(key);
        } catch (Exception e) {
            log.error("Redis get hot key error, key: {}", key, e);
            return null;
        }
    }

    /**
     * 异步获取缓存
     */
    public CompletableFuture<String> getAsync(String key) {
        return CompletableFuture.supplyAsync(() -> get(key));
    }

    /**
     * 写入缓存（带随机过期时间防雪崩）
     */
    public void set(String key, String value) {
        set(key, value, DEFAULT_TTL_SECONDS);
    }

    /**
     * 写入缓存（自定义过期时间 + 随机扰动）
     */
    public void set(String key, String value, long ttlSeconds) {
        try {
            // 添加随机扰动，防止缓存雪崩
            long randomTtl = ttlSeconds + (long) (Math.random() * TTL_RANDOM_RANGE_SECONDS);
            redisTemplate.opsForValue().set(key, value, randomTtl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Redis set error, key: {}", key, e);
        }
    }

    /**
     * 写入热点 Key（同时更新所有分片）
     */
    public void setHotKey(String key, String value, long ttlSeconds) {
        try {
            hotKeyShardService.setHotKey(key, value, ttlSeconds);
        } catch (Exception e) {
            log.error("Redis set hot key error, key: {}", key, e);
        }
    }

    /**
     * 删除缓存
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Redis delete error, key: {}", key, e);
        }
    }

    /**
     * 删除热点 Key（包括所有分片）
     */
    public void deleteHotKey(String key) {
        try {
            hotKeyShardService.deleteHotKey(key);
        } catch (Exception e) {
            log.error("Redis delete hot key error, key: {}", key, e);
        }
    }

    /**
     * 批量获取
     */
    public List<String> multiGet(List<String> keys) {
        try {
            return redisTemplate.opsForValue().multiGet(keys);
        } catch (Exception e) {
            log.error("Redis multiGet error, keys: {}", keys, e);
            return Collections.emptyList();
        }
    }

    /**
     * 批量写入
     */
    public void multiSet(Map<String, String> keyValues, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().multiSet(keyValues);
            // Pipeline 设置过期时间
            redisTemplate.executePipelined((connection) -> {
                keyValues.keySet().forEach(key -> {
                    long randomTtl = ttlSeconds + (long) (Math.random() * TTL_RANDOM_RANGE_SECONDS);
                    connection.keyCommands().expire(key.getBytes(), randomTtl);
                });
                return null;
            });
        } catch (Exception e) {
            log.error("Redis multiSet error", e);
        }
    }

    /**
     * 检查 Key 是否存在
     */
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.error("Redis exists error, key: {}", key, e);
            return false;
        }
    }

    /**
     * 设置过期时间
     */
    public void expire(String key, long seconds) {
        try {
            redisTemplate.expire(key, Duration.ofSeconds(seconds));
        } catch (Exception e) {
            log.error("Redis expire error, key: {}", key, e);
        }
    }

    /**
     * 获取剩余过期时间
     */
    public Long ttl(String key) {
        try {
            return redisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Redis ttl error, key: {}", key, e);
            return -1L;
        }
    }

    /**
     * 原子递增
     */
    public Long increment(String key) {
        try {
            return redisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            log.error("Redis increment error, key: {}", key, e);
            return null;
        }
    }

    /**
     * SetNX（不存在时设置）
     */
    public boolean setIfAbsent(String key, String value, long ttlSeconds) {
        try {
            return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(key, value, ttlSeconds, TimeUnit.SECONDS)
            );
        } catch (Exception e) {
            log.error("Redis setIfAbsent error, key: {}", key, e);
            return false;
        }
    }
}
