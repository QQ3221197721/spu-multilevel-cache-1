package com.ecommerce.cache.consumer;

import com.alibaba.fastjson2.JSON;
import com.ecommerce.cache.service.L1CacheService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地缓存广播消费者
 * 使用 Kafka 消费者组实现广播效果：
 * 每个实例使用唯一 groupId（spring.application.name + 实例ID），
 * 确保所有实例都能收到失效消息，用于多实例部署时同步本地缓存。
 */
@Component
public class LocalCacheBroadcastConsumer {

    private static final Logger log = LoggerFactory.getLogger(LocalCacheBroadcastConsumer.class);

    @Value("${spring.application.name:spu-detail-service}")
    private String applicationName;

    private final L1CacheService l1CacheService;

    public LocalCacheBroadcastConsumer(L1CacheService l1CacheService) {
        this.l1CacheService = l1CacheService;
    }

    /**
     * 广播模式消费：每个实例的 groupId 唯一，通过 #{T(java.util.UUID).randomUUID()} 保证
     * 所有实例都会收到同一条消息
     */
    @KafkaListener(
            topics = "CACHE_LOCAL_INVALIDATE_TOPIC",
            groupId = "#{T(java.lang.String).format('%s_LOCAL_CACHE_%s', '${spring.application.name:spu-detail-service}', T(java.util.UUID).randomUUID().toString().substring(0,8))}",
            concurrency = "1"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            LocalCacheInvalidateMessage msg = JSON.parseObject(record.value(), LocalCacheInvalidateMessage.class);

            log.debug("收到本地缓存广播: keys={}, source={}", msg.keys().size(), msg.sourceInstance());

            // 跳过自己发送的消息
            if (applicationName.equals(msg.sourceInstance())) {
                log.debug("跳过自身发送的消息");
                return;
            }

            // 失效本地缓存
            for (String key : msg.keys()) {
                l1CacheService.invalidate(key);
            }

            log.info("本地缓存广播失效完成: keys={}", msg.keys().size());

        } catch (Exception e) {
            log.error("本地缓存广播失效失败", e);
        }
    }

    /**
     * 本地缓存失效消息
     */
    public record LocalCacheInvalidateMessage(
            List<String> keys,
            String sourceInstance,
            long timestamp
    ) {}
}
