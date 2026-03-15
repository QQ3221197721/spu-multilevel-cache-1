package com.ecommerce.cache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ShardingSphere 分库分表配置属性
 * <p>
 * 仅当 sharding.enabled=true 时生效。
 * 主要功能：
 * 1. SPU 表按 spu_id 取模分为 N 张物理表
 * 2. 主从读写分离（写走 master，读走 slave）
 */
@Data
@ConfigurationProperties(prefix = "sharding")
public class ShardingSphereProperties {

    /** 总开关 */
    private boolean enabled = false;

    /** 主库数据源 */
    private DataSourceConfig master = new DataSourceConfig();

    /** 从库数据源 */
    private DataSourceConfig slave = new DataSourceConfig();

    /** 分表配置 */
    private TableConfig table = new TableConfig();

    /** 读写分离配置 */
    private ReadWriteSplitConfig readWriteSplit = new ReadWriteSplitConfig();

    /** ShardingSphere 属性 */
    private PropsConfig props = new PropsConfig();

    // ==================== 内部配置类 ====================

    @Data
    public static class DataSourceConfig {
        private String url;
        private String username;
        private String password;
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
        private HikariConfig hikari = new HikariConfig();
    }

    @Data
    public static class HikariConfig {
        private int maximumPoolSize = 30;
        private int minimumIdle = 5;
        private long idleTimeout = 300000;
        private long maxLifetime = 1800000;
        private long connectionTimeout = 30000;
        private String poolName = "sharding-hikari";
    }

    @Data
    public static class TableConfig {
        /** SPU 分表数量（建议 2 的幂次） */
        private int spuShardCount = 16;
        /** SPU 分片列 */
        private String spuShardingColumn = "spu_id";
        /** 分片算法：MOD / HASH_MOD / INLINE */
        private String spuAlgorithm = "MOD";
    }

    @Data
    public static class ReadWriteSplitConfig {
        /** 读写分离开关 */
        private boolean enabled = true;
        /** 负载均衡算法：ROUND_ROBIN / RANDOM / WEIGHT */
        private String loadBalancer = "ROUND_ROBIN";
    }

    @Data
    public static class PropsConfig {
        /** 是否打印 SQL */
        private boolean sqlShow = false;
        /** 查询线程数 */
        private int maxConnectionsSizePerQuery = 1;
    }
}
