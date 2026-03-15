package com.ecommerce.cache.repository;

import com.ecommerce.cache.entity.MessageOutboxEntity;
import com.ecommerce.cache.entity.MessageOutboxEntity.MessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息发件箱 Repository
 */
@Repository
public interface MessageOutboxRepository extends JpaRepository<MessageOutboxEntity, Long> {

    /**
     * 查询待发送/待重试的消息（到达重试时间 + 未超限）
     */
    @Query("SELECT m FROM MessageOutboxEntity m " +
            "WHERE m.status = :status " +
            "AND m.nextRetryTime <= :now " +
            "AND m.retryCount < m.maxRetry " +
            "ORDER BY m.nextRetryTime ASC")
    List<MessageOutboxEntity> findPendingMessages(
            @Param("status") MessageStatus status,
            @Param("now") LocalDateTime now);

    /**
     * 查询超过最大重试次数的消息
     */
    @Query("SELECT m FROM MessageOutboxEntity m " +
            "WHERE m.status = 'PENDING' " +
            "AND m.retryCount >= m.maxRetry")
    List<MessageOutboxEntity> findExhaustedMessages();

    /**
     * 批量清理已确认的历史消息
     */
    @Modifying
    @Query("DELETE FROM MessageOutboxEntity m " +
            "WHERE m.status = 'CONFIRMED' " +
            "AND m.updatedAt < :before")
    int deleteConfirmedBefore(@Param("before") LocalDateTime before);

    /**
     * 按业务键查询
     */
    List<MessageOutboxEntity> findByMessageKey(String messageKey);

    /**
     * 统计各状态消息数
     */
    @Query("SELECT m.status, COUNT(m) FROM MessageOutboxEntity m GROUP BY m.status")
    List<Object[]> countByStatus();
}
