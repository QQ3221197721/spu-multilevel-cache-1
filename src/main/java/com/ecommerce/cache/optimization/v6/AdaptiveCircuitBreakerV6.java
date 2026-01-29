package com.ecommerce.cache.optimization.v6;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/**
 * 自适应熔断器 V6 - 智能熔断
 * 
 * 核心特性:
 * 1. 多维度健康指标
 * 2. 自适应阈值调整
 * 3. 渐进式恢复
 * 4. 异常分类处理
 * 5. 时间窗口滑动
 * 6. 智能降级策略
 * 
 * 性能目标:
 * - 熔断响应 < 1ms
 * - 误判率 < 1%
 * - 恢复时间优化 50%
 */
public class AdaptiveCircuitBreakerV6 {
    
    private static final Logger log = LoggerFactory.getLogger(AdaptiveCircuitBreakerV6.class);
    
    // 配置
    @Value("${optimization.v6.circuit.failure-threshold:0.5}")
    private double failureThreshold = 0.5;
    
    @Value("${optimization.v6.circuit.slow-call-threshold:0.8}")
    private double slowCallThreshold = 0.8;
    
    @Value("${optimization.v6.circuit.slow-call-duration-ms:500}")
    private long slowCallDurationMs = 500;
    
    @Value("${optimization.v6.circuit.minimum-calls:10}")
    private int minimumCalls = 10;
    
    @Value("${optimization.v6.circuit.window-size-seconds:60}")
    private int windowSizeSeconds = 60;
    
    @Value("${optimization.v6.circuit.wait-duration-seconds:30}")
    private int waitDurationSeconds = 30;
    
    @Value("${optimization.v6.circuit.permitted-calls-half-open:5}")
    private int permittedCallsHalfOpen = 5;
    
    @Value("${optimization.v6.circuit.success-threshold-close:0.8}")
    private double successThresholdClose = 0.8;
    
    private final MeterRegistry meterRegistry;
    
    // 各服务的熔断器状态
    private final ConcurrentHashMap<String, CircuitBreakerState> breakers = new ConcurrentHashMap<>();
    
    // 全局指标
    private final LongAdder totalCalls = new LongAdder();
    private final LongAdder successfulCalls = new LongAdder();
    private final LongAdder failedCalls = new LongAdder();
    private final LongAdder rejectedCalls = new LongAdder();
    private final LongAdder slowCalls = new LongAdder();
    
    // 执行器
    private ScheduledExecutorService scheduledExecutor;
    
    // 指标
    private Timer callTimer;
    private Counter stateChangeCounter;
    
    public AdaptiveCircuitBreakerV6(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    @PostConstruct
    public void init() {
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("circuit-breaker").factory());
        
        // 定期检查半开状态
        scheduledExecutor.scheduleAtFixedRate(this::checkHalfOpenTransitions, 
            1, 1, TimeUnit.SECONDS);
        
        // 定期自适应调整
        scheduledExecutor.scheduleAtFixedRate(this::adaptiveAdjust, 
            30, 30, TimeUnit.SECONDS);
        
        // 注册指标
        registerMetrics();
        
        log.info("AdaptiveCircuitBreakerV6 initialized: failureThreshold={}, slowThreshold={}",
            failureThreshold, slowCallThreshold);
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
    }
    
    private void registerMetrics() {
        callTimer = Timer.builder("circuit.breaker.call.latency")
            .publishPercentileHistogram()
            .publishPercentiles(0.5, 0.9, 0.95, 0.99)
            .register(meterRegistry);
        stateChangeCounter = Counter.builder("circuit.breaker.state.changes")
            .register(meterRegistry);
        
        Gauge.builder("circuit.breaker.open.count", breakers, 
            b -> b.values().stream().filter(s -> s.state == State.OPEN).count())
            .register(meterRegistry);
        Gauge.builder("circuit.breaker.half.open.count", breakers,
            b -> b.values().stream().filter(s -> s.state == State.HALF_OPEN).count())
            .register(meterRegistry);
    }
    
    /**
     * 执行带熔断保护的调用
     */
    public <T> T executeWithProtection(String name, Supplier<T> supplier, Supplier<T> fallback) {
        CircuitBreakerState breaker = getOrCreateBreaker(name);
        totalCalls.increment();
        
        // 检查是否允许调用
        if (!breaker.allowRequest()) {
            rejectedCalls.increment();
            log.debug("Circuit breaker rejected: name={}, state={}", name, breaker.state);
            return fallback.get();
        }
        
        long startTime = System.nanoTime();
        try {
            T result = callTimer.record(supplier);
            long duration = (System.nanoTime() - startTime) / 1_000_000;
            
            // 记录成功
            breaker.recordSuccess(duration);
            successfulCalls.increment();
            
            if (duration > slowCallDurationMs) {
                slowCalls.increment();
            }
            
            return result;
            
        } catch (Exception e) {
            long duration = (System.nanoTime() - startTime) / 1_000_000;
            
            // 记录失败
            breaker.recordFailure(duration, e);
            failedCalls.increment();
            
            log.debug("Circuit breaker recorded failure: name={}, error={}", name, e.getMessage());
            return fallback.get();
        }
    }
    
