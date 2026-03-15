package com.ecommerce.cache.repository;

import com.ecommerce.cache.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志仓库
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /**
     * 按时间范围查询
     */
    Page<AuditLogEntity> findByOperationTimeBetweenOrderByOperationTimeDesc(
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 按操作人查询
     */
    Page<AuditLogEntity> findByOperatorOrderByOperationTimeDesc(String operator, Pageable pageable);

    /**
     * 按操作类型查询
     */
    Page<AuditLogEntity> findByOperationTypeOrderByOperationTimeDesc(String operationType, Pageable pageable);

    /**
     * 按风险等级查询
     */
    Page<AuditLogEntity> findByRiskLevelOrderByOperationTimeDesc(String riskLevel, Pageable pageable);

    /**
     * 按资源查询
     */
    Page<AuditLogEntity> findByResourceContainingOrderByOperationTimeDesc(String resource, Pageable pageable);

    /**
     * 综合条件查询
     */
    @Query("SELECT a FROM AuditLogEntity a WHERE " +
           "(:operationType IS NULL OR a.operationType = :operationType) AND " +
           "(:operator IS NULL OR a.operator = :operator) AND " +
           "(:riskLevel IS NULL OR a.riskLevel = :riskLevel) AND " +
           "(:outcome IS NULL OR a.outcome = :outcome) AND " +
           "(:startTime IS NULL OR a.operationTime >= :startTime) AND " +
           "(:endTime IS NULL OR a.operationTime <= :endTime) " +
           "ORDER BY a.operationTime DESC")
    Page<AuditLogEntity> findByFilters(
            @Param("operationType") String operationType,
            @Param("operator") String operator,
            @Param("riskLevel") String riskLevel,
            @Param("outcome") String outcome,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    /**
     * 统计指定时间段内各操作类型的数量
     */
    @Query("SELECT a.operationType, COUNT(a) FROM AuditLogEntity a " +
           "WHERE a.operationTime >= :since GROUP BY a.operationType")
    List<Object[]> countByOperationTypeSince(@Param("since") LocalDateTime since);

    /**
     * 统计指定时间段内各风险等级的数量
     */
    @Query("SELECT a.riskLevel, COUNT(a) FROM AuditLogEntity a " +
           "WHERE a.operationTime >= :since GROUP BY a.riskLevel")
    List<Object[]> countByRiskLevelSince(@Param("since") LocalDateTime since);

    /**
     * 查询失败操作
     */
    @Query("SELECT a FROM AuditLogEntity a WHERE a.outcome = 'FAILURE' AND a.operationTime >= :since " +
           "ORDER BY a.operationTime DESC")
    List<AuditLogEntity> findRecentFailures(@Param("since") LocalDateTime since);

    /**
     * 清理指定天数前的旧审计日志
     */
    void deleteByOperationTimeBefore(LocalDateTime before);
}
