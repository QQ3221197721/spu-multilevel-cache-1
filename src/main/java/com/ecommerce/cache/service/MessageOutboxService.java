package com.ecommerce.cache.service;

import com.ecommerce.cache.entity.MessageOutboxEntity;
import com.ecommerce.cache.entity.MessageOutboxEntity.MessageStatus;
import com.ecommerce.cache.repository.MessageOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 消息发件箱服务（Outbox Pattern）
 * <p>
 * 解决分布式系统中业务操作与消息发送的一致性问题：
 * <pre>
 *   ┌──────────────────────────────────────────────────┐
 *   │ 同一本地事务                                       │
 *   │  1. 执行业务操作（如缓存失效）                       │
 *   │  2. 写入 t_message_outbox（status=PENDING）        │
 *   └──────────────────────────────────────────────────┘
 *        ↓（调度器）
 *   ┌──────────────────────────────────────────────────┐
 *   │ 异步投递                                           │
 *   │  3. 扫描 PENDING 消息 → 投递 Kafka                 │
 *   │  4. 成功 → SENT，失败 → 递增 retryCount + 指数退避   │
 *   │  5. 超过 maxRetry → FAILED + 告警                  │
 *   └──────────────────────────────────────────────────┘
 *        ↓（定时清理）
 *   ┌──────────────────────────────────────────────────┐
 *   │ 6. 清理 N 天前的 CONFIRMED 消息                     │
 *   └──────────────────────────────────────────────────┘
 * </pre>
 */
@Service
public class MessageOutboxService {

    private static final Logger log = LoggerFactory.getLogger(MessageOutboxService.class);

    private final MessageOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${kafka.outbox.enabled:true}")
    private boolean outboxEnabled;

    @Value("${kafka.outbox.max-retry:5}")
    private int defaultMaxRetry;

    @Value("${kafka.outbox.cleanup-days:7}")
    private int cleanupDays;

    private Counter sendSuccessCounter;
    private Counter sendFailureCounter;
    private Counter retryCounter;

