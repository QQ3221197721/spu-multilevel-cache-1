全面大幅优化一下spu-multilevel-cachpackage com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/**
 * 高级弹性保护器 - 自适应熔断与降级
 * 
 * 核心特性：
 * 1. 自适应熔断 - 基于实时指标动态调整阈值
 * 2. 多级降级 - 支持多种降级策略组合
 * 3. 流量整形 - 平滑限流避免突发流量
 * 4. 隔离舱 - 资源隔离防止级联故障
 * 5. 快速恢复 - 渐进式恢复避免雪崩
 * 6. 熔断预警 - 提前预警避免熔断
 * 
 * 目标：系统可用性 > 99.99%，故障恢复时间 < 30s
 */
@Service
public class AdaptiveResilienceGuard {
    
    private static final Logger log = LoggerFactory.getLogger(AdaptiveResilienceGuard.class);
    
    // 熔断器集合
    private final ConcurrentHashMap<String, AdaptiveCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    
    // 限流器集合
    private final ConcurrentHashMap<String, AdaptiveRateLimiter> rateLimiters = new ConcurrentHashMap<>();
    
    // 隔离舱集合
    private final ConcurrentHashMap<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    
    // 降级处理器
    private final ConcurrentHashMap<String, FallbackHandler<?>> fallbackHandlers = new ConcurrentHashMap<>();
    
    // 配置
    @Value("${optimization.resilience.circuit-breaker.default-failure-threshold:50}")
    private int defaultFailureThreshold;
    
    @Value("${optimization.resilience.circuit-breaker.default-slow-call-threshold:80}")
    private int defaultSlowCallThreshold;
    
    @Value("${optimization.resilience.circuit-breaker.default-slow-call-duration-ms:1000}")
    private long defaultSlowCallDurationMs;
    
    @Value("${optimization.resilience.rate-limiter.default-qps:1000}")
    private int defaultQps;
    
    @Value("${optimization.resilience.bulkhead.default-max-concurrent:100}")
    private int defaultMaxConcurrent;
    
    @Value("${optimization.resilience.adaptive-enabled:true}")
    private boolean adaptiveEnabled;
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Counter circuitOpenCounter;
    private Counter circuitCloseCounter;
    private Counter rateLimitRejectedCounter;
    private Counter bulkheadRejectedCounter;
    private Counter fallbackExecutedCounter;
    private Timer protectedCallTimer;
    
    // 全局统计
    private final AtomicLong totalCalls = new AtomicLong(0);
    private final AtomicLong successCalls = new AtomicLong(0);
    private final AtomicLong failedCalls = new AtomicLong(0);
    private final AtomicLong rejectedCalls = new AtomicLong(0);
    
