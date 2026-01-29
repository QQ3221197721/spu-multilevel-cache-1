-- SPU 多级缓存示例数据库表结构
-- MySQL 8.0+

-- 创建数据库
CREATE DATABASE IF NOT EXISTS spu_cache_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE spu_cache_db;

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

-- 插入测试数据
INSERT INTO t_brand (id, name, logo, description) VALUES
(1, '苹果', 'https://cdn.example.com/brands/apple.png', 'Apple Inc.'),
(2, '华为', 'https://cdn.example.com/brands/huawei.png', 'Huawei Technologies'),
(3, '小米', 'https://cdn.example.com/brands/xiaomi.png', 'Xiaomi Corporation');

INSERT INTO t_category (id, name, parent_id, level, path) VALUES
(1, '手机数码', 0, 1, '1'),
(2, '手机', 1, 2, '1/2'),
(3, '平板', 1, 2, '1/3'),
(4, '家用电器', 0, 1, '4'),
(5, '电视', 4, 2, '4/5');

INSERT INTO t_spu (spu_id, name, subtitle, description, category_id, brand_id, price, main_image, images, attributes, status, sales) VALUES
(10001, 'iPhone 15 Pro Max', '年度旗舰 A17 Pro 芯片', '苹果最新旗舰手机，搭载 A17 Pro 芯片', 2, 1, 9999.00, 'https://cdn.example.com/spu/10001/main.jpg', '["https://cdn.example.com/spu/10001/1.jpg","https://cdn.example.com/spu/10001/2.jpg"]', '[{"name":"屏幕尺寸","value":"6.7英寸"},{"name":"芯片","value":"A17 Pro"}]', 1, 100000),
(10002, 'HUAWEI Mate 60 Pro', '鸿蒙旗舰 麒麟芯片回归', '华为 Mate 60 Pro，麒麟 9000s 芯片', 2, 2, 6999.00, 'https://cdn.example.com/spu/10002/main.jpg', '["https://cdn.example.com/spu/10002/1.jpg"]', '[{"name":"屏幕尺寸","value":"6.82英寸"},{"name":"芯片","value":"麒麟9000s"}]', 1, 80000),
(10003, '小米14 Ultra', '徕卡影像旗舰', '小米 14 Ultra，骁龙 8 Gen 3 芯片', 2, 3, 5999.00, 'https://cdn.example.com/spu/10003/main.jpg', '["https://cdn.example.com/spu/10003/1.jpg"]', '[{"name":"屏幕尺寸","value":"6.73英寸"},{"name":"芯片","value":"骁龙8 Gen 3"}]', 1, 50000),
(10086, '热门测试商品', '用于热点 Key 测试', '这是一个热门商品，用于测试热点 Key 检测与分片', 2, 1, 1999.00, 'https://cdn.example.com/spu/10086/main.jpg', '[]', '[]', 1, 500000);

INSERT INTO t_sku (sku_id, spu_id, spec, price, stock) VALUES
(100011, 10001, '{"颜色":"深空黑","存储":"256GB"}', 9999.00, 1000),
(100012, 10001, '{"颜色":"深空黑","存储":"512GB"}', 11999.00, 800),
(100013, 10001, '{"颜色":"白色","存储":"256GB"}', 9999.00, 1200),
(100021, 10002, '{"颜色":"雅丹黑","存储":"512GB"}', 6999.00, 500),
(100031, 10003, '{"颜色":"黑色","存储":"256GB"}', 5999.00, 2000),
(100861, 10086, '{"规格":"标准版"}', 1999.00, 10000);
