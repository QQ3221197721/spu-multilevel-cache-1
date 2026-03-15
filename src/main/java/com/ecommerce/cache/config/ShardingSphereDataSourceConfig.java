package com.ecommerce.cache.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.readwritesplitting.api.ReadwriteSplittingRuleConfiguration;
import org.apache.shardingsphere.readwritesplitting.api.rule.ReadwriteSplittingDataSourceGroupRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;

/**
 * ShardingSphere 分库分表 + 读写分离数据源配置
 * <p>
 * 仅当 sharding.enabled=true 时激活。
 * 关闭时由 Spring Boot 自动配置的 HikariCP DataSource 生效（spring.datasource.*）。
 * <p>
 * 架构：
 * <pre>
 *   物理数据源                  逻辑数据源                  分片规则
 *   ┌─────────┐
 *   │ master  │──┐
 *   └─────────┘  ├─→ readwrite_ds ──→ t_spu → t_spu_00 ~ t_spu_15
 *   ┌─────────┐  │                          （spu_id % 16）
 *   │ slave_0 │──┘
 *   └─────────┘
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "sharding.enabled", havingValue = "true")
@EnableConfigurationProperties(ShardingSphereProperties.class)
public class ShardingSphereDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ShardingSphereDataSourceConfig.class);

    /**
     * 主数据源 — 同时作为 Flyway 迁移数据源
     */
    @Bean("masterDataSource")
    @FlywayDataSource
    public DataSource masterDataSource(ShardingSphereProperties props) {
        log.info("Creating master DataSource for sharding: {}", props.getMaster().getUrl());
        return createHikariDataSource("master", props.getMaster());
    }

    /**
     * 从数据源
     */
    @Bean("slaveDataSource")
    public DataSource slaveDataSource(ShardingSphereProperties props) {
        log.info("Creating slave DataSource for read-write split: {}", props.getSlave().getUrl());
        return createHikariDataSource("slave-0", props.getSlave());
    }

    /**
     * ShardingSphere 组合数据源（主 Bean）
     * 包含分表规则 + 读写分离规则
     */
    @Bean
    @Primary
    public DataSource shardingSphereDataSource(ShardingSphereProperties props) throws SQLException {
        // 1. 物理数据源
        Map<String, DataSource> dataSourceMap = new LinkedHashMap<>();
        dataSourceMap.put("master", createHikariDataSource("master-ss", props.getMaster()));
        dataSourceMap.put("slave_0", createHikariDataSource("slave-0-ss", props.getSlave()));

        // 2. 组装规则
        Collection<RuleConfiguration> rules = new ArrayList<>();

        // 2a. 读写分离规则
        if (props.getReadWriteSplit().isEnabled()) {
            rules.add(buildReadWriteSplitRule(props));
            log.info("Read-write splitting enabled, loadBalancer={}", props.getReadWriteSplit().getLoadBalancer());
        }

        // 2b. 分片规则
        rules.add(buildShardingRule(props));
        log.info("Sharding enabled: t_spu → {} shards by column [{}]",
                props.getTable().getSpuShardCount(), props.getTable().getSpuShardingColumn());

        // 3. ShardingSphere 属性
        Properties ssProps = new Properties();
        ssProps.setProperty("sql-show", String.valueOf(props.getProps().isSqlShow()));
        ssProps.setProperty("max-connections-size-per-query",
                String.valueOf(props.getProps().getMaxConnectionsSizePerQuery()));

        // 4. 创建组合数据源
        DataSource ds = ShardingSphereDataSourceFactory.createDataSource(
                "spu_cache_db", dataSourceMap, rules, ssProps);

        log.info("ShardingSphere DataSource created successfully");
        return ds;
    }

    // ==================== 规则构建 ====================

    /**
     * 读写分离规则
     */
    private ReadwriteSplittingRuleConfiguration buildReadWriteSplitRule(ShardingSphereProperties props) {
        // 数据源组：写 → master，读 → slave_0
        ReadwriteSplittingDataSourceGroupRuleConfiguration groupConfig =
                new ReadwriteSplittingDataSourceGroupRuleConfiguration(
                        "readwrite_ds",       // 逻辑数据源名
                        "master",             // 写数据源
                        List.of("slave_0"),   // 读数据源列表
                        "rw_lb"               // 负载均衡算法名
                );

        // 负载均衡算法
        Map<String, AlgorithmConfiguration> lbAlgorithms = new HashMap<>();
        lbAlgorithms.put("rw_lb", new AlgorithmConfiguration(
                props.getReadWriteSplit().getLoadBalancer(), new Properties()));

        ReadwriteSplittingRuleConfiguration rwConfig = new ReadwriteSplittingRuleConfiguration(
                List.of(groupConfig), lbAlgorithms);
        return rwConfig;
    }

    /**
     * 分片规则 — t_spu 按 spu_id 取模分表
     */
    private ShardingRuleConfiguration buildShardingRule(ShardingSphereProperties props) {
        ShardingRuleConfiguration shardingConfig = new ShardingRuleConfiguration();

        int shardCount = props.getTable().getSpuShardCount();
        String shardColumn = props.getTable().getSpuShardingColumn();

        // 逻辑表 → 物理表映射
        // 当读写分离开启时，数据节点前缀为逻辑数据源名 readwrite_ds
        String dsPrefix = props.getReadWriteSplit().isEnabled() ? "readwrite_ds" : "master";
        String actualDataNodes = String.format("%s.t_spu_${0..%d}", dsPrefix, shardCount - 1);

        ShardingTableRuleConfiguration spuRule = new ShardingTableRuleConfiguration("t_spu", actualDataNodes);
        spuRule.setTableShardingStrategy(
                new StandardShardingStrategyConfiguration(shardColumn, "spu_mod_algorithm"));

        shardingConfig.getTables().add(spuRule);

        // 分片算法
        Properties algorithmProps = new Properties();
        algorithmProps.setProperty("sharding-count", String.valueOf(shardCount));

        shardingConfig.getShardingAlgorithms().put("spu_mod_algorithm",
                new AlgorithmConfiguration(props.getTable().getSpuAlgorithm(), algorithmProps));

        return shardingConfig;
    }

    // ==================== HikariCP 工厂 ====================

    private HikariDataSource createHikariDataSource(String poolName,
                                                     ShardingSphereProperties.DataSourceConfig config) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(config.getUrl());
        ds.setUsername(config.getUsername());
        ds.setPassword(config.getPassword());
        ds.setDriverClassName(config.getDriverClassName());
        ds.setPoolName(poolName);
        ds.setMaximumPoolSize(config.getHikari().getMaximumPoolSize());
        ds.setMinimumIdle(config.getHikari().getMinimumIdle());
        ds.setIdleTimeout(config.getHikari().getIdleTimeout());
        ds.setMaxLifetime(config.getHikari().getMaxLifetime());
        ds.setConnectionTimeout(config.getHikari().getConnectionTimeout());
        return ds;
    }
}