    public AdaptiveResilienceGuard(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    @PostConstruct
    public void init() {
        // 注册指标
        circuitOpenCounter = Counter.builder("resilience.circuit.open").register(meterRegistry);
        circuitCloseCounter = Counter.builder("resilience.circuit.close").register(meterRegistry);
        rateLimitRejectedCounter = Counter.builder("resilience.ratelimit.rejected").register(meterRegistry);
        bulkheadRejectedCounter = Counter.builder("resilience.bulkhead.rejected").register(meterRegistry);
        fallbackExecutedCounter = Counter.builder("resilience.fallback.executed").register(meterRegistry);
        protectedCallTimer = Timer.builder("resilience.protected.call.latency")
            .publishPercentileHistogram()
            .publishPercentiles(0.5, 0.9, 0.95, 0.99)
            .register(meterRegistry);
        
        Gauge.builder("resilience.circuit.breakers.count", circuitBreakers, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("resilience.success.rate", this, AdaptiveResilienceGuard::getSuccessRate)
            .register(meterRegistry);
        
        log.info("AdaptiveResilienceGuard initialized: adaptiveEnabled={}", adaptiveEnabled);
    }
    
    /**
     * 执行受保护的调用
     */
    public <T> T execute(String name, Supplier<T> supplier, Supplier<T> fallback) {
        return protectedCallTimer.record(() -> {
            totalCalls.incrementAndGet();
            
            // 1. 检查熔断器
            AdaptiveCircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(name);
            if (!circuitBreaker.allowRequest()) {
                rejectedCalls.incrementAndGet();
                fallbackExecutedCounter.increment();
                log.debug("Circuit breaker {} is open, executing fallback", name);
                return fallback.get();
            }
            
            // 2. 检查限流器
            AdaptiveRateLimiter rateLimiter = getOrCreateRateLimiter(name);
            if (!rateLimiter.tryAcquire()) {
                rejectedCalls.incrementAndGet();
                rateLimitRejectedCounter.increment();
                log.debug("Rate limit exceeded for {}, executing fallback", name);
                return fallback.get();
            }
            
            // 3. 检查隔离舱
            Bulkhead bulkhead = getOrCreateBulkhead(name);
            if (!bulkhead.tryAcquire()) {
                rejectedCalls.incrementAndGet();
                bulkheadRejectedCounter.increment();
                log.debug("Bulkhead full for {}, executing fallback", name);
                return fallback.get();
            }
            
            // 4. 执行实际调用
            long startTime = System.nanoTime();
            try {
                T result = supplier.get();
                long duration = System.nanoTime() - startTime;
                
                circuitBreaker.recordSuccess(duration);
                successCalls.incrementAndGet();
                
                return result;
            } catch (Exception e) {
                long duration = System.nanoTime() - startTime;
                circuitBreaker.recordFailure(duration, e);
                failedCalls.incrementAndGet();
                
                // 执行降级
                fallbackExecutedCounter.increment();
                log.warn("Call to {} failed, executing fallback: {}", name, e.getMessage());
                return fallback.get();
            } finally {
                bulkhead.release();
            }
        });
    }
    
    /**
     * 执行受保护的异步调用
     */
    public <T> CompletableFuture<T> executeAsync(String name, Supplier<CompletableFuture<T>> supplier, 
                                                   Supplier<T> fallback) {
        totalCalls.incrementAndGet();
        
        AdaptiveCircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(name);
        if (!circuitBreaker.allowRequest()) {
            rejectedCalls.incrementAndGet();
            return CompletableFuture.completedFuture(fallback.get());
        }
        
        AdaptiveRateLimiter rateLimiter = getOrCreateRateLimiter(name);
        if (!rateLimiter.tryAcquire()) {
            rejectedCalls.incrementAndGet();
            return CompletableFuture.completedFuture(fallback.get());
        }
        
        Bulkhead bulkhead = getOrCreateBulkhead(name);
        if (!bulkhead.tryAcquire()) {
            rejectedCalls.incrementAndGet();
            return CompletableFuture.completedFuture(fallback.get());
        }
        
        long startTime = System.nanoTime();
        return supplier.get()
            .whenComplete((result, ex) -> {
                long duration = System.nanoTime() - startTime;
                bulkhead.release();
                
                if (ex != null) {
                    circuitBreaker.recordFailure(duration, ex);
                    failedCalls.incrementAndGet();
                } else {
                    circuitBreaker.recordSuccess(duration);
                    successCalls.incrementAndGet();
                }
            })
            .exceptionally(ex -> {
                fallbackExecutedCounter.increment();
                return fallback.get();
            });
    }
    
    /**
     * 注册降级处理器
     */
    public <T> void registerFallback(String name, FallbackHandler<T> handler) {
        fallbackHandlers.put(name, handler);
    }
    
    /**
     * 获取或创建熔断器
     */
    private AdaptiveCircuitBreaker getOrCreateCircuitBreaker(String name) {
        return circuitBreakers.computeIfAbsent(name, k -> 
            new AdaptiveCircuitBreaker(k, defaultFailureThreshold, defaultSlowCallThreshold, 
                defaultSlowCallDurationMs, adaptiveEnabled, meterRegistry));
    }
    
    /**
     * 获取或创建限流器
     */
    private AdaptiveRateLimiter getOrCreateRateLimiter(String name) {
        return rateLimiters.computeIfAbsent(name, k -> 
            new AdaptiveRateLimiter(k, defaultQps, adaptiveEnabled));
    }
    
    /**
     * 获取或创建隔离舱
     */
    private Bulkhead getOrCreateBulkhead(String name) {
        return bulkheads.computeIfAbsent(name, k -> 
            new Bulkhead(k, defaultMaxConcurrent));
    }
    
    /**
     * 配置熔断器
     */
    public void configureCircuitBreaker(String name, int failureThreshold, int slowCallThreshold, 
                                         long slowCallDurationMs) {
        circuitBreakers.put(name, new AdaptiveCircuitBreaker(name, failureThreshold, slowCallThreshold,
            slowCallDurationMs, adaptiveEnabled, meterRegistry));
    }
    
    /**
     * 配置限流器
     */
    public void configureRateLimiter(String name, int qps) {
        rateLimiters.put(name, new AdaptiveRateLimiter(name, qps, adaptiveEnabled));
    }
    
    /**
     * 配置隔离舱
     */
    public void configureBulkhead(String name, int maxConcurrent) {
        bulkheads.put(name, new Bulkhead(name, maxConcurrent));
    }
    
    /**
     * 强制打开熔断器
     */
    public void forceOpen(String name) {
        AdaptiveCircuitBreaker cb = circuitBreakers.get(name);
        if (cb != null) {
            cb.forceOpen();
        }
    }
    
    /**
     * 强制关闭熔断器
     */
    public void forceClose(String name) {
        AdaptiveCircuitBreaker cb = circuitBreakers.get(name);
        if (cb != null) {
            cb.forceClose();
        }
    }
    
    /**
     * 定期自适应调整
     */
    @Scheduled(fixedRate = 30000) // 30秒
    public void adaptiveAdjust() {
        if (!adaptiveEnabled) return;
        
        for (AdaptiveCircuitBreaker cb : circuitBreakers.values()) {
            cb.adaptiveAdjust();
        }
        
        for (AdaptiveRateLimiter rl : rateLimiters.values()) {
            rl.adaptiveAdjust();
        }
    }
    
    /**
     * 获取成功率
     */
    public double getSuccessRate() {
        long total = totalCalls.get();
        long success = successCalls.get();
        return total > 0 ? (double) success / total : 1.0;
    }
    
    /**
     * 获取所有熔断器状态
     */
    public Map<String, CircuitBreakerStatus> getCircuitBreakerStatuses() {
        Map<String, CircuitBreakerStatus> statuses = new HashMap<>();
        circuitBreakers.forEach((name, cb) -> statuses.put(name, cb.getStatus()));
        return statuses;
    }
    
    /**
     * 获取所有限流器状态
     */
    public Map<String, RateLimiterStatus> getRateLimiterStatuses() {
        Map<String, RateLimiterStatus> statuses = new HashMap<>();
        rateLimiters.forEach((name, rl) -> statuses.put(name, rl.getStatus()));
        return statuses;
    }
    
    /**
     * 获取所有隔离舱状态
     */
    public Map<String, BulkheadStatus> getBulkheadStatuses() {
        Map<String, BulkheadStatus> statuses = new HashMap<>();
        bulkheads.forEach((name, bh) -> statuses.put(name, bh.getStatus()));
        return statuses;
    }
    
    /**
     * 获取总体统计
     */
    public ResilienceStats getStats() {
        return new ResilienceStats(
            totalCalls.get(),
            successCalls.get(),
            failedCalls.get(),
            rejectedCalls.get(),
            getSuccessRate(),
            circuitBreakers.size(),
            rateLimiters.size(),
            bulkheads.size()
        );
    }
    
    // ========== 内部类 ==========
    
    /**
     * 自适应熔断器
     */
    private static class AdaptiveCircuitBreaker {
        private final String name;
        private volatile int failureThreshold;
        private volatile int slowCallThreshold;
        private volatile long slowCallDurationMs;
        private final boolean adaptive;
        private final MeterRegistry meterRegistry;
        
        // 状态
        private volatile State state = State.CLOSED;
        private final AtomicLong failureCount = new AtomicLong(0);
        private final AtomicLong slowCallCount = new AtomicLong(0);
        private final AtomicLong totalCalls = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private volatile long lastStateChangeTime = System.currentTimeMillis();
        private volatile long openTime = 0;
        
        // 滑动窗口统计
        private final long[] callResults = new long[100]; // 最近 100 次调用
        private final AtomicInteger windowIndex = new AtomicInteger(0);
        
        // 半开状态允许的调用数
        private volatile int halfOpenAllowedCalls = 5;
        private final AtomicInteger halfOpenCallCount = new AtomicInteger(0);
        
        enum State { CLOSED, OPEN, HALF_OPEN }
        
        AdaptiveCircuitBreaker(String name, int failureThreshold, int slowCallThreshold,
                               long slowCallDurationMs, boolean adaptive, MeterRegistry meterRegistry) {
            this.name = name;
            this.failureThreshold = failureThreshold;
            this.slowCallThreshold = slowCallThreshold;
            this.slowCallDurationMs = slowCallDurationMs;
            this.adaptive = adaptive;
            this.meterRegistry = meterRegistry;
        }
        
        boolean allowRequest() {
            switch (state) {
                case CLOSED:
                    return true;
                case OPEN:
                    // 检查是否应该转换到半开
                    if (System.currentTimeMillis() - openTime > 30000) { // 30秒后尝试恢复
                        state = State.HALF_OPEN;
                        halfOpenCallCount.set(0);
                        log.info("Circuit breaker {} transitioning to HALF_OPEN", name);
                        return true;
                    }
                    return false;
                case HALF_OPEN:
                    return halfOpenCallCount.incrementAndGet() <= halfOpenAllowedCalls;
                default:
                    return true;
            }
        }
        
        void recordSuccess(long durationNs) {
            totalCalls.incrementAndGet();
            successCount.incrementAndGet();
            
            long durationMs = durationNs / 1_000_000;
            int index = windowIndex.getAndIncrement() % callResults.length;
            callResults[index] = 1; // 1 表示成功
            
            if (durationMs > slowCallDurationMs) {
                slowCallCount.incrementAndGet();
            }
            
            if (state == State.HALF_OPEN) {
                if (halfOpenCallCount.get() >= halfOpenAllowedCalls) {
                    // 半开状态成功次数足够，关闭熔断器
                    close();
                }
            }
        }
        
        void recordFailure(long durationNs, Throwable ex) {
            totalCalls.incrementAndGet();
            failureCount.incrementAndGet();
            
            int index = windowIndex.getAndIncrement() % callResults.length;
            callResults[index] = 0; // 0 表示失败
            
            if (state == State.HALF_OPEN) {
                // 半开状态遇到失败，重新打开
                open();
            } else if (state == State.CLOSED) {
                // 检查是否需要打开熔断器
                checkThreshold();
            }
        }
        
        private void checkThreshold() {
            long total = totalCalls.get();
            if (total < 10) return; // 最少需要 10 次调用
            
            // 计算失败率
            long failures = failureCount.get();
            double failureRate = (double) failures / total * 100;
            
            // 计算慢调用率
            long slowCalls = slowCallCount.get();
            double slowCallRate = (double) slowCalls / total * 100;
            
            if (failureRate >= failureThreshold || slowCallRate >= slowCallThreshold) {
                open();
            }
        }
        
        private void open() {
            state = State.OPEN;
            openTime = System.currentTimeMillis();
            lastStateChangeTime = openTime;
            log.warn("Circuit breaker {} OPENED: failures={}, slowCalls={}", 
                name, failureCount.get(), slowCallCount.get());
        }
        
        private void close() {
            state = State.CLOSED;
            lastStateChangeTime = System.currentTimeMillis();
            failureCount.set(0);
            slowCallCount.set(0);
            totalCalls.set(0);
            successCount.set(0);
            log.info("Circuit breaker {} CLOSED", name);
        }
        
        void forceOpen() {
            state = State.OPEN;
            openTime = System.currentTimeMillis();
            log.warn("Circuit breaker {} force OPENED", name);
        }
        
        void forceClose() {
            close();
            log.info("Circuit breaker {} force CLOSED", name);
        }
        
        void adaptiveAdjust() {
            if (!adaptive) return;
            
            // 基于历史数据自适应调整阈值
            long total = totalCalls.get();
            if (total < 100) return;
            
            long failures = failureCount.get();
            double avgFailureRate = (double) failures / total * 100;
            
            // 如果失败率长期很低，可以适当放宽阈值
            if (avgFailureRate < 10 && failureThreshold < 70) {
                failureThreshold = Math.min(70, failureThreshold + 5);
            }
            // 如果失败率接近阈值，收紧阈值
            else if (avgFailureRate > failureThreshold * 0.8) {
                failureThreshold = Math.max(30, failureThreshold - 5);
            }
        }
        
        CircuitBreakerStatus getStatus() {
            long total = totalCalls.get();
            double failureRate = total > 0 ? (double) failureCount.get() / total * 100 : 0;
            double slowCallRate = total > 0 ? (double) slowCallCount.get() / total * 100 : 0;
            
            return new CircuitBreakerStatus(
                name, state.name(), failureRate, slowCallRate,
                failureThreshold, slowCallThreshold, totalCalls.get()
            );
        }
    }
    
    /**
     * 自适应限流器
     */
    private static class AdaptiveRateLimiter {
        private final String name;
        private volatile int targetQps;
        private final boolean adaptive;
        
        // 令牌桶
        private final AtomicLong tokens;
        private final AtomicLong lastRefillTime;
        
        // 统计
        private final AtomicLong acquireCount = new AtomicLong(0);
        private final AtomicLong rejectCount = new AtomicLong(0);
        
        AdaptiveRateLimiter(String name, int targetQps, boolean adaptive) {
            this.name = name;
            this.targetQps = targetQps;
            this.adaptive = adaptive;
            this.tokens = new AtomicLong(targetQps);
            this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        }
        
        boolean tryAcquire() {
            refill();
            
            long current = tokens.get();
            if (current > 0 && tokens.compareAndSet(current, current - 1)) {
                acquireCount.incrementAndGet();
                return true;
            }
            
            rejectCount.incrementAndGet();
            return false;
        }
        
        private void refill() {
            long now = System.currentTimeMillis();
            long last = lastRefillTime.get();
            long elapsed = now - last;
            
            if (elapsed > 0 && lastRefillTime.compareAndSet(last, now)) {
                long tokensToAdd = (elapsed * targetQps) / 1000;
                long newTokens = Math.min(targetQps * 2, tokens.get() + tokensToAdd);
                tokens.set(newTokens);
            }
        }
        
        void adaptiveAdjust() {
            if (!adaptive) return;
            
            // 基于拒绝率调整
            long total = acquireCount.get() + rejectCount.get();
            if (total < 100) return;
            
            double rejectRate = (double) rejectCount.get() / total;
            
            // 如果拒绝率很低，可以适当提高限流阈值
            if (rejectRate < 0.01) {
                targetQps = (int) (targetQps * 1.1);
            }
            // 如果拒绝率很高，降低阈值
            else if (rejectRate > 0.1) {
                targetQps = (int) (targetQps * 0.9);
            }
        }
        
        RateLimiterStatus getStatus() {
            long total = acquireCount.get() + rejectCount.get();
            double rejectRate = total > 0 ? (double) rejectCount.get() / total * 100 : 0;
            
            return new RateLimiterStatus(name, targetQps, tokens.get(), rejectRate, total);
        }
    }
    
    /**
     * 隔离舱
     */
    private static class Bulkhead {
        private final String name;
        private final int maxConcurrent;
        private final Semaphore semaphore;
        
        private final AtomicLong acquireCount = new AtomicLong(0);
        private final AtomicLong rejectCount = new AtomicLong(0);
        
        Bulkhead(String name, int maxConcurrent) {
            this.name = name;
            this.maxConcurrent = maxConcurrent;
            this.semaphore = new Semaphore(maxConcurrent);
        }
        
        boolean tryAcquire() {
            if (semaphore.tryAcquire()) {
                acquireCount.incrementAndGet();
                return true;
            }
            rejectCount.incrementAndGet();
            return false;
        }
        
        void release() {
            semaphore.release();
        }
        
        BulkheadStatus getStatus() {
            int available = semaphore.availablePermits();
            int active = maxConcurrent - available;
            long total = acquireCount.get() + rejectCount.get();
            double rejectRate = total > 0 ? (double) rejectCount.get() / total * 100 : 0;
            
            return new BulkheadStatus(name, maxConcurrent, active, available, rejectRate);
        }
    }
    
    /**
     * 降级处理器接口
     */
    @FunctionalInterface
    public interface FallbackHandler<T> {
        T handle(String name, Throwable cause);
    }
    
    // ========== 状态记录 ==========
    
    public record CircuitBreakerStatus(
        String name, String state, double failureRate, double slowCallRate,
        int failureThreshold, int slowCallThreshold, long totalCalls
    ) {}
    
    public record RateLimiterStatus(
        String name, int targetQps, long availableTokens, double rejectRate, long totalRequests
    ) {}
    
    public record BulkheadStatus(
        String name, int maxConcurrent, int activeCalls, int availablePermits, double rejectRate
    ) {}
    
    public record ResilienceStats(
        long totalCalls, long successCalls, long failedCalls, long rejectedCalls,
        double successRate, int circuitBreakerCount, int rateLimiterCount, int bulkheadCount
    ) {}
}
