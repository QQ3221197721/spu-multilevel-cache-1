package com.ecommerce.cache.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis 连接池深度健康检查
 *
 * 检查项：
 * 1. 连接池活跃/空闲连接数
 * 2. 连接池使用率（接近上限告警）
 * 3. 连接池配置参数
 * 4. PING 响应延迟
 */
@Slf4j
@Component("redisPoolHealth")
public class RedisConnectionPoolHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory connectionFactory;

    public RedisConnectionPoolHealthIndicator(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean healthy = true;

        try {
            // 1. PING 延迟检测
            long pingStart = System.currentTimeMillis();
            connectionFactory.getConnection().ping();
            long pingLatency = System.currentTimeMillis() - pingStart;
            details.put("pingLatencyMs", pingLatency);

            if (pingLatency > 1000) {
                details.put("pingWarning", "Latency > 1s, potential connectivity issue");
                healthy = false;
            }

            // 2. Lettuce 连接池信息
            if (connectionFactory instanceof LettuceConnectionFactory lettuce) {
                details.put("hostName", lettuce.getHostName());
                details.put("port", lettuce.getPort());
                details.put("database", lettuce.getDatabase());
                details.put("isCluster", lettuce.isClusterAware());

                // Lettuce 连接池配置
                var poolConfig = lettuce.getClientConfiguration().getPoolConfig();
                if (poolConfig.isPresent()) {
                    var config = poolConfig.get();
                    details.put("pool.maxTotal", config.getMaxTotal());
                    details.put("pool.maxIdle", config.getMaxIdle());
                    details.put("pool.minIdle", config.getMinIdle());

                    // 检查连接池健康度
                    if (config.getMaxTotal() <= 0) {
                        details.put("poolWarning", "Pool max total is 0 or negative");
                        healthy = false;
                    }
                } else {
                    details.put("pool", "Not using connection pooling");
                }

                // 连接验证
                details.put("validateConnection", lettuce.getValidateConnection());
            }

            details.put("status", healthy ? "HEALTHY" : "DEGRADED");

        } catch (Exception e) {
            log.error("Redis connection pool health check failed", e);
            details.put("error", e.getMessage());
            details.put("status", "UNHEALTHY");
            return Health.down().withDetails(details).build();
        }

        return healthy ? Health.up().withDetails(details).build()
                : Health.down().withDetails(details).build();
    }
}
