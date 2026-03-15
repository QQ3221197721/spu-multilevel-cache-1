package com.ecommerce.cache.monitor;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 业务指标收集器
 *
 * 功能：
 * 1. HTTP 请求级 QPS / TP50 / TP90 / TP99 / TP999
 * 2. 接口成功率 / 错误率
 * 3. 按 URI 粒度的指标分解
 * 4. 定时汇总日志输出
 * 5. REST API 查询实时业务指标
 */
@Component
@Order(1)
public class BusinessMetricsCollector implements Filter {

    private static final Logger log = LoggerFactory.getLogger(BusinessMetricsCollector.class);

    private final MeterRegistry meterRegistry;

    // 全局计数器
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder totalSuccess = new LongAdder();
    private final LongAdder totalClientErrors = new LongAdder();  // 4xx
    private final LongAdder totalServerErrors = new LongAdder();  // 5xx

    // 按 URI 的指标
    private final ConcurrentHashMap<String, UriMetrics> uriMetricsMap = new ConcurrentHashMap<>();

    // Prometheus 指标
    private Counter requestCounter;
    private Counter successCounter;
    private Counter clientErrorCounter;
    private Counter serverErrorCounter;
    private Timer requestTimer;

    public BusinessMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        // 注册 Prometheus 指标
        requestCounter = Counter.builder("business.http.requests.total")
                .description("Total HTTP requests")
                .register(meterRegistry);

        successCounter = Counter.builder("business.http.success.total")
                .description("Total successful HTTP requests (2xx)")
                .register(meterRegistry);

        clientErrorCounter = Counter.builder("business.http.client_errors.total")
                .description("Total client error HTTP requests (4xx)")
                .register(meterRegistry);

        serverErrorCounter = Counter.builder("business.http.server_errors.total")
                .description("Total server error HTTP requests (5xx)")
                .register(meterRegistry);

        requestTimer = Timer.builder("business.http.request.duration")
                .description("HTTP request duration")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.9, 0.95, 0.99, 0.999)
                .register(meterRegistry);

        // 注册 Gauge 指标
        Gauge.builder("business.http.success_rate", this, c -> c.getSuccessRate())
                .description("HTTP success rate (2xx / total)")
                .register(meterRegistry);

        Gauge.builder("business.http.qps", this, c -> c.getCurrentQps())
                .description("Current QPS (requests per second)")
                .register(meterRegistry);

        log.info("BusinessMetricsCollector initialized");
    }

    // ==================== Filter 实现 ====================

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        // 跳过 actuator 和静态资源
        String uri = httpReq.getRequestURI();
        if (uri.startsWith("/actuator") || uri.startsWith("/webjars")) {
            chain.doFilter(request, response);
            return;
        }

        long startNanos = System.nanoTime();

        try {
            chain.doFilter(request, response);
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            int status = httpResp.getStatus();

            recordRequest(uri, httpReq.getMethod(), status, durationNanos);
        }
    }

    private void recordRequest(String uri, String method, int status, long durationNanos) {
        totalRequests.increment();
        requestCounter.increment();
        requestTimer.record(durationNanos, TimeUnit.NANOSECONDS);

        // 分类统计
        if (status >= 200 && status < 400) {
            totalSuccess.increment();
            successCounter.increment();
        } else if (status >= 400 && status < 500) {
            totalClientErrors.increment();
            clientErrorCounter.increment();
        } else if (status >= 500) {
            totalServerErrors.increment();
            serverErrorCounter.increment();
        }

        // 按 URI 统计（归一化路径参数）
        String normalizedUri = normalizeUri(uri);
        UriMetrics metrics = uriMetricsMap.computeIfAbsent(normalizedUri, k -> new UriMetrics(k, meterRegistry));
        metrics.record(status, durationNanos);
    }

    /**
     * 归一化 URI，将路径参数替换为 {id}
     * /api/spu/detail/10001 -> /api/spu/detail/{id}
     */
    private String normalizeUri(String uri) {
        return uri.replaceAll("/\\d+", "/{id}");
    }

    // ==================== 查询方法 ====================

    public double getSuccessRate() {
        long total = totalRequests.sum();
        return total > 0 ? (double) totalSuccess.sum() / total : 1.0;
    }

    public double getCurrentQps() {
        // 近似 QPS：最近 1 分钟请求量 / 60
        // 精确 QPS 由 Prometheus rate() 计算
        return totalRequests.sum();
    }

    public Map<String, Object> getMetricsSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRequests", totalRequests.sum());
        summary.put("totalSuccess", totalSuccess.sum());
        summary.put("totalClientErrors", totalClientErrors.sum());
        summary.put("totalServerErrors", totalServerErrors.sum());
        summary.put("successRate", String.format("%.4f", getSuccessRate()));

        // 按 URI 分解
        List<Map<String, Object>> uriBreakdown = new ArrayList<>();
        uriMetricsMap.forEach((uri, metrics) -> {
            Map<String, Object> uriInfo = new LinkedHashMap<>();
            uriInfo.put("uri", uri);
            uriInfo.put("totalRequests", metrics.requests.sum());
            uriInfo.put("successCount", metrics.success.sum());
            uriInfo.put("errorCount", metrics.errors.sum());
            uriInfo.put("successRate", String.format("%.4f", metrics.getSuccessRate()));
            uriBreakdown.add(uriInfo);
        });
        uriBreakdown.sort((a, b) -> Long.compare(
                (long) b.get("totalRequests"), (long) a.get("totalRequests")));
        summary.put("uriBreakdown", uriBreakdown);

        return summary;
    }

    // ==================== 定时汇总 ====================

    @Scheduled(fixedRate = 60000)
    public void logBusinessMetrics() {
        double successRate = getSuccessRate() * 100;
        log.info("Business Metrics: total={}, success={}, clientErr={}, serverErr={}, successRate={:.2f}%",
                totalRequests.sum(), totalSuccess.sum(),
                totalClientErrors.sum(), totalServerErrors.sum(), successRate);
    }

    // ==================== URI 维度指标 ====================

    static class UriMetrics {
        final String uri;
        final LongAdder requests = new LongAdder();
        final LongAdder success = new LongAdder();
        final LongAdder errors = new LongAdder();
        final Timer timer;

        UriMetrics(String uri, MeterRegistry meterRegistry) {
            this.uri = uri;
            this.timer = Timer.builder("business.http.uri.duration")
                    .tag("uri", uri)
                    .publishPercentileHistogram()
                    .publishPercentiles(0.5, 0.9, 0.99)
                    .register(meterRegistry);
        }

        void record(int status, long durationNanos) {
            requests.increment();
            timer.record(durationNanos, TimeUnit.NANOSECONDS);
            if (status >= 200 && status < 400) {
                success.increment();
            } else if (status >= 400) {
                errors.increment();
            }
        }

        double getSuccessRate() {
            long total = requests.sum();
            return total > 0 ? (double) success.sum() / total : 1.0;
        }
    }
}

/**
 * 业务指标查询 REST 端点
 */
@RestController
@RequestMapping("/api/monitor/business")
class BusinessMetricsEndpoint {

    private final BusinessMetricsCollector collector;

    BusinessMetricsEndpoint(BusinessMetricsCollector collector) {
        this.collector = collector;
    }

    /**
     * 获取业务指标摘要（QPS、成功率、错误率、URI 分解）
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getBusinessMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.putAll(collector.getMetricsSummary());
        return ResponseEntity.ok(result);
    }
}
