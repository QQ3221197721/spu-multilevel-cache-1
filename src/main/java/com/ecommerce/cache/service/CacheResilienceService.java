package com.ecommerce.cache.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreaker;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.EventObserverRegistry;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 缓存弹性保护服务
 * 集成 Sentinel 实现：
 * 1. 熔断降级：防止雪崩扩散（慢调用比例 + 异常比例）
 * 2. 流量控制：保护后端资源（QPS 限流）
 * 3. 系统保护：全局自适应保护
 * 4. 优雅降级：返回兜底数据
 */
@Service
public class CacheResilienceService {

    private static final Logger log = LoggerFactory.getLogger(CacheResilienceService.class);

    // Sentinel 资源名称
    private static final String RESOURCE_REDIS = "cache:redis";
    private static final String RESOURCE_MEMCACHED = "cache:memcached";
    private static final String RESOURCE_DB = "cache:database";
    private static final String RESOURCE_CACHE_READ = "cache:read";
    private static final String RESOURCE_DB_LOAD = "cache:db-load";

    // 熔断配置
    private static final double SLOW_CALL_RATIO_THRESHOLD = 0.8;
    private static final int SLOW_CALL_DURATION_MS = 100;
    private static final int MINIMUM_REQUESTS = 10;
    private static final int RECOVERY_TIMEOUT_SECONDS = 30;

    // 限流配置
    private static final double CACHE_READ_QPS = 10000;
    private static final double DB_LOAD_QPS = 1000;

    private final MeterRegistry meterRegistry;

    // 降级缓存（存储最近成功的数据）
    private final Map<String, String> fallbackCache = new ConcurrentHashMap<>();
    private static final int FALLBACK_CACHE_MAX_SIZE = 10000;

    // 熔断器状态缓存（用于指标暴露）
    private final Map<String, String> circuitStates = new ConcurrentHashMap<>();

