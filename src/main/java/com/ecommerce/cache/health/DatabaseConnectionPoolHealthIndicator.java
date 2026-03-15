package com.ecommerce.cache.health;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据库连接池深度健康检查（HikariCP）
 *
 * 检查项：
 * 1. 活跃/空闲/总/等待线程连接数
 * 2. 连接池使用率（>85% 告警）
 * 3. 连接验证（isValid）
 * 4. 连接池配置参数
 */
@Slf4j
@Component("databasePoolHealth")
public class DatabaseConnectionPoolHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public DatabaseConnectionPoolHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean healthy = true;

        try {
            // 1. 基本连接验证
            long start = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection()) {
                boolean valid = conn.isValid(5);
                long latency = System.currentTimeMillis() - start;
                details.put("connectionValid", valid);
                details.put("connectionLatencyMs", latency);

                if (!valid) {
                    healthy = false;
                }
                if (latency > 1000) {
                    details.put("connectionWarning", "Connection latency > 1s");
                    healthy = false;
                }
            }

            // 2. HikariCP 连接池详细信息
            if (dataSource instanceof HikariDataSource hikari) {
                HikariPoolMXBean poolMXBean = hikari.getHikariPoolMXBean();

                if (poolMXBean != null) {
                    int active = poolMXBean.getActiveConnections();
                    int idle = poolMXBean.getIdleConnections();
                    int total = poolMXBean.getTotalConnections();
                    int waiting = poolMXBean.getThreadsAwaitingConnection();
                    int maxPoolSize = hikari.getMaximumPoolSize();

                    details.put("pool.activeConnections", active);
                    details.put("pool.idleConnections", idle);
                    details.put("pool.totalConnections", total);
                    details.put("pool.threadsAwaitingConnection", waiting);
                    details.put("pool.maxPoolSize", maxPoolSize);
                    details.put("pool.minimumIdle", hikari.getMinimumIdle());
                    details.put("pool.poolName", hikari.getPoolName());
                    details.put("pool.connectionTimeout", hikari.getConnectionTimeout());
                    details.put("pool.idleTimeout", hikari.getIdleTimeout());
                    details.put("pool.maxLifetime", hikari.getMaxLifetime());

                    // 使用率计算
                    double usageRate = maxPoolSize > 0 ? (double) active / maxPoolSize : 0;
                    details.put("pool.usageRate", String.format("%.1f%%", usageRate * 100));

                    // 健康度判断
                    if (usageRate > 0.90) {
                        details.put("pool.warning", "Connection pool usage > 90%, near exhaustion");
                        healthy = false;
                    } else if (usageRate > 0.85) {
                        details.put("pool.warning", "Connection pool usage > 85%, elevated");
                    }

                    if (waiting > 0) {
                        details.put("pool.waitingWarning",
                                waiting + " threads waiting for connection");
                        if (waiting > 10) {
                            healthy = false;
                        }
                    }
                } else {
                    details.put("pool", "Pool MXBean not available");
                }
            } else {
                details.put("pool", "Not using HikariCP, limited pool info");
            }

            details.put("status", healthy ? "HEALTHY" : "DEGRADED");

        } catch (Exception e) {
            log.error("Database connection pool health check failed", e);
            details.put("error", e.getMessage());
            details.put("status", "UNHEALTHY");
            return Health.down().withDetails(details).build();
        }

        return healthy ? Health.up().withDetails(details).build()
                : Health.down().withDetails(details).build();
    }
}