    /**
     * 检查服务是否可用
     */
    public boolean isAvailable(String name) {
        CircuitBreakerState breaker = breakers.get(name);
        return breaker == null || breaker.state != State.OPEN;
    }
    
    /**
     * 获取熔断器状态
     */
    public State getState(String name) {
        CircuitBreakerState breaker = breakers.get(name);
        return breaker != null ? breaker.state : State.CLOSED;
    }
    
    /**
     * 强制打开熔断器
     */
    public void forceOpen(String name) {
        CircuitBreakerState breaker = getOrCreateBreaker(name);
        breaker.transitionTo(State.OPEN);
        log.warn("Circuit breaker forced open: name={}", name);
    }
    
    /**
     * 强制关闭熔断器
     */
    public void forceClose(String name) {
        CircuitBreakerState breaker = getOrCreateBreaker(name);
        breaker.transitionTo(State.CLOSED);
        log.info("Circuit breaker forced closed: name={}", name);
    }
    
    /**
     * 重置熔断器
     */
    public void reset(String name) {
        breakers.remove(name);
        log.info("Circuit breaker reset: name={}", name);
    }
    
    private CircuitBreakerState getOrCreateBreaker(String name) {
        return breakers.computeIfAbsent(name, k -> new CircuitBreakerState(name));
    }
    
    /**
     * 检查半开状态转换
     */
    private void checkHalfOpenTransitions() {
        long now = System.currentTimeMillis();
        
        for (CircuitBreakerState breaker : breakers.values()) {
            if (breaker.state == State.OPEN) {
                if (now - breaker.openedAt >= waitDurationSeconds * 1000L) {
                    breaker.transitionTo(State.HALF_OPEN);
                }
            }
        }
    }
    
    /**
     * 自适应调整阈值
     */
    private void adaptiveAdjust() {
        for (CircuitBreakerState breaker : breakers.values()) {
            breaker.adaptiveAdjust();
        }
    }
    
    /**
     * 获取统计信息
     */
    public CircuitBreakerStats getStats() {
        long open = breakers.values().stream().filter(s -> s.state == State.OPEN).count();
        long halfOpen = breakers.values().stream().filter(s -> s.state == State.HALF_OPEN).count();
        long closed = breakers.values().stream().filter(s -> s.state == State.CLOSED).count();
        
        return new CircuitBreakerStats(
            totalCalls.sum(),
            successfulCalls.sum(),
            failedCalls.sum(),
            rejectedCalls.sum(),
            slowCalls.sum(),
            breakers.size(),
            (int) open,
            (int) halfOpen,
            (int) closed,
            calculateSuccessRate()
        );
    }
    
    /**
     * 获取所有熔断器详情
     */
    public List<BreakerDetail> getAllBreakerDetails() {
        return breakers.values().stream()
            .map(b -> new BreakerDetail(
                b.name,
                b.state,
                b.getFailureRate(),
                b.getSlowCallRate(),
                b.totalCalls.sum(),
                b.failedCalls.sum(),
                b.slowCalls.sum()
            ))
            .toList();
    }
    
    private double calculateSuccessRate() {
        long total = totalCalls.sum();
        return total > 0 ? (double) successfulCalls.sum() / total : 1.0;
    }
    
    // ========== 内部类 ==========
    
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
    
    /**
     * 熔断器状态
     */
    private class CircuitBreakerState {
        final String name;
        volatile State state = State.CLOSED;
        volatile long openedAt = 0;
        
        // 滑动窗口计数
        final LongAdder totalCalls = new LongAdder();
        final LongAdder failedCalls = new LongAdder();
        final LongAdder slowCalls = new LongAdder();
        final LongAdder successCalls = new LongAdder();
        
        // 半开状态计数
        final AtomicInteger halfOpenCalls = new AtomicInteger(0);
        final AtomicInteger halfOpenSuccess = new AtomicInteger(0);
        
        // 滑动窗口
        final ConcurrentLinkedDeque<CallRecord> callHistory = new ConcurrentLinkedDeque<>();
        
        // 自适应阈值
        volatile double adaptedFailureThreshold = failureThreshold;
        volatile double adaptedSlowThreshold = slowCallThreshold;
        
        CircuitBreakerState(String name) {
            this.name = name;
        }
        
        boolean allowRequest() {
            switch (state) {
                case CLOSED:
                    return true;
                case OPEN:
                    return false;
                case HALF_OPEN:
                    return halfOpenCalls.incrementAndGet() <= permittedCallsHalfOpen;
                default:
                    return true;
            }
        }
        
