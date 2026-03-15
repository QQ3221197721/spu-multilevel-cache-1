-- =============================================
-- SPU 多级缓存服务 - 初始数据库表结构
-- Flyway V1 迁移脚本
-- MySQL 8.0+
-- =============================================

-- SPU 商品主表
CREATE TABLE IF NOT EXISTS t_spu (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    spu_id          BIGINT NOT NULL COMMENT 'SPU ID',
    name            VARCHAR(200) NOT NULL COMMENT '商品名称',
    subtitle        VARCHAR(500) COMMENT '商品副标题',
    description     TEXT COMMENT '商品描述',
    category_id     BIGINT COMMENT '分类 ID',
    brand_id        BIGINT COMMENT '品牌 ID',
    price           DECIMAL(10, 2) COMMENT '参考价格',
    main_image      VARCHAR(500) COMMENT '主图 URL',
    images          TEXT COMMENT '图集 JSON',
    attributes      TEXT COMMENT '商品属性 JSON',
    status          TINYINT DEFAULT 1 COMMENT '状态: 1-上架 0-下架',
    sales           INT DEFAULT 0 COMMENT '销量',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_spu_id (spu_id),
    KEY idx_category (category_id, status),
    KEY idx_brand (brand_id, status),
    KEY idx_sales (sales DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SPU 商品表';

-- 分类表
CREATE TABLE IF NOT EXISTS t_category (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL COMMENT '分类名称',
    parent_id       BIGINT DEFAULT 0 COMMENT '父分类 ID',
    level           TINYINT DEFAULT 1 COMMENT '层级',
    path            VARCHAR(200) COMMENT '路径',
    sort            INT DEFAULT 0 COMMENT '排序',
    status          TINYINT DEFAULT 1 COMMENT '状态',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品分类表';

-- 品牌表
CREATE TABLE IF NOT EXISTS t_brand (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL COMMENT '品牌名称',
    logo            VARCHAR(500) COMMENT 'Logo URL',
    description     TEXT COMMENT '品牌描述',
    status          TINYINT DEFAULT 1 COMMENT '状态',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='品牌表';

-- SKU 表
CREATE TABLE IF NOT EXISTS t_sku (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id          BIGINT NOT NULL COMMENT 'SKU ID',
    spu_id          BIGINT NOT NULL COMMENT 'SPU ID',
    spec            VARCHAR(500) COMMENT '规格 JSON',
    price           DECIMAL(10, 2) NOT NULL COMMENT '价格',
    stock           INT DEFAULT 0 COMMENT '库存',
    status          TINYINT DEFAULT 1 COMMENT '状态',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_sku_id (sku_id),
    KEY idx_spu (spu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SKU 表';
