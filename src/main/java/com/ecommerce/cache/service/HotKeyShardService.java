package com.ecommerce.cache.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 热点 Key 分片服务
 * 将高 QPS 的热点 Key 拆分为多个 sub-key，分散单点压力
 */
@Service
public class HotKeyShardService {

    private static final int DEFAULT_SHARD_COUNT = 10;
    private static final String SHARD_SUFFIX = ":shard:";
    
    private final StringRedisTemplate redisTemplate;

    // 热点 Key 读取 Lua 脚本
    private static final String READ_SCRIPT = """
        local key = KEYS[1]
        local shard_count = tonumber(ARGV[1]) or 10
        local random_suffix = math.random(0, shard_count - 1)
        local shard_key = key .. ":shard:" .. random_suffix
        
        local value = redis.call('GET', shard_key)
        if value then
            return value
        end
        
        value = redis.call('GET', key)
        if value then
            for i = 0, shard_count - 1 do
                local sk = key .. ":shard:" .. i
                redis.call('SETEX', sk, 300, value)
            end
        end
        
        return value
        """;

    // 热点 Key 写入 Lua 脚本
    private static final String WRITE_SCRIPT = """
        local key = KEYS[1]
        local value = ARGV[1]
        local ttl = tonumber(ARGV[2]) or 600
        local shard_count = tonumber(ARGV[3]) or 10
        
        redis.call('SETEX', key, ttl, value)
        
        for i = 0, shard_count - 1 do
            local shard_key = key .. ":shard:" .. i
            redis.call('SETEX', shard_key, ttl, value)
        end
        
        return "OK"
        """;

    public HotKeyShardService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读取热点 Key（自动路由到随机分片）
     * @param key 原始 key，如 spu:detail:10086
     * @return 缓存值
     */
    public String getHotKey(String key) {
        // 随机选择一个分片
        int shardIndex = ThreadLocalRandom.current().nextInt(DEFAULT_SHARD_COUNT);
        String shardKey = key + SHARD_SUFFIX + shardIndex;
        
        String value = redisTemplate.opsForValue().get(shardKey);
        if (value != null) {
            return value;
        }
        
        // 分片未命中，使用 Lua 脚本原子操作
        RedisScript<String> script = RedisScript.of(READ_SCRIPT, String.class);
        return redisTemplate.execute(
            script,
            Collections.singletonList(key),
            String.valueOf(DEFAULT_SHARD_COUNT)
        );
    }

    /**
     * 写入热点 Key（同时更新所有分片）
     * @param key 原始 key
     * @param value 缓存值
     * @param ttlSeconds 过期时间（秒）
     */
    public void setHotKey(String key, String value, long ttlSeconds) {
        RedisScript<String> script = RedisScript.of(WRITE_SCRIPT, String.class);
        redisTemplate.execute(
            script,
            Collections.singletonList(key),
            value,
            String.valueOf(ttlSeconds),
            String.valueOf(DEFAULT_SHARD_COUNT)
        );
    }

    /**
     * 删除热点 Key（删除原始 key 和所有分片）
     * @param key 原始 key
     */
    public void deleteHotKey(String key) {
        // 删除原始 key
        redisTemplate.delete(key);
        
        // 删除所有分片
        for (int i = 0; i < DEFAULT_SHARD_COUNT; i++) {
            redisTemplate.delete(key + SHARD_SUFFIX + i);
        }
    }

    /**
     * 判断是否为分片 Key
     */
    public boolean isShardKey(String key) {
        return key.contains(SHARD_SUFFIX);
    }

    /**
     * 从分片 Key 中提取原始 Key
     */
    public String extractOriginalKey(String shardKey) {
        int index = shardKey.indexOf(SHARD_SUFFIX);
        return index > 0 ? shardKey.substring(0, index) : shardKey;
    }
}
