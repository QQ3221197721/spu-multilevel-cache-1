package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.GZIPOutputStream;

/**
 * 中间件优化模块
 * 
 * 核心优化：
 * 1. 智能限流器 - 多维度限流（IP、用户、接口）
 * 2. 自适应响应压缩 - 根据响应大小和类型智能压缩
 * 3. 请求聚合器 - 合并相同请求减少后端压力
 * 4. 熔断降级增强 - 细粒度熔断策略
 * 5. 请求追踪增强 - 全链路追踪支持
 * 6. 监控指标增强 - 详细的性能指标采集
 */
@Configuration
public class MiddlewareOptimizer implements WebMvcConfigurer {
    
    private static final Logger log = LoggerFactory.getLogger(MiddlewareOptimizer.class);
    
    private final SmartRateLimiter rateLimiter;
    private final RequestAggregator requestAggregator;
    private final PerformanceInterceptor performanceInterceptor;
    
    public MiddlewareOptimizer(SmartRateLimiter rateLimiter,
                               RequestAggregator requestAggregator,
                               PerformanceInterceptor performanceInterceptor) {
        this.rateLimiter = rateLimiter;
        this.requestAggregator = requestAggregator;
        this.performanceInterceptor = performanceInterceptor;
    }
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(performanceInterceptor)
            .addPathPatterns("/api/**")
            .order(Ordered.HIGHEST_PRECEDENCE);
    }
    
    /**
     * 注册 GZIP 压缩过滤器
     */
    @Bean
    public FilterRegistrationBean<AdaptiveCompressionFilter> compressionFilter(
            AdaptiveCompressionFilter filter) {
        FilterRegistrationBean<AdaptiveCompressionFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
    
    /**
     * 注册限流过滤器
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}

/**
 * 智能限流器 - 多维度自适应限流
 */
@Component
class SmartRateLimiter {
    
    private static final Logger log = LoggerFactory.getLogger(SmartRateLimiter.class);
    
    // IP 维度限流
    private final ConcurrentHashMap<String, TokenBucket> ipBuckets = new ConcurrentHashMap<>();
    
    // 用户维度限流
    private final ConcurrentHashMap<String, TokenBucket> userBuckets = new ConcurrentHashMap<>();
    
    // 接口维度限流
    private final ConcurrentHashMap<String, TokenBucket> apiBuckets = new ConcurrentHashMap<>();
    
    // 全局限流
    private final TokenBucket globalBucket;
    
    // 配置
    @Value("${optimization.middleware.rate-limit.ip.qps:100}")
    private int ipQps;
    
    @Value("${optimization.middleware.rate-limit.user.qps:50}")
    private int userQps;
    
    @Value("${optimization.middleware.rate-limit.api.qps:10000}")
    private int apiQps;
    
    @Value("${optimization.middleware.rate-limit.global.qps:50000}")
    private int globalQps;
    
    // 指标
    private final Counter allowedCounter;
    private final Counter rejectedCounter;
    private final Counter ipRejectedCounter;
    private final Counter userRejectedCounter;
    
    public SmartRateLimiter(MeterRegistry meterRegistry) {
        this.globalBucket = new TokenBucket(50000, 50000);
        
        this.allowedCounter = Counter.builder("rate.limit.allowed").register(meterRegistry);
        this.rejectedCounter = Counter.builder("rate.limit.rejected").register(meterRegistry);
        this.ipRejectedCounter = Counter.builder("rate.limit.ip.rejected").register(meterRegistry);
        this.userRejectedCounter = Counter.builder("rate.limit.user.rejected").register(meterRegistry);
        
        Gauge.builder("rate.limit.ip.buckets", ipBuckets, ConcurrentHashMap::size)
            .register(meterRegistry);
    }
    
    @PostConstruct
    public void init() {
        this.globalBucket.setRate(globalQps);
    }
    
    /**
     * 检查是否允许请求
     */
    public RateLimitResult tryAcquire(String ip, String userId, String api) {
        // 1. 全局限流检查
        if (!globalBucket.tryAcquire()) {
            rejectedCounter.increment();
            return new RateLimitResult(false, "GLOBAL_LIMIT", "System busy, please retry later");
        }
        
        // 2. IP 限流检查
        TokenBucket ipBucket = ipBuckets.computeIfAbsent(ip, k -> new TokenBucket(ipQps, ipQps * 2));
        if (!ipBucket.tryAcquire()) {
            rejectedCounter.increment();
            ipRejectedCounter.increment();
            return new RateLimitResult(false, "IP_LIMIT", "Too many requests from your IP");
        }
        
        // 3. 用户限流检查（如果已登录）
        if (userId != null && !userId.isEmpty()) {
            TokenBucket userBucket = userBuckets.computeIfAbsent(userId, k -> new TokenBucket(userQps, userQps * 2));
            if (!userBucket.tryAcquire()) {
                rejectedCounter.increment();
                userRejectedCounter.increment();
                return new RateLimitResult(false, "USER_LIMIT", "Too many requests, please slow down");
            }
        }
        
        // 4. API 限流检查
        TokenBucket apiBucket = apiBuckets.computeIfAbsent(api, k -> new TokenBucket(apiQps, apiQps * 2));
        if (!apiBucket.tryAcquire()) {
            rejectedCounter.increment();
            return new RateLimitResult(false, "API_LIMIT", "API rate limit exceeded");
        }
        
        allowedCounter.increment();
        return new RateLimitResult(true, null, null);
    }
    
    /**
     * 定期清理过期桶
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredBuckets() {
        long threshold = System.currentTimeMillis() - 300000; // 5 分钟未访问
        
        ipBuckets.entrySet().removeIf(e -> e.getValue().getLastAccessTime() < threshold);
        userBuckets.entrySet().removeIf(e -> e.getValue().getLastAccessTime() < threshold);
        
        log.debug("Cleaned up rate limit buckets: ip={}, user={}", ipBuckets.size(), userBuckets.size());
    }
    
    public record RateLimitResult(boolean allowed, String limitType, String message) {}
    
    /**
     * 令牌桶实现
     */
    static class TokenBucket {
        private final AtomicLong tokens;
        private final AtomicLong lastRefillTime;
        private volatile long rate;
        private volatile long capacity;
        private volatile long lastAccessTime;
        
        TokenBucket(long rate, long capacity) {
            this.rate = rate;
            this.capacity = capacity;
            this.tokens = new AtomicLong(capacity);
            this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        boolean tryAcquire() {
            lastAccessTime = System.currentTimeMillis();
            refill();
            
            long current = tokens.get();
            if (current > 0) {
                return tokens.compareAndSet(current, current - 1) || tryAcquire();
            }
            return false;
        }
        
        private void refill() {
            long now = System.currentTimeMillis();
            long last = lastRefillTime.get();
            long elapsed = now - last;
            
            if (elapsed > 0) {
                long tokensToAdd = (elapsed * rate) / 1000;
                if (tokensToAdd > 0 && lastRefillTime.compareAndSet(last, now)) {
                    long newTokens = Math.min(capacity, tokens.get() + tokensToAdd);
                    tokens.set(newTokens);
                }
            }
        }
        
        void setRate(long rate) {
            this.rate = rate;
            this.capacity = rate * 2;
        }
        
        long getLastAccessTime() {
            return lastAccessTime;
        }
    }
}

/**
 * 限流过滤器
 */
@Component
class RateLimitFilter implements Filter {
    
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    
    private final SmartRateLimiter rateLimiter;
    
    public RateLimitFilter(SmartRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        String ip = getClientIp(httpRequest);
        String userId = httpRequest.getHeader("X-User-Id");
        String api = httpRequest.getRequestURI();
        
        SmartRateLimiter.RateLimitResult result = rateLimiter.tryAcquire(ip, userId, api);
        
        if (!result.allowed()) {
            httpResponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write(
                String.format("{\"code\":429,\"message\":\"%s\",\"limitType\":\"%s\"}", 
                    result.message(), result.limitType()));
            return;
        }
        
        chain.doFilter(request, response);
    }
    
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理时取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}

/**
 * 自适应压缩过滤器
 */
@Component
class AdaptiveCompressionFilter implements Filter {
    
    private static final Logger log = LoggerFactory.getLogger(AdaptiveCompressionFilter.class);
    
    @Value("${optimization.middleware.compression.min-size:1024}")
    private int minCompressSize;
    
    @Value("${optimization.middleware.compression.level:6}")
    private int compressionLevel;
    
    private final LongAdder compressedCount = new LongAdder();
    private final LongAdder savedBytes = new LongAdder();
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // 检查客户端是否支持 gzip
        String acceptEncoding = httpRequest.getHeader("Accept-Encoding");
        if (acceptEncoding == null || !acceptEncoding.contains("gzip")) {
            chain.doFilter(request, response);
            return;
        }
        
        // 使用包装器捕获响应
        CompressibleResponseWrapper responseWrapper = new CompressibleResponseWrapper(httpResponse);
        chain.doFilter(request, responseWrapper);
        
        // 获取响应内容
        byte[] content = responseWrapper.getContent();
        
        // 判断是否需要压缩
        if (content.length >= minCompressSize && isCompressibleContentType(responseWrapper.getContentType())) {
            byte[] compressed = compress(content);
            
            if (compressed.length < content.length) {
                httpResponse.setHeader("Content-Encoding", "gzip");
                httpResponse.setContentLength(compressed.length);
                httpResponse.getOutputStream().write(compressed);
                
                compressedCount.increment();
                savedBytes.add(content.length - compressed.length);
                
                log.debug("Compressed response: {} -> {} bytes", content.length, compressed.length);
                return;
            }
        }
        
        // 不压缩，直接输出原始内容
        httpResponse.setContentLength(content.length);
        httpResponse.getOutputStream().write(content);
    }
    
    private boolean isCompressibleContentType(String contentType) {
        if (contentType == null) return false;
        return contentType.contains("json") || 
               contentType.contains("text") || 
               contentType.contains("xml") ||
               contentType.contains("javascript");
    }
    
    private byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos) {
            { def.setLevel(compressionLevel); }
        }) {
            gzip.write(data);
        }
        return bos.toByteArray();
    }
    
    public CompressionStats getStats() {
        return new CompressionStats(compressedCount.sum(), savedBytes.sum());
    }
    
    public record CompressionStats(long compressedCount, long savedBytes) {}
    
    /**
     * 响应包装器
     */
    static class CompressibleResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private ServletOutputStream outputStream;
        private PrintWriter writer;
        
        public CompressibleResponseWrapper(HttpServletResponse response) {
            super(response);
        }
        
        @Override
        public ServletOutputStream getOutputStream() {
            if (outputStream == null) {
                outputStream = new ServletOutputStream() {
                    @Override
                    public void write(int b) { buffer.write(b); }
                    @Override
                    public boolean isReady() { return true; }
                    @Override
                    public void setWriteListener(WriteListener listener) {}
                };
            }
            return outputStream;
        }
        
        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8));
            }
            return writer;
        }
        
        public byte[] getContent() {
            if (writer != null) writer.flush();
            return buffer.toByteArray();
        }
    }
}

