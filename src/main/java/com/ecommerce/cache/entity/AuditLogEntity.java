package com.ecommerce.cache.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 审计日志实体
 *
 * 记录所有敏感操作:
 * - API 调用（写操作: POST/PUT/DELETE）
 * - 缓存管理操作（刷新、清除、预热）
 * - 降级开关操作
 * - 配置变更
 * - 认证/授权事件
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_timestamp", columnList = "operationTime"),
        @Index(name = "idx_audit_operator", columnList = "operator"),
        @Index(name = "idx_audit_type", columnList = "operationType"),
        @Index(name = "idx_audit_resource", columnList = "resource"),
        @Index(name = "idx_audit_risk", columnList = "riskLevel")
})
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作类型: API_CALL, CACHE_MANAGE, DEGRADATION, CONFIG_CHANGE, AUTH_EVENT */
    @Column(nullable = false, length = 32)
    private String operationType;

    /** 操作动作: GET, CREATE, UPDATE, DELETE, REFRESH, INVALIDATE, ENABLE, DISABLE */
    @Column(nullable = false, length = 32)
    private String action;

    /** 操作资源: /api/spu/detail/{id}, cache:spu:detail:10001, degradation */
    @Column(nullable = false, length = 512)
    private String resource;

    /** 资源 ID (可选) */
    @Column(length = 128)
    private String resourceId;

    /** 操作人（JWT 用户名或 IP） */
    @Column(nullable = false, length = 128)
    private String operator;

    /** 客户端 IP */
    @Column(nullable = false, length = 64)
    private String clientIp;

    /** HTTP 方法 */
    @Column(length = 10)
    private String httpMethod;

    /** HTTP 状态码 */
    private Integer httpStatus;

    /** 操作结果: SUCCESS, FAILURE, DENIED */
    @Column(nullable = false, length = 16)
    private String outcome;

    /** 风险等级: LOW, MEDIUM, HIGH, CRITICAL */
    @Column(nullable = false, length = 16)
    private String riskLevel;

    /** 操作耗时（毫秒） */
    private Long durationMs;

    /** 请求体摘要（脱敏后的前 500 字符） */
    @Column(length = 512)
    private String requestSummary;

    /** 响应体摘要（脱敏后的前 500 字符） */
    @Column(length = 512)
    private String responseSummary;

    /** 详细信息 (JSON 格式的扩展字段) */
    @Column(columnDefinition = "TEXT")
    private String details;

    /** User-Agent */
    @Column(length = 512)
    private String userAgent;

    /** 操作时间 */
    @Column(nullable = false)
    private LocalDateTime operationTime;

    /** 链路追踪 ID */
    @Column(length = 64)
    private String traceId;

    // ========== Constructors ==========

    public AuditLogEntity() {
        this.operationTime = LocalDateTime.now();
    }

    // ========== Builder Pattern ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AuditLogEntity entity = new AuditLogEntity();

        public Builder operationType(String val) { entity.operationType = val; return this; }
        public Builder action(String val) { entity.action = val; return this; }
        public Builder resource(String val) { entity.resource = val; return this; }
        public Builder resourceId(String val) { entity.resourceId = val; return this; }
        public Builder operator(String val) { entity.operator = val; return this; }
        public Builder clientIp(String val) { entity.clientIp = val; return this; }
        public Builder httpMethod(String val) { entity.httpMethod = val; return this; }
        public Builder httpStatus(Integer val) { entity.httpStatus = val; return this; }
        public Builder outcome(String val) { entity.outcome = val; return this; }
        public Builder riskLevel(String val) { entity.riskLevel = val; return this; }
        public Builder durationMs(Long val) { entity.durationMs = val; return this; }
        public Builder requestSummary(String val) { entity.requestSummary = val; return this; }
        public Builder responseSummary(String val) { entity.responseSummary = val; return this; }
        public Builder details(String val) { entity.details = val; return this; }
        public Builder userAgent(String val) { entity.userAgent = val; return this; }
        public Builder traceId(String val) { entity.traceId = val; return this; }

        public AuditLogEntity build() { return entity; }
    }

    // ========== Getters & Setters ==========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getRequestSummary() { return requestSummary; }
    public void setRequestSummary(String requestSummary) { this.requestSummary = requestSummary; }
    public String getResponseSummary() { return responseSummary; }
    public void setResponseSummary(String responseSummary) { this.responseSummary = responseSummary; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public LocalDateTime getOperationTime() { return operationTime; }
    public void setOperationTime(LocalDateTime operationTime) { this.operationTime = operationTime; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
