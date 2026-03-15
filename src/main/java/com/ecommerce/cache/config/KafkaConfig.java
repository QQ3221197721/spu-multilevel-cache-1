package com.ecommerce.cache.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 配置（替代 RocketMQConfig）
 * <p>
 * 配置项：
 * 1. 生产者：acks=all, 幂等, 压缩, 批量发送
 * 2. 消费者：手动提交, 错误处理, 重试策略
 * 3. 死信主题：通过 DefaultErrorHandler 自动转发到 .DLT
 * 4. Topic 自动创建
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ==================== 生产者配置 ====================

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // 消息可靠性：所有副本确认
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // 幂等生产者
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // 重试
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);
        // 批量发送优化
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        // 压缩
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        // 超时
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ==================== 消费者配置 ====================

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        // 手动提交
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 每次拉取消息数
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 32);
        // 心跳
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        // 从最早的消息开始消费（新消费者组）
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        // 手动提交 offset
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // 错误处理：重试 3 次，间隔 1 秒，失败后发送到 DLT
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(1000L, 3L)
        ));
        return factory;
    }

    // ==================== Topic 自动创建 ====================

    @Bean
    public NewTopic cacheInvalidateTopic() {
        return TopicBuilder.name("CACHE_INVALIDATE_TOPIC")
                .partitions(8)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic cacheInvalidateDltTopic() {
        return TopicBuilder.name("CACHE_INVALIDATE_TOPIC.DLT")
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic localCacheInvalidateTopic() {
        return TopicBuilder.name("CACHE_LOCAL_INVALIDATE_TOPIC")
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic canalBinlogTopic() {
        return TopicBuilder.name("CANAL_BINLOG_TOPIC")
                .partitions(8)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic canalBinlogDltTopic() {
        return TopicBuilder.name("CANAL_BINLOG_TOPIC.DLT")
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic canalSpuBinlogTopic() {
        return TopicBuilder.name("CANAL_SPU_BINLOG")
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic canalSkuBinlogTopic() {
        return TopicBuilder.name("CANAL_SKU_BINLOG")
                .partitions(4)
                .replicas(1)
                .build();
    }
}