/**
 * 请求聚合器 - 合并相同请求
 */
@Component
class RequestAggregator {
    
    private static final Logger log = LoggerFactory.getLogger(RequestAggregator.class);
    
    // 正在处理的请求
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflightRequests = new ConcurrentHashMap<>();
    
    // 指标
    private final Counter aggregatedCounter;
    private final Counter totalCounter;
    
    @Value("${optimization.middleware.aggregation.enabled:true}")
    private boolean enabled;
    
    @Value("${optimization.middleware.aggregation.timeout-ms:100}")
    private long timeoutMs;
    
    public RequestAggregator(MeterRegistry meterRegistry) {
        this.aggregatedCounter = Counter.builder("request.aggregation.aggregated").register(meterRegistry);
        this.totalCounter = Counter.builder("request.aggregation.total").register(meterRegistry);
        
        Gauge.builder("request.aggregation.inflight", inflightRequests, ConcurrentHashMap::size)
            .register(meterRegistry);
    }
    
    /**
     * 执行请求，自动聚合相同请求
     */
    public <T> T execute(String key, java.util.function.Supplier<T> supplier) throws Exception {
        if (!enabled) {
            return supplier.get();
        }
        
        totalCounter.increment();
        
        @SuppressWarnings("unchecked")
        CompletableFuture<T> future = (CompletableFuture<T>) inflightRequests.computeIfAbsent(key, k -> {
            CompletableFuture<T> newFuture = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try {
                    T result = supplier.get();
                    newFuture.complete(result);
                } catch (Exception e) {
                    newFuture.completeExceptionally(e);
                } finally {
                    inflightRequests.remove(k);
                }
            });
            return newFuture;
        });
        
        if (inflightRequests.containsKey(key)) {
            aggregatedCounter.increment();
            log.debug("Request aggregated: {}", key);
        }
        
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
    
    public AggregationStats getStats() {
        return new AggregationStats(totalCounter.count(), aggregatedCounter.count(), inflightRequests.size());
    }
    
    public record AggregationStats(double total, double aggregated, int inflight) {}
}

