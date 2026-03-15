package com.ecommerce.cache.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息发件箱实体（Outbox Pattern）
 * <p>
 * 本地事务保证消息与业务操作的原子性：
 * 1. 业务操作 + 写入 outbox 在同一事务中
 * 2. 后台调度器扫描 PENDING 消息并投递到 RocketMQ
 * 3. 投递成功标记为 SENT，消费确认后标记为 CONFIRMED
 * 4. 超过最大重试次数标记为 FAILED 并告警
 */
@Data
@Entity
@Table(name = "t_message_outbox", indexes = {
    @Index(name = "idx_outbox_status_retry", columnList = "status, next_retry_time"),
    @Index(name = "idx_outbox_topic_key", columnList = "topic, message_key"),
    @Index(name = "idx_outbox_created", columnList = "created_at")
})
public class MessageOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** RocketMQ Topic */
    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    /** RocketMQ Tag（可选） */
    @Column(name = "tag", length = 64)
    private String tag;

    /** 消息业务键（用于查询和幂等） */
    @Column(name = "message_key", nullable = false, length = 128)
    private String messageKey;

    /** 消息体 JSON */
    @Column(name = "message_body", nullable = false, columnDefinition = "TEXT")
    private String messageBody;

    /** 消息状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MessageStatus status = MessageStatus.PENDING;

    /** 已重试次数 */
    @Column(name = "retry_count")
    private int retryCount = 0;

    /** 最大重试次数 */
    @Column(name = "max_retry")
    private int maxRetry = 5;

    /** 下次重试时间 */
    @Column(name = "next_retry_time")
    private LocalDateTime nextRetryTime;

    /** RocketMQ 返回的 msgId */
    @Column(name = "mq_msg_id", length = 128)
    private String mqMsgId;

    /** 错误信息 */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 链路追踪 ID */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.nextRetryTime == null) {
            this.nextRetryTime = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 消息状态枚举
     */
    public enum MessageStatus {
        /** 待发送 */
        PENDING,
        /** 已发送到 MQ（等待消费确认） */
        SENT,
        /** 消费确认 */
        CONFIRMED,
        /** 发送失败（超过最大重试） */
        FAILED
    }
}
