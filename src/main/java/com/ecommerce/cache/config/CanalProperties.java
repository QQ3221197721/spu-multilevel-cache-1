package com.ecommerce.cache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Canal 缓存一致性配置属性
 * <p>
 * Canal Server 以 RocketMQ 模式运行，将 binlog 变更事件发送到 MQ Topic，
 * 本应用通过 {@link com.ecommerce.cache.consumer.CanalBinlogListener} 消费后触发缓存失效。
 * <p>
 * 配置层级：
 * 1. Canal Server 连接 & 监听 Topic
 * 2. 表名 → 缓存 Key 映射规则
 * 3. 缓存一致性策略参数（延迟双删、版本校验、审计）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "canal")
public class CanalProperties {

    /** 是否启用 Canal binlog 监听 */
    private boolean enabled = true;

    /** Canal binlog 发送到 RocketMQ 的 Topic（需与 instance.properties 中 canal.mq.topic 一致） */
    private String binlogTopic = "CANAL_BINLOG_TOPIC";

    /** SPU 表专用 binlog Topic（动态路由） */
    private String spuBinlogTopic = "CANAL_SPU_BINLOG";

    /** SKU 表专用 binlog Topic（动态路由） */
    private String skuBinlogTopic = "CANAL_SKU_BINLOG";

    /** Canal binlog 消费者组 */
    private String consumerGroup = "CG_CANAL_BINLOG";

    /** Canal Server 健康检查地址（metrics 端口） */
    private String healthCheckUrl = "http://canal-server:11112/metrics";

    /** 表名映射配置 */
    private TableMapping tableMapping = new TableMapping();

    /** 缓存一致性策略配置 */
    private ConsistencyConfig consistency = new ConsistencyConfig();

    /**
     * 表名 → 缓存 Key 前缀映射
     */
    @Data
    public static class TableMapping {
        /** t_spu 表对应的缓存 Key 前缀 */
        private String spuKeyPrefix = "spu:detail:";

        /** t_sku 表对应的缓存 Key 前缀 */
        private String skuKeyPrefix = "sku:detail:";

        /** t_spu_detail 表对应的缓存 Key 前缀 */
        private String spuDetailKeyPrefix = "spu:ext:";

        /** t_spu_attribute 表对应的缓存 Key 前缀 */
        private String spuAttributeKeyPrefix = "spu:attr:";
    }

    /**
     * 缓存一致性策略配置
     */
    @Data
    public static class ConsistencyConfig {
        /** 是否启用延迟双删 */
        private boolean delayedDoubleDeleteEnabled = true;

        /** 延迟双删间隔（毫秒）- 需大于主从复制延迟 */
        private long delayedDeleteMs = 1000;

        /** 是否启用版本号校验 */
        private boolean versionCheckEnabled = true;

        /** 版本号在 Redis 中的 TTL（秒） */
        private int versionTtlSeconds = 3600;

        /** 版本号 Key 前缀 */
        private String versionKeyPrefix = "cache:version:";

        /** 是否启用一致性审计 */
        private boolean auditEnabled = true;

        /** 审计采样率（0.0 ~ 1.0）- 生产环境建议 0.01 ~ 0.05 */
        private double auditSampleRate = 0.05;

        /** 审计窗口时间（秒）- 在此时间窗口内比对 DB 与缓存 */
        private int auditWindowSeconds = 60;

        /** 审计不一致时是否自动修复 */
        private boolean auditAutoFix = true;

        /** binlog 事件最大延迟告警阈值（秒） */
        private int maxBinlogDelayAlertSeconds = 10;
    }
}
