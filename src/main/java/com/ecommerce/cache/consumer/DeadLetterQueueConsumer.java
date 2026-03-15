package com.ecommerce.cache.consumer;

import com.ecommerce.cache.entity.DeadLetterMessageEntity;
import com.ecommerce.cache.entity.DeadLetterMessageEntity.DlqStatus;
import com.ecommerce.cache.repository.DeadLetterMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 死信队列消费者（Kafka Dead Letter Topic）
 * <p>
 * Kafka 消息重试耗尽后由 DefaultErrorHandler 自动转发到 .DLT 后缀的主题。
 * 本消费者监听死信主题，执行：
 * 1. 持久化到 t_dead_letter_message 表
 * 2. 递增 Prometheus 指标（告警触发）
 * 3. 记录 ERROR 日志（触发日志告警）
 */
@Component
@ConditionalOnProperty(name = "kafka.dead-letter.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueConsumer.class);

    private final DeadLetterMessageRepository dlqRepository;
    private final MeterRegistry meterRegistry;

    private Counter dlqCounter;
    private Counter dlqPersistCounter;

    public DeadLetterQueueConsumer(DeadLetterMessageRepository dlqRepository,
                                    MeterRegistry meterRegistry) {
        this.dlqRepository = dlqRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initMetrics() {
        this.dlqCounter = Counter.builder("mq.dead_letter.received")
                .description("Dead letter messages received").register(meterRegistry);
        this.dlqPersistCounter = Counter.builder("mq.dead_letter.persisted")
                .description("Dead letter messages persisted to DB").register(meterRegistry);
    }

    /**
     * 监听缓存失效死信主题
     */
    @KafkaListener(
            topics = "CACHE_INVALIDATE_TOPIC.DLT",
            groupId = "CG_DEAD_LETTER_HANDLER"
    )
    public void onCacheInvalidateDlt(ConsumerRecord<String, String> record) {
        handleDeadLetter(record, "CACHE_INVALIDATE_TOPIC", "CG_CACHE_INVALIDATE");
    }

    /**
     * 监听 Canal binlog 死信主题
     */
    @KafkaListener(
            topics = "CANAL_BINLOG_TOPIC.DLT",
            groupId = "CG_DEAD_LETTER_HANDLER"
    )
    public void onCanalBinlogDlt(ConsumerRecord<String, String> record) {
        handleDeadLetter(record, "CANAL_BINLOG_TOPIC", "CG_CANAL_BINLOG");
    }

    /**
     * 处理单条死信消息
     */
    private void handleDeadLetter(ConsumerRecord<String, String> record,
                                   String originalTopic, String consumerGroup) {
        dlqCounter.increment();

        String body = record.value();
        String msgId = String.format("%s-%d-%d", record.topic(), record.partition(), record.offset());

        log.error("[DLT] 死信消息: msgId={}, topic={}, partition={}, offset={}, body={}",
                msgId, record.topic(), record.partition(), record.offset(),
                body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);

        // 去重检查
        if (dlqRepository.existsByMsgId(msgId)) {
            log.warn("[DLT] 重复死信消息，跳过: msgId={}", msgId);
            return;
        }

        // 持久化到数据库
        try {
            DeadLetterMessageEntity entity = new DeadLetterMessageEntity();
            entity.setOriginalTopic(originalTopic);
            entity.setConsumerGroup(consumerGroup);
            entity.setMsgId(msgId);
            entity.setMessageKey(record.key());
            entity.setTag(null);
            entity.setMessageBody(body);
            entity.setReconsumeTimes(0);
            entity.setStatus(DlqStatus.PENDING);
            entity.setBornTimestamp(record.timestamp());

            dlqRepository.save(entity);
            dlqPersistCounter.increment();

            log.info("[DLT] 死信消息已持久化: id={}, msgId={}, consumerGroup={}",
                    entity.getId(), msgId, consumerGroup);
        } catch (Exception e) {
            log.error("[DLT] 死信消息持久化失败: msgId={}", msgId, e);
        }
    }
}