    public CacheResilienceService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        initDegradeRules();
        initFlowRules();
        initSystemRules();
        registerCircuitBreakerObservers();
        registerMetrics();
        log.info("Sentinel 缓存弹性保护服务初始化完成");
    }

    /**
     * 初始化熔断降级规则
     */
    private void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        // Redis 熔断：慢调用比例
        DegradeRule redisRule = new DegradeRule(RESOURCE_REDIS)
                .setGrade(CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType())
                .setCount(SLOW_CALL_RATIO_THRESHOLD)
                .setSlowRatioThreshold(SLOW_CALL_RATIO_THRESHOLD)
                .setTimeWindow(RECOVERY_TIMEOUT_SECONDS)
                .setMinRequestAmount(MINIMUM_REQUESTS)
                .setStatIntervalMs(10000);
        rules.add(redisRule);

        // Memcached 熔断：慢调用比例
        DegradeRule memcachedRule = new DegradeRule(RESOURCE_MEMCACHED)
                .setGrade(CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType())
                .setCount(SLOW_CALL_RATIO_THRESHOLD)
                .setSlowRatioThreshold(SLOW_CALL_RATIO_THRESHOLD)
                .setTimeWindow(RECOVERY_TIMEOUT_SECONDS)
                .setMinRequestAmount(MINIMUM_REQUESTS)
                .setStatIntervalMs(10000);
        rules.add(memcachedRule);

        // Database 熔断：异常比例
        DegradeRule dbRule = new DegradeRule(RESOURCE_DB)
                .setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType())
                .setCount(0.5)
                .setTimeWindow(RECOVERY_TIMEOUT_SECONDS)
                .setMinRequestAmount(MINIMUM_REQUESTS)
                .setStatIntervalMs(10000);
        rules.add(dbRule);

        DegradeRuleManager.loadRules(rules);
        log.info("Sentinel 熔断规则已加载: redis/memcached(慢调用比例), database(异常比例)");
    }

    /**
     * 初始化流量控制规则
     */
    private void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        // 缓存读取 QPS 限流
        FlowRule cacheReadRule = new FlowRule(RESOURCE_CACHE_READ)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(CACHE_READ_QPS)
                .setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_WARM_UP)
                .setWarmUpPeriodSec(10);
        rules.add(cacheReadRule);

        // 数据库回源 QPS 限流（更严格）
        FlowRule dbLoadRule = new FlowRule(RESOURCE_DB_LOAD)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(DB_LOAD_QPS)
                .setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER)
                .setMaxQueueingTimeMs(100);
        rules.add(dbLoadRule);

        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 流控规则已加载: cacheRead={}qps, dbLoad={}qps", CACHE_READ_QPS, DB_LOAD_QPS);
    }

    /**
     * 初始化系统保护规则
     */
    private void initSystemRules() {
        List<SystemRule> rules = new ArrayList<>();
        SystemRule systemRule = new SystemRule();
        systemRule.setHighestSystemLoad(10.0);
        systemRule.setHighestCpuUsage(0.9);
        systemRule.setAvgRt(200);
        systemRule.setMaxThread(500);
        systemRule.setQps(50000);
        rules.add(systemRule);
        SystemRuleManager.loadRules(rules);
    }

    /**
     * 注册熔断器状态变化观察者
     */
    private void registerCircuitBreakerObservers() {
        EventObserverRegistry.getInstance().addStateChangeObserver("metricsObserver",
                (prevState, newState, rule, snapshotValue) -> {
                    String resource = rule.getResource();
                    circuitStates.put(resource, newState.name());
                    log.warn("Sentinel 熔断器状态变化: resource={}, {} -> {}",
                            resource, prevState.name(), newState.name());
                });
        // 初始化状态
        circuitStates.put(RESOURCE_REDIS, "CLOSED");
        circuitStates.put(RESOURCE_MEMCACHED, "CLOSED");
        circuitStates.put(RESOURCE_DB, "CLOSED");
    }

    private void registerMetrics() {
        meterRegistry.gauge("cache.sentinel.redis.state", circuitStates,
                m -> stateToOrder(m.getOrDefault(RESOURCE_REDIS, "CLOSED")));
        meterRegistry.gauge("cache.sentinel.memcached.state", circuitStates,
                m -> stateToOrder(m.getOrDefault(RESOURCE_MEMCACHED, "CLOSED")));
        meterRegistry.gauge("cache.sentinel.db.state", circuitStates,
                m -> stateToOrder(m.getOrDefault(RESOURCE_DB, "CLOSED")));
    }

    private double stateToOrder(String state) {
        return switch (state) {
            case "CLOSED" -> 0;
            case "HALF_OPEN" -> 1;
            case "OPEN" -> 2;
            default -> -1;
        };
    }

    /**
     * 带熔断保护的 Redis 读取
     */
    public <T> T executeRedisWithProtection(Supplier<T> action, Supplier<T> fallback) {
        // 先检查流量控制
        try (Entry flowEntry = SphU.entry(RESOURCE_CACHE_READ)) {
            // 再检查 Redis 熔断
            try (Entry cbEntry = SphU.entry(RESOURCE_REDIS)) {
                return action.get();
            } catch (BlockException e) {
                log.debug("Redis 熔断器已打开，使用降级数据");
                return fallback.get();
            } catch (Exception e) {
                Tracer.trace(e);
                log.warn("Redis 操作失败，使用降级数据", e);
                return fallback.get();
            }
        } catch (BlockException e) {
            log.debug("缓存读取限流，使用降级数据");
            return fallback.get();
        }
    }

    /**
     * 带熔断保护的 Memcached 读取
     */
    public <T> T executeMemcachedWithProtection(Supplier<T> action, Supplier<T> fallback) {
        try (Entry entry = SphU.entry(RESOURCE_MEMCACHED)) {
            return action.get();
        } catch (BlockException e) {
            log.debug("Memcached 熔断器已打开，使用降级数据");
            return fallback.get();
        } catch (Exception e) {
            Tracer.trace(e);
            log.warn("Memcached 操作失败，使用降级数据", e);
            return fallback.get();
        }
    }

    /**
     * 带熔断保护的数据库回源
     */
    public <T> T executeDbLoadWithProtection(Supplier<T> action, Supplier<T> fallback) {
        // 数据库限流
        try (Entry flowEntry = SphU.entry(RESOURCE_DB_LOAD)) {
            // 数据库熔断
            try (Entry cbEntry = SphU.entry(RESOURCE_DB)) {
                return action.get();
            } catch (BlockException e) {
                log.warn("数据库熔断器已打开，使用降级数据");
                return fallback.get();
            } catch (Exception e) {
                Tracer.trace(e);
                log.error("数据库操作失败，使用降级数据", e);
                return fallback.get();
            }
        } catch (BlockException e) {
            log.warn("数据库回源限流");
            return fallback.get();
        }
    }

    /**
     * 缓存降级数据
     */
    public void cacheFallbackData(String key, String value) {
        if (fallbackCache.size() >= FALLBACK_CACHE_MAX_SIZE) {
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
                circuitStates.getOrDefault(RESOURCE_REDIS, "CLOSED"),
                circuitStates.getOrDefault(RESOURCE_MEMCACHED, "CLOSED"),
                circuitStates.getOrDefault(RESOURCE_DB, "CLOSED"),
                0f, // Sentinel 通过 Dashboard 查看详细指标
                0f,
                (int) CACHE_READ_QPS,
                (int) DB_LOAD_QPS,
                0, // Sentinel 并发控制由系统保护实现
                0,
                fallbackCache.size()
        );
    }

    /**
     * 手动重置熔断器（用于紧急恢复）
     * Sentinel 熔断器通过重新加载规则实现重置
     */
    public void resetCircuitBreaker(String name) {
        switch (name.toLowerCase()) {
            case "redis" -> {
                circuitStates.put(RESOURCE_REDIS, "CLOSED");
                initDegradeRules(); // 重新加载规则以重置熔断器
                log.info("Redis Sentinel 熔断器已重置");
            }
            case "memcached" -> {
                circuitStates.put(RESOURCE_MEMCACHED, "CLOSED");
                initDegradeRules();
                log.info("Memcached Sentinel 熔断器已重置");
            }
            case "database", "db" -> {
                circuitStates.put(RESOURCE_DB, "CLOSED");
                initDegradeRules();
                log.info("Database Sentinel 熔断器已重置");
            }
            default -> log.warn("未知的熔断器: {}", name);
        }
    }

    /**
     * 强制开启熔断器（用于紧急保护）
     * Sentinel 通过设置极低阈值触发熔断
     */
    public void forceOpenCircuitBreaker(String name) {
        String resource = switch (name.toLowerCase()) {
            case "redis" -> RESOURCE_REDIS;
            case "memcached" -> RESOURCE_MEMCACHED;
            case "database", "db" -> RESOURCE_DB;
            default -> {
                log.warn("未知的熔断器: {}", name);
                yield null;
            }
        };
        if (resource != null) {
            // 设置极低阈值（异常比例 0%）强制触发熔断
            List<DegradeRule> currentRules = new ArrayList<>(DegradeRuleManager.getRules());
            currentRules.removeIf(r -> r.getResource().equals(resource));
            DegradeRule forceOpen = new DegradeRule(resource)
                    .setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType())
                    .setCount(0.0) // 0% 异常比例即触发
                    .setTimeWindow(3600) // 1小时恢复
                    .setMinRequestAmount(1)
                    .setStatIntervalMs(1000);
            currentRules.add(forceOpen);
            DegradeRuleManager.loadRules(currentRules);
            circuitStates.put(resource, "OPEN");
            log.warn("{} Sentinel 熔断器已强制打开", name);
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
