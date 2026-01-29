package com.ecommerce.cache.consumer;

import com.alibaba.fastjson2.JSON;
import com.ecommerce.cache.service.L1CacheService;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地缓存广播消费者
 * 使用广播模式，确保所有实例都能收到失效消息
 * 用于多实例部署时同步本地缓存
 */
@Component
@RocketMQMessageListener(
    topic = "CACHE_LOCAL_INVALIDATE_TOPIC",
    consumerGroup = "${spring.application.name:spu-detail-service}_LOCAL_CACHE",
    // 广播模式：所有消费者都会收到消息
    messageModel = MessageModel.BROADCASTING
)
public class LocalCacheBroadcastConsumer implements RocketMQListener<String> {
    
    private static final Logger log = LoggerFactory.getLogger(LocalCacheBroadcastConsumer.class);
    
    @Value("${spring.application.name:spu-detail-service}")
    private String applicationName;
    
    private final L1CacheService l1CacheService;

    public LocalCacheBroadcastConsumer(L1CacheService l1CacheService) {
        this.l1CacheService = l1CacheService;
    }

    @Override
    public void onMessage(String message) {
        try {
            LocalCacheInvalidateMessage msg = JSON.parseObject(message, LocalCacheInvalidateMessage.class);
            
            log.debug("Received local cache broadcast: keys={}, source={}", 
                msg.keys().size(), msg.sourceInstance());
            
            // 跳过自己发送的消息
            if (applicationName.equals(msg.sourceInstance())) {
                log.debug("Skipping self-sent message");
                return;
            }
            
            // 失效本地缓存
            for (String key : msg.keys()) {
                l1CacheService.invalidate(key);
            }
            
            log.info("Local cache broadcast invalidated: keys={}", msg.keys().size());
            
        } catch (Exception e) {
            log.error("Local cache broadcast invalidate failed", e);
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