    public MessageOutboxService(MessageOutboxRepository outboxRepository,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initMetrics() {
        this.sendSuccessCounter = Counter.builder("mq.outbox.send.success")
                .description("Outbox messages sent successfully").register(meterRegistry);
        this.sendFailureCounter = Counter.builder("mq.outbox.send.failure")
                .description("Outbox messages send failed").register(meterRegistry);
        this.retryCounter = Counter.builder("mq.outbox.retry")
                .description("Outbox message retries").register(meterRegistry);
    }

    // ==================== 写入 Outbox ====================

    /**
     * 保存消息到 Outbox（应在业务事务中调用）
     *
     * @param topic      Kafka Topic
     * @param tag        消息 Tag（Kafka 中作为 Header）
     * @param messageKey 业务唯一键（用作 Kafka 分区键）
     * @param body       消息体 JSON
     * @param traceId    链路追踪 ID
     * @return 持久化的 Outbox 实体
     */
    @Transactional
    public MessageOutboxEntity saveToOutbox(String topic, String tag, String messageKey,
                                            String body, String traceId) {
        MessageOutboxEntity outbox = new MessageOutboxEntity();
        outbox.setTopic(topic);
        outbox.setTag(tag);
        outbox.setMessageKey(messageKey);
        outbox.setMessageBody(body);
        outbox.setStatus(MessageStatus.PENDING);
        outbox.setMaxRetry(defaultMaxRetry);
        outbox.setTraceId(traceId);
        outbox.setNextRetryTime(LocalDateTime.now());

        outbox = outboxRepository.save(outbox);
        log.debug("消息已保存到 Outbox: id={}, topic={}, key={}", outbox.getId(), topic, messageKey);
        return outbox;
    }

    /**
     * 立即发送（绕过 Outbox，用于非关键消息）
     */
    public boolean sendDirect(String topic, String tag, String messageKey, String body) {
        try {
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, messageKey, body);
            SendResult<String, String> result = future.get();
            sendSuccessCounter.increment();
            log.debug("直接发送成功: topic={}, partition={}, offset={}",
                    topic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            return true;
        } catch (Exception e) {
            log.error("直接发送失败: topic={}, key={}", topic, messageKey, e);
            sendFailureCounter.increment();
            return false;
        }
    }

    // ==================== 定时调度 ====================

    /**
     * 扫描并投递待发送消息（每 5 秒）
     */
    @Scheduled(fixedDelayString = "${kafka.outbox.retry-interval-ms:5000}")
    @Transactional
    public void processOutboxMessages() {
        if (!outboxEnabled) return;

        List<MessageOutboxEntity> pendingMessages = outboxRepository.findPendingMessages(
                MessageStatus.PENDING, LocalDateTime.now());

        if (pendingMessages.isEmpty()) return;

        log.debug("处理 {} 条待发送 Outbox 消息", pendingMessages.size());

        for (MessageOutboxEntity msg : pendingMessages) {
            try {
                CompletableFuture<SendResult<String, String>> future =
                        kafkaTemplate.send(msg.getTopic(), msg.getMessageKey(), msg.getMessageBody());
                SendResult<String, String> result = future.get();

                msg.setStatus(MessageStatus.SENT);
                msg.setMqMsgId(String.format("%d-%d",
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset()));
                sendSuccessCounter.increment();
                log.debug("Outbox 消息已发送: id={}, partition={}, offset={}",
                        msg.getId(), result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            } catch (Exception e) {
                handleSendFailure(msg, e.getMessage());
            }
            outboxRepository.save(msg);
        }
    }

    /**
     * 标记超过最大重试次数的消息为 FAILED（每分钟）
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void markExhaustedMessages() {
        List<MessageOutboxEntity> exhausted = outboxRepository.findExhaustedMessages();
        for (MessageOutboxEntity msg : exhausted) {
            msg.setStatus(MessageStatus.FAILED);
            outboxRepository.save(msg);
            sendFailureCounter.increment();
            log.error("Outbox 消息重试耗尽: id={}, topic={}, key={}, retryCount={}",
                    msg.getId(), msg.getTopic(), msg.getMessageKey(), msg.getRetryCount());
        }
    }

    /**
     * 清理 N 天前已确认的消息（每小时）
     */
    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanupConfirmedMessages() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(cleanupDays);
        int deleted = outboxRepository.deleteConfirmedBefore(cutoff);
        if (deleted > 0) {
            log.info("清理 {} 条 {} 天前的已确认 Outbox 消息", deleted, cleanupDays);
        }
    }

    // ==================== 手动操作 ====================

    /**
     * 确认消息（消费者回调）
     */
    @Transactional
    public void confirmMessage(String messageKey) {
        List<MessageOutboxEntity> messages = outboxRepository.findByMessageKey(messageKey);
        for (MessageOutboxEntity msg : messages) {
            if (msg.getStatus() == MessageStatus.SENT) {
                msg.setStatus(MessageStatus.CONFIRMED);
                outboxRepository.save(msg);
                log.debug("Outbox 消息已确认: id={}, key={}", msg.getId(), messageKey);
            }
        }
    }

    /**
     * 手动重试失败消息
     */
    @Transactional
    public boolean manualRetry(Long outboxId) {
        return outboxRepository.findById(outboxId).map(msg -> {
            msg.setStatus(MessageStatus.PENDING);
            msg.setRetryCount(0);
            msg.setNextRetryTime(LocalDateTime.now());
            msg.setErrorMessage(null);
            outboxRepository.save(msg);
            retryCounter.increment();
            log.info("Outbox 消息手动重试: id={}", outboxId);
            return true;
        }).orElse(false);
    }

    // ==================== 内部方法 ====================

    /**
     * 处理发送失败：递增重试计数 + 指数退避
     */
    private void handleSendFailure(MessageOutboxEntity msg, String error) {
        msg.setRetryCount(msg.getRetryCount() + 1);
        msg.setErrorMessage(error != null && error.length() > 1000 ? error.substring(0, 1000) : error);

        // 指数退避：1s → 2s → 4s → 8s → 16s ...
        long delaySeconds = (long) Math.pow(2, Math.min(msg.getRetryCount(), 10));
        msg.setNextRetryTime(LocalDateTime.now().plusSeconds(delaySeconds));

        retryCounter.increment();
        log.warn("Outbox 发送失败: id={}, retry={}/{}, nextRetry={}s, error={}",
                msg.getId(), msg.getRetryCount(), msg.getMaxRetry(), delaySeconds,
                error != null && error.length() > 200 ? error.substring(0, 200) : error);
    }
}
