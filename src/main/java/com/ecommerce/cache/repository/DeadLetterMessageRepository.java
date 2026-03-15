package com.ecommerce.cache.repository;

import com.ecommerce.cache.entity.DeadLetterMessageEntity;
import com.ecommerce.cache.entity.DeadLetterMessageEntity.DlqStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 死信消息 Repository
 */
@Repository
public interface DeadLetterMessageRepository extends JpaRepository<DeadLetterMessageEntity, Long> {

    /**
     * 按状态查询
     */
    List<DeadLetterMessageEntity> findByStatus(DlqStatus status);

    /**
     * 按消费者组查询待处理
     */
    List<DeadLetterMessageEntity> findByConsumerGroupAndStatus(String consumerGroup, DlqStatus status);

    /**
     * 统计各消费者组的死信数量
     */
    @Query("SELECT d.consumerGroup, d.status, COUNT(d) " +
            "FROM DeadLetterMessageEntity d " +
            "GROUP BY d.consumerGroup, d.status")
    List<Object[]> countByConsumerGroupAndStatus();

    /**
     * 按消息 ID 查询（去重）
     */
    boolean existsByMsgId(String msgId);
}
