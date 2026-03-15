package com.ecommerce.cache.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 死信消息实体
 * <p>
 * 存储 RocketMQ 重试耗尽后进入死信队列（%DLQ%）的消息，
 * 供人工排查和手动重试。
 */
@Data
@Entity
@Table(name = "t_dead_letter_message", indexes = {
    @Index(name = "idx_dlq_topic_group", columnList = "original_topic, consumer_group"),
    @Index(name = "idx_dlq_status", columnList = "status"),
    @Index(name = "idx_dlq_created", columnList = "created_at")
})
public class DeadLetterMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原始 Topic */
    @Column(name = "original_topic", nullable = false, length = 128)
    private String originalTopic;

    /** 消费者组 */
    @Column(name = "consumer_group", nullable = false, length = 128)
    private String consumerGroup;

    /** RocketMQ 消息 ID */
    @Column(name = "msg_id", length = 128)
    private String msgId;

    /** 消息 Key */
    @Column(name = "message_key", length = 128)
    private String messageKey;

    /** 消息 Tag */
    @Column(name = "tag", length = 64)
    private String tag;

    /** 消息体 */
    @Column(name = "message_body", nullable = false, columnDefinition = "TEXT")
    private String messageBody;

    /** 已重消费次数 */
    @Column(name = "reconsume_times")
    private int reconsumeTimes;

    /** 失败原因 */
    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    /** 处理状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DlqStatus status = DlqStatus.PENDING;

    /** 手动重试次数 */
    @Column(name = "manual_retry_count")
    private int manualRetryCount = 0;

    /** 处理人 */
    @Column(name = "handler", length = 64)
    private String handler;

    /** 处理备注 */
    @Column(name = "remark", length = 500)
    private String remark;

    /** 原始消息生产时间 */
    @Column(name = "born_timestamp")
    private Long bornTimestamp;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 死信处理状态
     */
    public enum DlqStatus {
        /** 待处理 */
        PENDING,
        /** 手动重试中 */
        RETRYING,
        /** 已解决 */
        RESOLVED,
        /** 已忽略 */
        IGNORED
    }
}
