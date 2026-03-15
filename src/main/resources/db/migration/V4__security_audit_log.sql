-- ========== V4: 安全审计日志表 ==========
-- 记录所有敏感操作的完整审计轨迹

CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_type  VARCHAR(32)   NOT NULL COMMENT '操作类型: API_CALL, CACHE_MANAGE, DEGRADATION, CONFIG_CHANGE, AUTH_EVENT',
    action          VARCHAR(32)   NOT NULL COMMENT '操作动作: CREATE, UPDATE, DELETE, REFRESH, INVALIDATE, ENABLE, DISABLE',
    resource        VARCHAR(512)  NOT NULL COMMENT '操作资源: URI 或资源标识',
    resource_id     VARCHAR(128)  NULL     COMMENT '资源ID',
    operator        VARCHAR(128)  NOT NULL COMMENT '操作人（JWT用户名或IP）',
    client_ip       VARCHAR(64)   NOT NULL COMMENT '客户端IP',
    http_method     VARCHAR(10)   NULL     COMMENT 'HTTP方法',
    http_status     INT           NULL     COMMENT 'HTTP状态码',
    outcome         VARCHAR(16)   NOT NULL COMMENT '操作结果: SUCCESS, FAILURE, DENIED',
    risk_level      VARCHAR(16)   NOT NULL COMMENT '风险等级: LOW, MEDIUM, HIGH, CRITICAL',
    duration_ms     BIGINT        NULL     COMMENT '操作耗时(毫秒)',
    request_summary VARCHAR(512)  NULL     COMMENT '请求摘要(脱敏)',
    response_summary VARCHAR(512) NULL     COMMENT '响应摘要(脱敏)',
    details         TEXT          NULL     COMMENT '详细信息(JSON)',
    user_agent      VARCHAR(512)  NULL     COMMENT 'User-Agent',
    operation_time  DATETIME(3)   NOT NULL COMMENT '操作时间',
    trace_id        VARCHAR(64)   NULL     COMMENT '链路追踪ID',

    INDEX idx_audit_timestamp  (operation_time),
    INDEX idx_audit_operator   (operator),
    INDEX idx_audit_type       (operation_type),
    INDEX idx_audit_resource   (resource(191)),
    INDEX idx_audit_risk       (risk_level),
    INDEX idx_audit_outcome    (outcome),
    INDEX idx_audit_composite  (operation_time, operation_type, risk_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='安全审计日志表';

-- 审计日志自动清理事件（保留 90 天）
-- 注: 需要在 MySQL 中开启 event_scheduler
-- SET GLOBAL event_scheduler = ON;
CREATE EVENT IF NOT EXISTS evt_cleanup_audit_log
    ON SCHEDULE EVERY 1 DAY
    STARTS CURRENT_TIMESTAMP
    DO
    DELETE FROM audit_log WHERE operation_time < DATE_SUB(NOW(), INTERVAL 90 DAY);
