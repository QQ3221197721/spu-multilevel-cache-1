-- =============================================
-- SPU 多级缓存服务 - 初始测试数据
-- Flyway V2 迁移脚本
-- =============================================

-- 品牌数据
INSERT INTO t_brand (id, name, logo, description) VALUES
(1, '苹果', 'https://cdn.example.com/brands/apple.png', 'Apple Inc.'),
(2, '华为', 'https://cdn.example.com/brands/huawei.png', 'Huawei Technologies'),
(3, '小米', 'https://cdn.example.com/brands/xiaomi.png', 'Xiaomi Corporation');

-- 分类数据
INSERT INTO t_category (id, name, parent_id, level, path) VALUES
(1, '手机数码', 0, 1, '1'),
(2, '手机', 1, 2, '1/2'),
(3, '平板', 1, 2, '1/3'),
(4, '家用电器', 0, 1, '4'),
(5, '电视', 4, 2, '4/5');

-- SPU 商品数据
INSERT INTO t_spu (spu_id, name, subtitle, description, category_id, brand_id, price, main_image, images, attributes, status, sales) VALUES
(10001, 'iPhone 15 Pro Max', '年度旗舰 A17 Pro 芯片', '苹果最新旗舰手机，搭载 A17 Pro 芯片', 2, 1, 9999.00, 'https://cdn.example.com/spu/10001/main.jpg', '["https://cdn.example.com/spu/10001/1.jpg","https://cdn.example.com/spu/10001/2.jpg"]', '[{"name":"屏幕尺寸","value":"6.7英寸"},{"name":"芯片","value":"A17 Pro"}]', 1, 100000),
(10002, 'HUAWEI Mate 60 Pro', '鸿蒙旗舰 麒麟芯片回归', '华为 Mate 60 Pro，麒麟 9000s 芯片', 2, 2, 6999.00, 'https://cdn.example.com/spu/10002/main.jpg', '["https://cdn.example.com/spu/10002/1.jpg"]', '[{"name":"屏幕尺寸","value":"6.82英寸"},{"name":"芯片","value":"麒麟9000s"}]', 1, 80000),
(10003, '小米14 Ultra', '徕卡影像旗舰', '小米 14 Ultra，骁龙 8 Gen 3 芯片', 2, 3, 5999.00, 'https://cdn.example.com/spu/10003/main.jpg', '["https://cdn.example.com/spu/10003/1.jpg"]', '[{"name":"屏幕尺寸","value":"6.73英寸"},{"name":"芯片","value":"骁龙8 Gen 3"}]', 1, 50000),
(10086, '热门测试商品', '用于热点 Key 测试', '这是一个热门商品，用于测试热点 Key 检测与分片', 2, 1, 1999.00, 'https://cdn.example.com/spu/10086/main.jpg', '[]', '[]', 1, 500000);

-- SKU 数据
INSERT INTO t_sku (sku_id, spu_id, spec, price, stock) VALUES
(100011, 10001, '{"颜色":"深空黑","存储":"256GB"}', 9999.00, 1000),
(100012, 10001, '{"颜色":"深空黑","存储":"512GB"}', 11999.00, 800),
(100013, 10001, '{"颜色":"白色","存储":"256GB"}', 9999.00, 1200),
(100021, 10002, '{"颜色":"雅丹黑","存储":"512GB"}', 6999.00, 500),
(100031, 10003, '{"颜色":"黑色","存储":"256GB"}', 5999.00, 2000),
(100861, 10086, '{"规格":"标准版"}', 1999.00, 10000);
