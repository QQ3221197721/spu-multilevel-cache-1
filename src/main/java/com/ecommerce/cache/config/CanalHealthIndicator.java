package com.ecommerce.cache.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Canal Server 健康检查指示器
 * <p>
 * 通过 Canal Server 的 Prometheus metrics 端口（默认 11112）检测 Canal 存活状态。
 * 集成到 Spring Boot Actuator 的 /actuator/health 端点。
 * <p>
 * 当 Canal Server 不可用时，缓存一致性依赖降级为：
 * - 仅靠延迟双删 + 版本校验保证
 * - 审计机制发现不一致后自动修复
 */
@Component("canalHealthIndicator")
@ConditionalOnProperty(name = "canal.enabled", havingValue = "true", matchIfMissing = true)
public class CanalHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(CanalHealthIndicator.class);

    private final CanalProperties canalProperties;
    private final HttpClient httpClient;

    public CanalHealthIndicator(CanalProperties canalProperties) {
        this.canalProperties = canalProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public Health health() {
        if (!canalProperties.isEnabled()) {
            return Health.up()
                    .withDetail("canal", "disabled")
                    .build();
        }

        String healthUrl = canalProperties.getHealthCheckUrl();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Health.up()
                        .withDetails(Map.of(
                                "canal", "connected",
                                "metricsEndpoint", healthUrl,
                                "binlogTopic", canalProperties.getBinlogTopic(),
                                "consumerGroup", canalProperties.getConsumerGroup()
                        ))
                        .build();
            } else {
                return Health.down()
                        .withDetails(Map.of(
                                "canal", "unhealthy",
                                "statusCode", response.statusCode(),
                                "metricsEndpoint", healthUrl
                        ))
                        .build();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildDownHealth(healthUrl, "Connection interrupted");
        } catch (Exception e) {
            log.debug("[CanalHealth] Canal Server unreachable: {}", e.getMessage());
            return buildDownHealth(healthUrl, e.getMessage());
        }
    }

    private Health buildDownHealth(String healthUrl, String error) {
        return Health.down()
                .withDetails(Map.of(
                        "canal", "unreachable",
                        "metricsEndpoint", healthUrl,
                        "error", error != null ? error : "unknown",
                        "fallback", "Delayed double delete + version check still active"
                ))
                .build();
    }
}
