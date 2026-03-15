-- =============================================
-- SPU 多级缓存服务 - V3 分库分表 & 消息持久化
-- Flyway V3 迁移脚本
-- MySQL 8.0+
-- =============================================

-- ========== 1. SPU 分表（t_spu_00 ~ t_spu_15） ==========
-- 当 ShardingSphere 分片模式开启时使用（sharding.enabled=true）
-- 关闭时仍使用原 t_spu 表

DELIMITER //

CREATE PROCEDURE IF NOT EXISTS create_spu_sharding_tables()
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE tbl_name VARCHAR(32);
    DECLARE create_sql TEXT;

    WHILE i < 16 DO
        SET tbl_name = CONCAT('t_spu_', LPAD(i, 2, '0'));
        SET create_sql = CONCAT(
            'CREATE TABLE IF NOT EXISTS ', tbl_name, ' (',
            '  id              BIGINT AUTO_INCREMENT PRIMARY KEY,',
            '  spu_id          BIGINT NOT NULL COMMENT ''SPU ID'',',
            '  name            VARCHAR(200) NOT NULL COMMENT ''商品名称'',',
            '  subtitle        VARCHAR(500) COMMENT ''商品副标题'',',
            '  description     TEXT COMMENT ''商品描述'',',
            '  category_id     BIGINT COMMENT ''分类 ID'',',
            '  brand_id        BIGINT COMMENT ''品牌 ID'',',
            '  price           DECIMAL(10, 2) COMMENT ''参考价格'',',
            '  main_image      VARCHAR(500) COMMENT ''主图 URL'',',
            '  images          TEXT COMMENT ''图集 JSON'',',
            '  attributes      TEXT COMMENT ''商品属性 JSON'',',
            '  status          TINYINT DEFAULT 1 COMMENT ''状态: 1-上架 0-下架'',',
            '  sales           INT DEFAULT 0 COMMENT ''销量'',',
            '  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,',
            '  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,',
            '  UNIQUE KEY uk_spu_id (spu_id),',
            '  KEY idx_category (category_id, status),',
            '  KEY idx_brand (brand_id, status),',
            '  KEY idx_sales (sales DESC)',
            ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
            ' COMMENT=''SPU 商品表 - 分片 ', tbl_name, ''''
        );
        SET @sql = create_sql;
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
        SET i = i + 1;
    END WHILE;
END //

DELIMITER ;

CALL create_spu_sharding_tables();
DROP PROCEDURE IF EXISTS create_spu_sharding_tables;

-- ========== 2. 消息发件箱表（Outbox Pattern） ==========

CREATE TABLE IF NOT EXISTS t_message_outbox (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic               VARCHAR(128) NOT NULL COMMENT 'RocketMQ Topic',
    tag                 VARCHAR(64) COMMENT 'RocketMQ Tag',
    message_key         VARCHAR(128) NOT NULL COMMENT '消息业务键',
    message_body        TEXT NOT NULL COMMENT '消息体 JSON',
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/SENT/CONFIRMED/FAILED',
    retry_count         INT DEFAULT 0 COMMENT '已重试次数',
    max_retry           INT DEFAULT 5 COMMENT '最大重试次数',
    next_retry_time     DATETIME COMMENT '下次重试时间',
    mq_msg_id           VARCHAR(128) COMMENT 'RocketMQ 消息 ID',
    error_message       VARCHAR(1000) COMMENT '最后一次错误信息',
    trace_id            VARCHAR(64) COMMENT '链路追踪 ID',
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    KEY idx_outbox_status_retry (status, next_retry_time),
    KEY idx_outbox_topic_key (topic, message_key),
    KEY idx_outbox_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息发件箱（Outbox Pattern）';

-- ========== 3. 死信消息表 ==========

CREATE TABLE IF NOT EXISTS t_dead_letter_message (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_topic      VARCHAR(128) NOT NULL COMMENT '原始 Topic',
    consumer_group      VARCHAR(128) NOT NULL COMMENT '消费者组',
    msg_id              VARCHAR(128) COMMENT 'RocketMQ 消息 ID',
    message_key         VARCHAR(128) COMMENT '消息 Key',
    tag                 VARCHAR(64) COMMENT '消息 Tag',
    message_body        TEXT NOT NULL COMMENT '消息体',
    reconsume_times     INT DEFAULT 0 COMMENT '已重消费次数',
    failure_reason      VARCHAR(2000) COMMENT '失败原因',
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RETRYING/RESOLVED/IGNORED',
    manual_retry_count  INT DEFAULT 0 COMMENT '手动重试次数',
    handler             VARCHAR(64) COMMENT '处理人',
    remark              VARCHAR(500) COMMENT '处理备注',
    born_timestamp      BIGINT COMMENT '原始消息生产时间戳',
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    KEY idx_dlq_topic_group (original_topic, consumer_group),
    KEY idx_dlq_status (status),
    KEY idx_dlq_created (created_at),
    UNIQUE KEY uk_msg_id (msg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='死信消息表';

-- ========== 4. Seata undo_log 表（分布式事务回滚日志） ==========

CREATE TABLE IF NOT EXISTS undo_log (
    branch_id       BIGINT NOT NULL COMMENT 'branch transaction id',
    xid             VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context         VARCHAR(128) NOT NULL COMMENT 'undo_log context, such as serialization',
    rollback_info   LONGBLOB NOT NULL COMMENT 'rollback info',
    log_status      INT NOT NULL COMMENT '0: normal status, 1: defense status',
    log_created     DATETIME(6) NOT NULL COMMENT 'create datetime',
    log_modified    DATETIME(6) NOT NULL COMMENT 'modify datetime',

    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Seata AT 模式回滚日志表';
