package com.ecommerce.cache.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 缓存弹性保护服务
 * 集成 Resilience4j 实现：
 * 1. 熔断器：防止雪崩扩散
 * 2. 限流器：保护后端资源
 * 3. 舱壁隔离：资源隔离
 * 4. 优雅降级：返回兜底数据
 */
@Service
public class CacheResilienceService {
    
    private static final Logger log = LoggerFactory.getLogger(CacheResilienceService.class);
    
    // 熔断器配置
    private static final float FAILURE_RATE_THRESHOLD = 50.0f;
    private static final int SLOW_CALL_RATE_THRESHOLD = 80;
    private static final Duration SLOW_CALL_DURATION_THRESHOLD = Duration.ofMillis(100);
    private static final int MINIMUM_CALLS = 10;
    private static final Duration WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(30);
    
    // 限流配置
    private static final int RATE_LIMIT_FOR_PERIOD = 10000; // 每秒 10000 请求
    private static final Duration RATE_LIMIT_REFRESH_PERIOD = Duration.ofSeconds(1);
    
    // 舱壁配置
    private static final int MAX_CONCURRENT_CALLS = 500;
    private static final Duration MAX_WAIT_DURATION = Duration.ofMillis(50);
    
    private final MeterRegistry meterRegistry;
    
    // 各组件的熔断器
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private RateLimiterRegistry rateLimiterRegistry;
    private BulkheadRegistry bulkheadRegistry;
    
    // 缓存组件熔断器
    private CircuitBreaker redisCircuitBreaker;
    private CircuitBreaker memcachedCircuitBreaker;
    private CircuitBreaker dbCircuitBreaker;
    
    // 限流器
    private RateLimiter cacheReadRateLimiter;
    private RateLimiter dbLoadRateLimiter;
    
    // 舱壁
    private Bulkhead redisBulkhead;
    private Bulkhead memcachedBulkhead;
    
    // 降级缓存（存储最近成功的数据）
    private final Map<String, String> fallbackCache = new ConcurrentHashMap<>();
    private static final int FALLBACK_CACHE_MAX_SIZE = 10000;

