package com.ecommerce.cache.config;

import lombok.Data;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * RocketMQ 配置
 */
@Data
@Configuration
@Import(RocketMQAutoConfiguration.class)
@ConfigurationProperties(prefix = "rocketmq")
public class RocketMQConfig {
    
    /** NameServer 地址 */
    private String nameServer;
    
    /** 生产者组 */
    private String producerGroup = "spu_cache_producer_group";
    
    /** 消费者组 */
    private String consumerGroup = "spu_cache_consumer_group";
    
    /** 缓存失效 Topic */
    private String cacheInvalidateTopic = "SPU_CACHE_INVALIDATE";
    
    /** 本地缓存广播 Topic */
    private String localCacheBroadcastTopic = "SPU_LOCAL_CACHE_BROADCAST";
    
    /** 发送超时时间（毫秒） */
    private int sendMsgTimeout = 3000;
    
    /** 重试次数 */
    private int retryTimesWhenSendFailed = 2;
    
    /** 消费者线程数最小值 */
    private int consumeThreadMin = 4;
    
    /** 消费者线程数最大值 */
    private int consumeThreadMax = 16;
    
    /** 批量消费最大消息数 */
    private int consumeMessageBatchMaxSize = 1;
}
