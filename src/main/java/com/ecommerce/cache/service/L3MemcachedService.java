package com.ecommerce.cache.service;

import net.spy.memcached.MemcachedClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * L3 Memcached 二级缓存服务
 * 双集群 20 节点，采用 Ketama 一致性哈希
 * 命中率目标：4.3%，延迟：3ms，3 次重试后回源 DB
 */
@Service
public class L3MemcachedService {
    
    private static final Logger log = LoggerFactory.getLogger(L3MemcachedService.class);
    
    private final MemcachedClient primaryClient;
    private final MemcachedClient backupClient;
    
    @Value("${memcached.default-expiration:600}")
    private int defaultExpiration;
    
    @Value("${memcached.retry-count:3}")
    private int retryCount;
    
    @Value("${memcached.retry-interval:100}")
    private int retryInterval;

    public L3MemcachedService(
            @Qualifier("primaryMemcachedClient") MemcachedClient primaryClient,
            @Qualifier("backupMemcachedClient") MemcachedClient backupClient) {
        this.primaryClient = primaryClient;
        this.backupClient = backupClient;
    }

    /**
     * 获取缓存（主集群 -> 灾备集群 -> null）
     */
    public String get(String key) {
        // 尝试主集群
        String value = getFromCluster(primaryClient, key, "primary");
        if (value != null) {
            return value;
        }
        
        // 主集群未命中，尝试灾备集群
        value = getFromCluster(backupClient, key, "backup");
        if (value != null) {
            // 回填主集群
            setToCluster(primaryClient, key, value, defaultExpiration, "primary");
        }
        
        return value;
    }

    /**
     * 从指定集群获取数据（带重试）
     */
    private String getFromCluster(MemcachedClient client, String key, String clusterName) {
        for (int i = 0; i < retryCount; i++) {
            try {
                Object value = client.get(key);
                if (value != null) {
                    return value.toString();
                }
                return null; // Key 不存在，无需重试
            } catch (Exception e) {
                log.warn("Memcached {} get failed, attempt {}/{}, key: {}", 
                    clusterName, i + 1, retryCount, key, e);
                if (i < retryCount - 1) {
                    sleep(retryInterval);
                }
            }
        }
        return null;
    }

    /**
     * 写入缓存（同时写入主集群和灾备集群）
     */
    public void set(String key, String value) {
        set(key, value, defaultExpiration);
    }

    /**
     * 写入缓存（带过期时间）
     */
    public void set(String key, String value, int expiration) {
        // 添加随机扰动，防止缓存雪崩（1-5 分钟）
        int randomExpiration = expiration + (int) (Math.random() * 300);
        
        // 异步写入两个集群
        setToCluster(primaryClient, key, value, randomExpiration, "primary");
        setToCluster(backupClient, key, value, randomExpiration, "backup");
    }

    /**
     * 写入指定集群
     */
    private void setToCluster(MemcachedClient client, String key, String value, 
                              int expiration, String clusterName) {
        try {
            Future<Boolean> future = client.set(key, expiration, value);
            // 异步写入，不阻塞主流程
            future.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时不影响主流程，记录日志即可
            log.debug("Memcached {} set timeout, key: {}", clusterName, key);
        } catch (Exception e) {
            log.warn("Memcached {} set failed, key: {}", clusterName, key, e);
        }
    }

    /**
     * 删除缓存
     */
    public void delete(String key) {
        deleteFromCluster(primaryClient, key, "primary");
        deleteFromCluster(backupClient, key, "backup");
    }

    /**
     * 从指定集群删除
     */
    private void deleteFromCluster(MemcachedClient client, String key, String clusterName) {
        try {
            client.delete(key);
        } catch (Exception e) {
            log.warn("Memcached {} delete failed, key: {}", clusterName, key, e);
        }
    }

    /**
     * 异步获取缓存（带 Failover 和回源 DB 能力）
     * @param key 缓存 Key
     * @param dbLoader 回源 DB 的加载函数
     * @return 缓存值
     */
    public String getOrLoad(String key, Supplier<String> dbLoader) {
        String value = get(key);
        if (value != null) {
            return value;
        }
        
        // 3 次重试后回源 DB
        log.info("Memcached miss after {} retries, loading from DB, key: {}", retryCount, key);
        value = dbLoader.get();
        
        if (value != null) {
            set(key, value);
        }
        
        return value;
    }

    /**
     * 批量获取
     */
    public java.util.Map<String, Object> getBulk(java.util.Collection<String> keys) {
        try {
            return primaryClient.getBulk(keys);
        } catch (Exception e) {
            log.error("Memcached getBulk failed", e);
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * 原子递增
     */
    public long incr(String key, int by, long defaultValue, int expiration) {
        try {
            return primaryClient.incr(key, by, defaultValue, expiration);
        } catch (Exception e) {
            log.error("Memcached incr failed, key: {}", key, e);
            return -1;
        }
    }

    /**
     * 原子递减
     */
    public long decr(String key, int by, long defaultValue, int expiration) {
        try {
            return primaryClient.decr(key, by, defaultValue, expiration);
        } catch (Exception e) {
            log.error("Memcached decr failed, key: {}", key, e);
            return -1;
        }
    }

    /**
     * 追加数据
     */
    public boolean append(String key, String value) {
        try {
            Future<Boolean> future = primaryClient.append(key, value);
            return future.get(100, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Memcached append failed, key: {}", key, e);
            return false;
        }
    }

    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