    public CacheResilienceService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        initCircuitBreakers();
        initRateLimiters();
        initBulkheads();
        registerMetrics();
        log.info("Cache resilience service initialized");
    }

    private void initCircuitBreakers() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(FAILURE_RATE_THRESHOLD)
            .slowCallRateThreshold(SLOW_CALL_RATE_THRESHOLD)
            .slowCallDurationThreshold(SLOW_CALL_DURATION_THRESHOLD)
            .minimumNumberOfCalls(MINIMUM_CALLS)
            .waitDurationInOpenState(WAIT_DURATION_IN_OPEN_STATE)
            .permittedNumberOfCallsInHalfOpenState(5)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(100)
            .build();
        
        circuitBreakerRegistry = CircuitBreakerRegistry.of(config);
        
        redisCircuitBreaker = circuitBreakerRegistry.circuitBreaker("redis");
        memcachedCircuitBreaker = circuitBreakerRegistry.circuitBreaker("memcached");
        dbCircuitBreaker = circuitBreakerRegistry.circuitBreaker("database");
        
        // 注册状态变化监听
        redisCircuitBreaker.getEventPublisher()
            .onStateTransition(event -> 
                log.warn("Redis circuit breaker state changed: {} -> {}", 
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));
    }

    private void initRateLimiters() {
        RateLimiterConfig cacheConfig = RateLimiterConfig.custom()
            .limitForPeriod(RATE_LIMIT_FOR_PERIOD)
            .limitRefreshPeriod(RATE_LIMIT_REFRESH_PERIOD)
            .timeoutDuration(Duration.ofMillis(10))
            .build();
        
        RateLimiterConfig dbConfig = RateLimiterConfig.custom()
            .limitForPeriod(1000) // 数据库限流更严格
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ofMillis(100))
            .build();
        
        rateLimiterRegistry = RateLimiterRegistry.of(cacheConfig);
        
        cacheReadRateLimiter = rateLimiterRegistry.rateLimiter("cacheRead", cacheConfig);
        dbLoadRateLimiter = rateLimiterRegistry.rateLimiter("dbLoad", dbConfig);
    }

    private void initBulkheads() {
        BulkheadConfig redisConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(MAX_CONCURRENT_CALLS)
            .maxWaitDuration(MAX_WAIT_DURATION)
            .build();
        
        BulkheadConfig mcConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(200)
            .maxWaitDuration(Duration.ofMillis(30))
            .build();
        
        bulkheadRegistry = BulkheadRegistry.of(redisConfig);
        
        redisBulkhead = bulkheadRegistry.bulkhead("redis", redisConfig);
        memcachedBulkhead = bulkheadRegistry.bulkhead("memcached", mcConfig);
    }

    private void registerMetrics() {
        // 注册熔断器指标
        meterRegistry.gauge("cache.circuit.redis.state", redisCircuitBreaker, 
            cb -> cb.getState().getOrder());
        meterRegistry.gauge("cache.circuit.memcached.state", memcachedCircuitBreaker, 
            cb -> cb.getState().getOrder());
        meterRegistry.gauge("cache.circuit.db.state", dbCircuitBreaker, 
            cb -> cb.getState().getOrder());
        
        // 注册限流器指标
        meterRegistry.gauge("cache.ratelimit.available", cacheReadRateLimiter,
            rl -> rl.getMetrics().getAvailablePermissions());
    }

    /**
     * 带熔断保护的 Redis 读取
     */
    public <T> T executeRedisWithProtection(Supplier<T> action, Supplier<T> fallback) {
        try {
            // 检查限流
            if (!cacheReadRateLimiter.acquirePermission()) {
                log.debug("Rate limit exceeded for cache read");
                return fallback.get();
            }
            
            // 舱壁隔离
            return Bulkhead.decorateSupplier(redisBulkhead,
                // 熔断器保护
                CircuitBreaker.decorateSupplier(redisCircuitBreaker, action)
            ).get();
            
        } catch (Exception e) {
            log.warn("Redis operation failed, using fallback", e);
            return fallback.get();
        }
    }

    /**
     * 带熔断保护的 Memcached 读取
     */
    public <T> T executeMemcachedWithProtection(Supplier<T> action, Supplier<T> fallback) {
        try {
            return Bulkhead.decorateSupplier(memcachedBulkhead,
                CircuitBreaker.decorateSupplier(memcachedCircuitBreaker, action)
            ).get();
        } catch (Exception e) {
            log.warn("Memcached operation failed, using fallback", e);
            return fallback.get();
        }
    }

    /**
     * 带熔断保护的数据库回源
     */
    public <T> T executeDbLoadWithProtection(Supplier<T> action, Supplier<T> fallback) {
        try {
            // 数据库限流
            if (!dbLoadRateLimiter.acquirePermission()) {
                log.warn("DB load rate limit exceeded");
                return fallback.get();
            }
            
            return CircuitBreaker.decorateSupplier(dbCircuitBreaker, action).get();
            
        } catch (Exception e) {
            log.error("DB operation failed, using fallback", e);
            return fallback.get();
        }
    }

    /**
     * 缓存降级数据
     */
    public void cacheFallbackData(String key, String value) {
        if (fallbackCache.size() >= FALLBACK_CACHE_MAX_SIZE) {
            // 简单的 LRU 清理
            fallbackCache.entrySet().stream()
                .limit(FALLBACK_CACHE_MAX_SIZE / 10)
                .forEach(e -> fallbackCache.remove(e.getKey()));
        }
        fallbackCache.put(key, value);
    }

    /**
     * 获取降级数据
     */
    public String getFallbackData(String key) {
        return fallbackCache.get(key);
    }

    /**
     * 获取弹性保护统计
     */
    public ResilienceStats getStats() {
        return new ResilienceStats(
            redisCircuitBreaker.getState().name(),
            memcachedCircuitBreaker.getState().name(),
            dbCircuitBreaker.getState().name(),
            redisCircuitBreaker.getMetrics().getFailureRate(),
            redisCircuitBreaker.getMetrics().getSlowCallRate(),
            cacheReadRateLimiter.getMetrics().getAvailablePermissions(),
            dbLoadRateLimiter.getMetrics().getAvailablePermissions(),
            redisBulkhead.getMetrics().getAvailableConcurrentCalls(),
            memcachedBulkhead.getMetrics().getAvailableConcurrentCalls(),
            fallbackCache.size()
        );
    }

    /**
     * 手动重置熔断器（用于紧急恢复）
     */
    public void resetCircuitBreaker(String name) {
        switch (name.toLowerCase()) {
            case "redis" -> {
                redisCircuitBreaker.reset();
                log.info("Redis circuit breaker reset");
            }
            case "memcached" -> {
                memcachedCircuitBreaker.reset();
                log.info("Memcached circuit breaker reset");
            }
            case "database", "db" -> {
                dbCircuitBreaker.reset();
                log.info("Database circuit breaker reset");
            }
            default -> log.warn("Unknown circuit breaker: {}", name);
        }
    }

    /**
     * 强制开启熔断器（用于紧急保护）
     */
    public void forceOpenCircuitBreaker(String name) {
        switch (name.toLowerCase()) {
            case "redis" -> {
                redisCircuitBreaker.transitionToOpenState();
                log.warn("Redis circuit breaker forced OPEN");
            }
            case "memcached" -> {
                memcachedCircuitBreaker.transitionToOpenState();
                log.warn("Memcached circuit breaker forced OPEN");
            }
            case "database", "db" -> {
                dbCircuitBreaker.transitionToOpenState();
                log.warn("Database circuit breaker forced OPEN");
            }
            default -> log.warn("Unknown circuit breaker: {}", name);
        }
    }

    public record ResilienceStats(
        String redisCircuitState,
        String memcachedCircuitState,
        String dbCircuitState,
        float redisFailureRate,
        float redisSlowCallRate,
        int cacheRateLimitAvailable,
        int dbRateLimitAvailable,
        int redisBulkheadAvailable,
        int memcachedBulkheadAvailable,
        int fallbackCacheSize
    ) {}
}