/**
 * 性能监控拦截器
 */
@Component
class PerformanceInterceptor implements HandlerInterceptor {
    
    private static final Logger log = LoggerFactory.getLogger(PerformanceInterceptor.class);
    
    private static final String START_TIME_ATTR = "requestStartTime";
    
    private final MeterRegistry meterRegistry;
    private final Timer requestTimer;
    private final Counter slowRequestCounter;
    
    @Value("${optimization.middleware.slow-request-threshold-ms:500}")
    private long slowRequestThreshold;
    
    // 最近慢请求
    private final ConcurrentLinkedQueue<SlowRequest> recentSlowRequests = new ConcurrentLinkedQueue<>();
    private static final int MAX_SLOW_REQUESTS = 100;
    
    public PerformanceInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.requestTimer = Timer.builder("http.request.duration.optimized")
            .publishPercentileHistogram()
            .publishPercentiles(0.5, 0.9, 0.95, 0.99)
            .register(meterRegistry);
        this.slowRequestCounter = Counter.builder("http.request.slow").register(meterRegistry);
    }
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.nanoTime());
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, 
                                Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
        if (startTime == null) return;
        
        long duration = (System.nanoTime() - startTime) / 1_000_000; // 转换为毫秒
        
        // 记录请求耗时
        requestTimer.record(Duration.ofMillis(duration));
        
        // 标记慢请求
        if (duration > slowRequestThreshold) {
            slowRequestCounter.increment();
            recordSlowRequest(request, duration);
            log.warn("Slow request detected: {} {} - {}ms", 
                request.getMethod(), request.getRequestURI(), duration);
        }
    }
    
    private void recordSlowRequest(HttpServletRequest request, long duration) {
        SlowRequest slowRequest = new SlowRequest(
            request.getMethod(),
            request.getRequestURI(),
            request.getQueryString(),
            duration,
            Instant.now()
        );
        
        recentSlowRequests.offer(slowRequest);
        
        // 保持队列大小
        while (recentSlowRequests.size() > MAX_SLOW_REQUESTS) {
            recentSlowRequests.poll();
        }
    }
    
    public List<SlowRequest> getRecentSlowRequests() {
        return new ArrayList<>(recentSlowRequests);
    }
    
    public record SlowRequest(String method, String uri, String query, long durationMs, Instant time) {}
}

