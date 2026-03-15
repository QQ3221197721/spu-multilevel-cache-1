package com.ecommerce.cache.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 缓存发布服务
 * 通过 Kafka 发送缓存失效消息
 */
@Service
public class CachePublisher {

    private static final Logger log = LoggerFactory.getLogger(CachePublisher.class);

    private static final String INVALIDATE_TOPIC = "CACHE_INVALIDATE_TOPIC";
    private static final String LOCAL_INVALIDATE_TOPIC = "CACHE_LOCAL_INVALIDATE_TOPIC";

    @Value("${spring.application.name:spu-detail-service}")
    private String applicationName;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public CachePublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
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
            String payload = com.alibaba.fastjson2.JSON.toJSONString(message);
            // 使用 primaryKey 作为 Kafka 分区键，保证同一 Key 的消息顺序
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(INVALIDATE_TOPIC, primaryKey, payload);
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka 发送缓存失效消息失败: table={}, key={}", tableName, primaryKey, ex);
                } else {
                    log.info("缓存失效消息已发送: table={}, key={}, partition={}, offset={}",
                            tableName, primaryKey,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        } catch (Exception e) {
            log.error("发送缓存失效消息异常", e);
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
            String payload = com.alibaba.fastjson2.JSON.toJSONString(message);
            kafkaTemplate.send(LOCAL_INVALIDATE_TOPIC, payload);
            log.debug("本地缓存失效广播已发送: keys={}", keys.size());
        } catch (Exception e) {
            log.error("发送本地缓存失效广播失败", e);
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
