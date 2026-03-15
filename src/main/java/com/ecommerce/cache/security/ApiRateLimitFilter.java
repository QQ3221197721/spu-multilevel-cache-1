package com.ecommerce.cache.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API 限流防刷过滤器
 * 
 * 三层限流策略:
 * 1. 全局 QPS 限流 — 保护系统整体吞吐
 * 2. IP 维度限流 — 防止单 IP 刷接口
 * 3. 接口维度限流 — 防止热点接口被打爆
 * 
 * 算法: 滑动窗口计数器（秒级精度）
 */
@Component
@Order(0)  // 最高优先级，在所有业务 Filter 之前
public class ApiRateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);

    // ========== 全局限流配置 ==========
    @Value("${security.rate-limit.global-qps:${optimization.middleware.rate-limit.global.qps:50000}}")
    private int globalQps;

    // ========== IP 限流配置 ==========
    @Value("${security.rate-limit.ip-qps:${optimization.middleware.rate-limit.ip.qps:100}}")
    private int ipQps;

    // ========== 接口限流配置 ==========
    @Value("${security.rate-limit.api-qps:${optimization.middleware.rate-limit.api.qps:10000}}")
    private int apiQps;

    @Value("${security.rate-limit.enabled:true}")
    private boolean enabled;

    // IP 黑名单自动封禁阈值: 连续被限流 N 次后自动封禁
    @Value("${security.rate-limit.ip-block-threshold:50}")
    private int ipBlockThreshold;

    @Value("${security.rate-limit.ip-block-duration-seconds:300}")
    private int ipBlockDurationSeconds;

    // ========== 滑动窗口数据结构 ==========
    private final SlidingWindowCounter globalCounter = new SlidingWindowCounter();
    private final Map<String, SlidingWindowCounter> ipCounters = new ConcurrentHashMap<>();
    private final Map<String, SlidingWindowCounter> apiCounters = new ConcurrentHashMap<>();

    // IP 封禁记录: IP -> 封禁到期时间戳
    private final Map<String, Long> blockedIps = new ConcurrentHashMap<>();
    // IP 连续被限流计数
    private final Map<String, AtomicInteger> ipViolationCounts = new ConcurrentHashMap<>();

    // Prometheus 指标
    private Counter rateLimitedTotal;
    private Counter rateLimitedByIp;
    private Counter rateLimitedByApi;
    private Counter ipBlockedTotal;

    private final MeterRegistry meterRegistry;

    public ApiRateLimitFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        rateLimitedTotal = Counter.builder("security.rate_limit.rejected_total")
                .description("Total requests rejected by rate limiter")
                .register(meterRegistry);
        rateLimitedByIp = Counter.builder("security.rate_limit.rejected_by_ip")
                .description("Requests rejected by IP rate limiter")
                .register(meterRegistry);
        rateLimitedByApi = Counter.builder("security.rate_limit.rejected_by_api")
                .description("Requests rejected by API rate limiter")
                .register(meterRegistry);
        ipBlockedTotal = Counter.builder("security.rate_limit.ip_blocked_total")
                .description("IPs blocked by auto-ban")
                .register(meterRegistry);

        log.info("API Rate Limit Filter initialized: globalQps={}, ipQps={}, apiQps={}, enabled={}",
                globalQps, ipQps, apiQps, enabled);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        String uri = httpReq.getRequestURI();

        // 跳过健康检查和 Actuator 端点
        if (uri.startsWith("/actuator") || uri.equals("/api/spu/health")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = extractClientIp(httpReq);

        // 1. IP 黑名单检查
        if (isIpBlocked(clientIp)) {
            rejectRequest(httpResp, 403, "IP temporarily blocked due to excessive requests");
            log.warn("[RATE-LIMIT] Blocked IP attempted access: ip={}, uri={}", clientIp, uri);
            return;
        }

        // 2. 全局 QPS 检查
        if (!globalCounter.tryAcquire(globalQps)) {
            rateLimitedTotal.increment();
            rejectRequest(httpResp, 429, "Server is busy, please try again later");
            log.warn("[RATE-LIMIT] Global QPS exceeded: limit={}", globalQps);
            return;
        }

        // 3. IP 维度 QPS 检查
        SlidingWindowCounter ipCounter = ipCounters.computeIfAbsent(clientIp, k -> new SlidingWindowCounter());
        if (!ipCounter.tryAcquire(ipQps)) {
            rateLimitedByIp.increment();
            rateLimitedTotal.increment();
            recordIpViolation(clientIp);
            rejectRequest(httpResp, 429, "Too many requests from your IP");
            log.warn("[RATE-LIMIT] IP QPS exceeded: ip={}, limit={}", clientIp, ipQps);
            return;
        }

        // 4. 接口维度 QPS 检查
        String normalizedUri = normalizeUri(uri);
        SlidingWindowCounter apiCounter = apiCounters.computeIfAbsent(normalizedUri, k -> new SlidingWindowCounter());
        if (!apiCounter.tryAcquire(apiQps)) {
            rateLimitedByApi.increment();
            rateLimitedTotal.increment();
            rejectRequest(httpResp, 429, "API rate limit exceeded");
            log.warn("[RATE-LIMIT] API QPS exceeded: uri={}, limit={}", normalizedUri, apiQps);
            return;
        }

        // IP 违规计数重置（成功通过限流检查）
        ipViolationCounts.remove(clientIp);

        chain.doFilter(request, response);
    }

    /**
     * 提取真实客户端 IP（支持反向代理）
     */
    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // 取第一个 IP（最原始的客户端 IP）
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        ip = request.getHeader("Proxy-Client-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }

    /**
     * 归一化 URI（将路径变量替换为占位符）
     */
    private String normalizeUri(String uri) {
        return uri.replaceAll("/\\d+", "/{id}");
    }

    /**
     * IP 封禁检查
     */
    private boolean isIpBlocked(String ip) {
        Long blockedUntil = blockedIps.get(ip);
        if (blockedUntil == null) {
            return false;
        }
        if (System.currentTimeMillis() > blockedUntil) {
            blockedIps.remove(ip);
            ipViolationCounts.remove(ip);
            return false;
        }
        return true;
    }

    /**
     * 记录 IP 违规，达到阈值自动封禁
     */
    private void recordIpViolation(String ip) {
        AtomicInteger count = ipViolationCounts.computeIfAbsent(ip, k -> new AtomicInteger(0));
        int violations = count.incrementAndGet();
        if (violations >= ipBlockThreshold) {
            blockedIps.put(ip, System.currentTimeMillis() + (long) ipBlockDurationSeconds * 1000);
            ipBlockedTotal.increment();
            log.error("[RATE-LIMIT] IP auto-blocked: ip={}, violations={}, blockDuration={}s",
                    ip, violations, ipBlockDurationSeconds);
        }
    }

    /**
     * 拒绝请求
     */
    private void rejectRequest(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", "1");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }

    @Override
    public void destroy() {
        // 清理资源
        ipCounters.clear();
        apiCounters.clear();
        blockedIps.clear();
        ipViolationCounts.clear();
    }

    /**
     * 滑动窗口计数器
     * 
     * 使用秒级滑动窗口: 将 1 秒分为 10 个 100ms 的子窗口,
     * 每次请求到来时累加当前子窗口计数并汇总最近 1 秒的总数。
     */
    static class SlidingWindowCounter {

        private static final int WINDOW_SIZE = 10; // 10 个子窗口
        private static final long SLOT_DURATION_MS = 100; // 每个子窗口 100ms

        private final AtomicLong[] slots = new AtomicLong[WINDOW_SIZE];
        private final AtomicLong[] slotTimestamps = new AtomicLong[WINDOW_SIZE];

        SlidingWindowCounter() {
            for (int i = 0; i < WINDOW_SIZE; i++) {
                slots[i] = new AtomicLong(0);
                slotTimestamps[i] = new AtomicLong(0);
            }
        }

        /**
         * 尝试获取令牌
         * @param limit 每秒限制数
         * @return true=允许通过, false=被限流
         */
        boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();
            int currentSlot = (int) ((now / SLOT_DURATION_MS) % WINDOW_SIZE);
            long currentSlotTime = (now / SLOT_DURATION_MS) * SLOT_DURATION_MS;

            // 如果当前子窗口过期，重置计数
            if (slotTimestamps[currentSlot].get() != currentSlotTime) {
                slots[currentSlot].set(0);
                slotTimestamps[currentSlot].set(currentSlotTime);
            }

            // 汇总最近 1 秒内所有子窗口的计数
            long windowStart = now - 1000;
            long total = 0;
            for (int i = 0; i < WINDOW_SIZE; i++) {
                long slotTime = slotTimestamps[i].get();
                if (slotTime >= windowStart) {
                    total += slots[i].get();
                }
            }

            if (total >= limit) {
                return false;
            }

            // 计数 +1
            slots[currentSlot].incrementAndGet();
            return true;
        }
    }
}