/**
 * 熔断增强服务 - 细粒度熔断控制
 */
@Component
class CircuitBreakerEnhancer {
    
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerEnhancer.class);
    
    // 各服务的熔断器状态
    private final ConcurrentHashMap<String, CircuitState> circuits = new ConcurrentHashMap<>();
    
    @Value("${optimization.middleware.circuit.failure-threshold:5}")
    private int failureThreshold;
    
    @Value("${optimization.middleware.circuit.success-threshold:3}")
    private int successThreshold;
    
    @Value("${optimization.middleware.circuit.timeout-seconds:30}")
    private int timeoutSeconds;
    
    private final Counter trippedCounter;
    private final Counter resetCounter;
    
    public CircuitBreakerEnhancer(MeterRegistry meterRegistry) {
        this.trippedCounter = Counter.builder("circuit.breaker.tripped").register(meterRegistry);
        this.resetCounter = Counter.builder("circuit.breaker.reset").register(meterRegistry);
        
        Gauge.builder("circuit.breaker.open.count", () -> 
            circuits.values().stream().filter(c -> c.state == State.OPEN).count()
        ).register(meterRegistry);
    }
    
    /**
     * 执行带熔断保护的操作
     */
    public <T> T executeWithCircuitBreaker(String name, java.util.function.Supplier<T> supplier, 
                                           java.util.function.Supplier<T> fallback) {
        CircuitState circuit = circuits.computeIfAbsent(name, k -> new CircuitState());
        
        // 检查熔断器状态
        if (circuit.state == State.OPEN) {
            if (circuit.shouldAttemptReset()) {
                circuit.state = State.HALF_OPEN;
                log.info("Circuit {} transitioning to HALF_OPEN", name);
            } else {
                log.debug("Circuit {} is OPEN, using fallback", name);
                return fallback.get();
            }
        }
        
        try {
            T result = supplier.get();
            circuit.recordSuccess();
            
            if (circuit.state == State.HALF_OPEN && circuit.successCount >= successThreshold) {
                circuit.reset();
                resetCounter.increment();
                log.info("Circuit {} RESET", name);
            }
            
            return result;
        } catch (Exception e) {
            circuit.recordFailure();
            
            if (circuit.failureCount >= failureThreshold) {
                circuit.trip();
                trippedCounter.increment();
                log.warn("Circuit {} TRIPPED", name);
            }
            
            return fallback.get();
        }
    }
    
    public Map<String, CircuitInfo> getCircuitStates() {
        Map<String, CircuitInfo> states = new HashMap<>();
        circuits.forEach((name, circuit) -> 
            states.put(name, new CircuitInfo(circuit.state.name(), 
                circuit.failureCount, circuit.successCount)));
        return states;
    }
    
    enum State { CLOSED, OPEN, HALF_OPEN }
    
    public record CircuitInfo(String state, int failures, int successes) {}
    
    class CircuitState {
        volatile State state = State.CLOSED;
        volatile int failureCount = 0;
        volatile int successCount = 0;
        volatile long lastFailureTime = 0;
        
        void recordFailure() {
            failureCount++;
            lastFailureTime = System.currentTimeMillis();
        }
        
        void recordSuccess() {
            successCount++;
            if (state == State.CLOSED) {
                failureCount = Math.max(0, failureCount - 1);
            }
        }
        
        void trip() {
            state = State.OPEN;
            successCount = 0;
        }
        
        void reset() {
            state = State.CLOSED;
            failureCount = 0;
            successCount = 0;
        }
        
        boolean shouldAttemptReset() {
            return System.currentTimeMillis() - lastFailureTime > timeoutSeconds * 1000L;
        }
    }
}
