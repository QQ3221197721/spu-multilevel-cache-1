package com.ecommerce.cache.service;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 缓存发布服务
 * 用于发送缓存失效消息到 MQ
 */
@Service
public class CachePublisher {
    
    private static final Logger log = LoggerFactory.getLogger(CachePublisher.class);
    
    private static final String INVALIDATE_TOPIC = "CACHE_INVALIDATE_TOPIC";
    private static final String LOCAL_INVALIDATE_TOPIC = "CACHE_LOCAL_INVALIDATE_TOPIC";
    
    @Value("${spring.application.name:spu-detail-service}")
    private String applicationName;
    
    private final RocketMQTemplate rocketMQTemplate;

    public CachePublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    /**
     * 发送缓存失效消息（全局）
     */
    public void publishInvalidate(String tableName, String eventType, 
                                   String primaryKey, List<String> cacheKeys) {
        CacheInvalidateMessage message = new CacheInvalidateMessage(
            java.util.UUID.randomUUID().toString(),
            tableName,
            eventType,
            primaryKey,
            cacheKeys,
            System.currentTimeMillis(),
            null
        );
        
        try {
            rocketMQTemplate.syncSend(INVALIDATE_TOPIC, 
                MessageBuilder.withPayload(com.alibaba.fastjson2.JSON.toJSONString(message)).build());
            log.info("Cache invalidate message sent: table={}, key={}", tableName, primaryKey);
        } catch (Exception e) {
            log.error("Failed to send cache invalidate message", e);
            throw new RuntimeException("Failed to send cache invalidate message", e);
        }
    }

    /**
     * 发送本地缓存失效广播
     */
    public void publishLocalInvalidate(List<String> keys) {
        LocalCacheInvalidateMessage message = new LocalCacheInvalidateMessage(
            keys,
            applicationName,
            System.currentTimeMillis()
        );
        
        try {
            rocketMQTemplate.syncSend(LOCAL_INVALIDATE_TOPIC,
                MessageBuilder.withPayload(com.alibaba.fastjson2.JSON.toJSONString(message)).build());
            log.debug("Local cache invalidate broadcast sent: keys={}", keys.size());
        } catch (Exception e) {
            log.error("Failed to send local cache invalidate broadcast", e);
        }
    }

    /**
     * 缓存失效消息
     */
    public record CacheInvalidateMessage(
        String messageId,
        String tableName,
        String eventType,
        String primaryKey,
        List<String> cacheKeys,
        long timestamp,
        String traceId
    ) {}

    /**
     * 本地缓存失效消息
     */
    public record LocalCacheInvalidateMessage(
        List<String> keys,
        String sourceInstance,
        long timestamp
    ) {}
}