        void recordSuccess(long durationMs) {
            totalCalls.increment();
            successCalls.increment();
            
            boolean isSlow = durationMs > slowCallDurationMs;
            if (isSlow) {
                slowCalls.increment();
            }
            
            callHistory.addLast(new CallRecord(System.currentTimeMillis(), true, isSlow, durationMs));
            cleanupHistory();
            
            if (state == State.HALF_OPEN) {
                halfOpenSuccess.incrementAndGet();
                checkHalfOpenClose();
            }
            
            checkStateTransition();
        }
        
        void recordFailure(long durationMs, Exception e) {
            totalCalls.increment();
            failedCalls.increment();
            
            boolean isSlow = durationMs > slowCallDurationMs;
            if (isSlow) {
                slowCalls.increment();
            }
            
            callHistory.addLast(new CallRecord(System.currentTimeMillis(), false, isSlow, durationMs));
            cleanupHistory();
            
            if (state == State.HALF_OPEN) {
                // 半开状态失败，立即打开
                transitionTo(State.OPEN);
            } else {
                checkStateTransition();
            }
        }
        
        void transitionTo(State newState) {
            if (state != newState) {
                State oldState = state;
                state = newState;
                
                if (newState == State.OPEN) {
                    openedAt = System.currentTimeMillis();
                } else if (newState == State.HALF_OPEN) {
                    halfOpenCalls.set(0);
                    halfOpenSuccess.set(0);
                } else if (newState == State.CLOSED) {
                    // 重置计数
                    failedCalls.reset();
                    slowCalls.reset();
                    totalCalls.reset();
                    successCalls.reset();
                }
                
                stateChangeCounter.increment();
                log.info("Circuit breaker state changed: name={}, {} -> {}", name, oldState, newState);
            }
        }
        
        void checkStateTransition() {
            if (state != State.CLOSED) return;
            
            long total = getWindowTotal();
            if (total < minimumCalls) return;
            
            double failureRate = getFailureRate();
            double slowRate = getSlowCallRate();
            
            if (failureRate >= adaptedFailureThreshold || slowRate >= adaptedSlowThreshold) {
                transitionTo(State.OPEN);
            }
        }
        
        void checkHalfOpenClose() {
            if (halfOpenCalls.get() >= permittedCallsHalfOpen) {
                double successRate = (double) halfOpenSuccess.get() / halfOpenCalls.get();
                if (successRate >= successThresholdClose) {
                    transitionTo(State.CLOSED);
                } else {
                    transitionTo(State.OPEN);
                }
            }
        }
        
        void adaptiveAdjust() {
            // 根据历史数据调整阈值
            double avgFailureRate = getWindowFailureRate();
            double avgSlowRate = getWindowSlowRate();
            
            // 平滑调整
            if (avgFailureRate < failureThreshold * 0.5) {
                // 服务稳定，可以略微放宽阈值
                adaptedFailureThreshold = Math.min(failureThreshold * 1.1, 0.8);
            } else {
                adaptedFailureThreshold = failureThreshold;
            }
            
            if (avgSlowRate < slowCallThreshold * 0.5) {
                adaptedSlowThreshold = Math.min(slowCallThreshold * 1.1, 0.95);
            } else {
                adaptedSlowThreshold = slowCallThreshold;
            }
        }
        
        double getFailureRate() {
            long total = getWindowTotal();
            return total > 0 ? (double) getWindowFailures() / total : 0;
        }
        
        double getSlowCallRate() {
            long total = getWindowTotal();
            return total > 0 ? (double) getWindowSlowCalls() / total : 0;
        }
        
        long getWindowTotal() {
            return callHistory.size();
        }
        
        long getWindowFailures() {
            return callHistory.stream().filter(r -> !r.success).count();
        }
        
        long getWindowSlowCalls() {
            return callHistory.stream().filter(r -> r.slow).count();
        }
        
        double getWindowFailureRate() {
            long total = getWindowTotal();
            return total > 0 ? (double) getWindowFailures() / total : 0;
        }
        
        double getWindowSlowRate() {
            long total = getWindowTotal();
            return total > 0 ? (double) getWindowSlowCalls() / total : 0;
        }
        
        void cleanupHistory() {
            long cutoff = System.currentTimeMillis() - windowSizeSeconds * 1000L;
            while (!callHistory.isEmpty()) {
                CallRecord first = callHistory.peekFirst();
                if (first != null && first.timestamp < cutoff) {
                    callHistory.pollFirst();
                } else {
                    break;
                }
            }
        }
    }
    
    private record CallRecord(long timestamp, boolean success, boolean slow, long durationMs) {}
    
    public record CircuitBreakerStats(
        long totalCalls,
        long successfulCalls,
        long failedCalls,
        long rejectedCalls,
        long slowCalls,
        int totalBreakers,
        int openBreakers,
        int halfOpenBreakers,
        int closedBreakers,
        double successRate
    ) {}
    
    public record BreakerDetail(
        String name,
        State state,
        double failureRate,
        double slowCallRate,
        long totalCalls,
        long failedCalls,
        long slowCalls
    ) {}
}
