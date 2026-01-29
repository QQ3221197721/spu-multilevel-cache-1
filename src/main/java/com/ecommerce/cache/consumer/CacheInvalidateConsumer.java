package com.ecommerce.cache.consumer;

import com.alibaba.fastjson2.JSON;
import com.ecommerce.cache.service.L1CacheService;
import com.ecommerce.cache.service.L2RedisService;
import com.ecommerce.cache.service.L3MemcachedService;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 缓存失效消费者
 * 消费 Canal 发送的 binlog 变更消息，顺序删除 L1/L2/L3 缓存
 * 支持幂等消费，确保不丢不重
 */
@Component
@RocketMQMessageListener(
    topic = "CACHE_INVALIDATE_TOPIC",
    consumerGroup = "CG_CACHE_INVALIDATE",
    // 顺序消费，保证同一 Key 的操作顺序
    consumeMode = ConsumeMode.ORDERLY,
    // 最大重试次数
    maxReconsumeTimes = 3
)
public class CacheInvalidateConsumer implements RocketMQListener<String> {
    
    private static final Logger log = LoggerFactory.getLogger(CacheInvalidateConsumer.class);
    
    // 幂等 Key 前缀
    private static final String IDEMPOTENT_PREFIX = "cache:idempotent:";
    // 幂等 Key 过期时间
    private static final Duration IDEMPOTENT_TTL = Duration.ofHours(24);
    
    private final L1CacheService l1CacheService;
    private final L2RedisService l2RedisService;
    private final L3MemcachedService l3MemcachedService;
    private final RedissonClient redissonClient;

    public CacheInvalidateConsumer(L1CacheService l1CacheService,
                                    L2RedisService l2RedisService,
                                    L3MemcachedService l3MemcachedService,
                                    RedissonClient redissonClient) {
        this.l1CacheService = l1CacheService;
        this.l2RedisService = l2RedisService;
        this.l3MemcachedService = l3MemcachedService;
        this.redissonClient = redissonClient;
    }

    @Override
    public void onMessage(String message) {
        CacheInvalidateMessage msg = JSON.parseObject(message, CacheInvalidateMessage.class);
        
        log.info("Received cache invalidate message: messageId={}, table={}, key={}", 
            msg.getMessageId(), msg.getTableName(), msg.getPrimaryKey());
        
        // ========== 幂等检查 ==========
        if (isProcessed(msg.getMessageId())) {
            log.info("Message already processed, skipping: {}", msg.getMessageId());
            return;
        }
        
        try {
            // ========== 顺序删除 L1 -> L2 -> L3 ==========
            List<String> cacheKeys = msg.getCacheKeys();
            
            for (String key : cacheKeys) {
                // 1. 删除 L1 本地缓存
                l1CacheService.invalidate(key);
                log.debug("L1 invalidated: {}", key);
                
                // 2. 删除 L2 Redis（包括热点 Key 分片）
                l2RedisService.deleteHotKey(key);
                log.debug("L2 invalidated: {}", key);
                
                // 3. 删除 L3 Memcached
                l3MemcachedService.delete(key);
                log.debug("L3 invalidated: {}", key);
            }
            
            // ========== 标记幂等 ==========
            markProcessed(msg.getMessageId());
            
            log.info("Cache invalidate completed: messageId={}, keys={}", 
                msg.getMessageId(), cacheKeys.size());
            
        } catch (Exception e) {
            log.error("Cache invalidate failed: messageId={}", msg.getMessageId(), e);
            throw e; // 抛出异常触发重试
        }
    }

    /**
     * 检查消息是否已处理（幂等）
     */
    private boolean isProcessed(String messageId) {
        String key = IDEMPOTENT_PREFIX + messageId;
        RBucket<String> bucket = redissonClient.getBucket(key);
        return bucket.isExists();
    }

    /**
     * 标记消息已处理
     */
    private void markProcessed(String messageId) {
        String key = IDEMPOTENT_PREFIX + messageId;
        RBucket<String> bucket = redissonClient.getBucket(key);
        bucket.set("1", IDEMPOTENT_TTL);
    }

    /**
     * 缓存失效消息 DTO
     */
    public static class CacheInvalidateMessage {
        private String messageId;
        private String tableName;
        private String eventType;
        private String primaryKey;
        private List<String> cacheKeys;
        private long timestamp;
        private String traceId;
        
        // Getters and Setters
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public String getPrimaryKey() { return primaryKey; }
        public void setPrimaryKey(String primaryKey) { this.primaryKey = primaryKey; }
        public List<String> getCacheKeys() { return cacheKeys; }
        public void setCacheKeys(List<String> cacheKeys) { this.cacheKeys = cacheKeys; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
    }
}
