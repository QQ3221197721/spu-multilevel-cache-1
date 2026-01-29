package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 自适应智能限流器
 * 
 * 核心特性:
 * 1. 滑动窗口限流: 精确的时间窗口计数
 * 2. 令牌桶算法: 平滑突发流量
 * 3. 漏桶算法: 恒定速率输出
 * 4. 自适应调整: 根据系统负载动态调整
 * 5. 多维度限流: 按Key、用户、IP等维度
 * 6. 分布式限流: 基于Redis的集群限流
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdaptiveRateLimiter {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    
    // ========== 限流器存储 ==========
    
    /** 本地限流器 */
    private final ConcurrentMap<String, RateLimiterBucket> localLimiters = new ConcurrentHashMap<>();
    
    /** 规则配置 */
    private final ConcurrentMap<String, RateLimitRule> rules = new ConcurrentHashMap<>();
    
    /** 统计计数 */
    private final ConcurrentMap<String, LongAdder> permitCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LongAdder> rejectCounts = new ConcurrentHashMap<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 全局计数 */
    private final AtomicLong totalPermits = new AtomicLong(0);
    private final AtomicLong totalRejects = new AtomicLong(0);
    
    /** 系统负载因子 */
    private volatile double systemLoadFactor = 1.0;
    
    // ========== 配置 ==========
    
    private static final String REDIS_LIMIT_PREFIX = "v7:limit:";
    private static final int DEFAULT_QPS = 1000;
    private static final int DEFAULT_BURST = 2000;
    
    // ========== 指标 ==========
    
    private Counter permitCounter;
    private Counter rejectCounter;
    
    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "rate-limiter-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        initDefaultRules();
        
        // 启动自适应调整任务
        scheduler.scheduleWithFixedDelay(
            this::adaptiveTuning,
            10,
            10,
            TimeUnit.SECONDS
        );
        
        // 启动清理任务
        scheduler.scheduleWithFixedDelay(
            this::cleanupExpiredLimiters,
            60,
            60,
            TimeUnit.SECONDS
        );
        
        log.info("[自适应限流器] 初始化完成");
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        log.info("[自适应限流器] 已关闭 - 总放行: {}, 总拒绝: {}",
            totalPermits.get(), totalRejects.get());
    }
    
    private void initMetrics() {
        permitCounter = Counter.builder("cache.ratelimit.permit")
            .description("限流放行次数")
            .register(meterRegistry);
        
        rejectCounter = Counter.builder("cache.ratelimit.reject")
            .description("限流拒绝次数")
            .register(meterRegistry);
        
        Gauge.builder("cache.ratelimit.load_factor", () -> systemLoadFactor)
            .description("系统负载因子")
            .register(meterRegistry);
    }
    
    private void initDefaultRules() {
        // 默认规则
        addRule("default", RateLimitRule.builder()
            .name("default")
            .qps(DEFAULT_QPS)
            .burst(DEFAULT_BURST)
            .algorithm(Algorithm.TOKEN_BUCKET)
            .build());
        
        // 热点Key规则
        addRule("hotkey", RateLimitRule.builder()
            .name("hotkey")
            .qps(500)
            .burst(800)
            .algorithm(Algorithm.SLIDING_WINDOW)
            .build());
        
        // 写操作规则
        addRule("write", RateLimitRule.builder()
            .name("write")
            .qps(200)
            .burst(300)
            .algorithm(Algorithm.LEAKY_BUCKET)
            .build());
    }
    
    // ========== 核心API ==========
    
    /**
     * 尝试获取许可
     */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, "default");
    }
    
    /**
     * 尝试获取许可(指定规则)
     */
    public boolean tryAcquire(String key, String ruleName) {
        RateLimitRule rule = rules.getOrDefault(ruleName, rules.get("default"));
        
        // 应用系统负载因子
        int effectiveQps = (int) (rule.qps * systemLoadFactor);
        
        RateLimiterBucket bucket = localLimiters.computeIfAbsent(
            key + ":" + ruleName,
            k -> createBucket(rule, effectiveQps)
        );
        
        boolean permitted = bucket.tryAcquire();
        
        // 更新统计
        if (permitted) {
            totalPermits.incrementAndGet();
            permitCounter.increment();
            permitCounts.computeIfAbsent(ruleName, k -> new LongAdder()).increment();
        } else {
            totalRejects.incrementAndGet();
            rejectCounter.increment();
            rejectCounts.computeIfAbsent(ruleName, k -> new LongAdder()).increment();
        }
        
        return permitted;
    }
    
    /**
     * 尝试获取许可(分布式)
     */
    public boolean tryAcquireDistributed(String key, String ruleName) {
        RateLimitRule rule = rules.getOrDefault(ruleName, rules.get("default"));
        
        String redisKey = REDIS_LIMIT_PREFIX + ruleName + ":" + key;
        int effectiveQps = (int) (rule.qps * systemLoadFactor);
        
        try {
            // 使用Redis进行分布式限流
            Long count = redisTemplate.opsForValue().increment(redisKey);
            
            if (count == 1) {
                // 第一次访问，设置过期时间
                redisTemplate.expire(redisKey, Duration.ofSeconds(1));
            }
            
            boolean permitted = count != null && count <= effectiveQps;
            
            if (permitted) {
                totalPermits.incrementAndGet();
                permitCounter.increment();
            } else {
                totalRejects.incrementAndGet();
                rejectCounter.increment();
            }
            
            return permitted;
            
        } catch (Exception e) {
            log.warn("[自适应限流器] Redis限流失败，降级到本地: {}", e.getMessage());
            return tryAcquire(key, ruleName);
        }
    }
    
    /**
     * 批量检查
     */
    public Map<String, Boolean> tryAcquireBatch(Collection<String> keys, String ruleName) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (String key : keys) {
            results.put(key, tryAcquire(key, ruleName));
        }
        return results;
    }
    
    /**
     * 获取等待时间
     */
    public long getWaitTime(String key, String ruleName) {
        RateLimiterBucket bucket = localLimiters.get(key + ":" + ruleName);
        if (bucket == null) {
            return 0;
        }
        return bucket.getWaitTimeMs();
    }
    
    /**
     * 添加限流规则
     */
    public void addRule(String name, RateLimitRule rule) {
        rules.put(name, rule);
        log.info("[自适应限流器] 添加规则: {} -> qps={}, burst={}", 
            name, rule.qps, rule.burst);
    }
    
    /**
     * 更新限流规则
     */
    public void updateRule(String name, int newQps, int newBurst) {
        RateLimitRule existing = rules.get(name);
        if (existing != null) {
            RateLimitRule updated = RateLimitRule.builder()
                .name(name)
                .qps(newQps)
                .burst(newBurst)
                .algorithm(existing.algorithm)
                .build();
            rules.put(name, updated);
            
            // 清除对应的本地限流器，让其重建
            localLimiters.entrySet().removeIf(e -> e.getKey().contains(":" + name));
            
            log.info("[自适应限流器] 更新规则: {} -> qps={}, burst={}", name, newQps, newBurst);
        }
    }
    
    /**
     * 移除限流规则
     */
    public void removeRule(String name) {
        rules.remove(name);
        localLimiters.entrySet().removeIf(e -> e.getKey().contains(":" + name));
        log.info("[自适应限流器] 移除规则: {}", name);
    }
    
    /**
     * 设置系统负载因子
     */
    public void setSystemLoadFactor(double factor) {
        this.systemLoadFactor = Math.max(0.1, Math.min(2.0, factor));
        log.info("[自适应限流器] 系统负载因子调整: {}", this.systemLoadFactor);
    }
    
    // ========== 自适应调整 ==========
    
    private void adaptiveTuning() {
        try {
            // 计算拒绝率
            long permits = totalPermits.get();
            long rejects = totalRejects.get();
            long total = permits + rejects;
            
            if (total < 100) {
                return;
            }
            
            double rejectRate = (double) rejects / total;
            
            // 根据拒绝率调整负载因子
            if (rejectRate > 0.3) {
                // 拒绝率过高，降低限流阈值
                systemLoadFactor = Math.max(0.5, systemLoadFactor * 0.9);
            } else if (rejectRate < 0.05) {
                // 拒绝率很低，可以适当提升
                systemLoadFactor = Math.min(1.5, systemLoadFactor * 1.1);
            }
            
            log.debug("[自适应限流器] 自适应调整 - 拒绝率: {:.2f}%, 负载因子: {}",
                rejectRate * 100, systemLoadFactor);
            
        } catch (Exception e) {
            log.warn("[自适应限流器] 自适应调整失败: {}", e.getMessage());
        }
    }
    
    private void cleanupExpiredLimiters() {
        long now = System.currentTimeMillis();
        long expireThreshold = 5 * 60 * 1000; // 5分钟未使用则清理
        
        int removed = 0;
        Iterator<Map.Entry<String, RateLimiterBucket>> it = localLimiters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RateLimiterBucket> entry = it.next();
            if (now - entry.getValue().getLastAccessTime() > expireThreshold) {
                it.remove();
                removed++;
            }
        }
        
        if (removed > 0) {
            log.debug("[自适应限流器] 清理过期限流器: {}", removed);
        }
    }
    
    // ========== 桶创建 ==========
    
    private RateLimiterBucket createBucket(RateLimitRule rule, int effectiveQps) {
        return switch (rule.algorithm) {
            case TOKEN_BUCKET -> new TokenBucketLimiter(effectiveQps, rule.burst);
            case LEAKY_BUCKET -> new LeakyBucketLimiter(effectiveQps);
            case SLIDING_WINDOW -> new SlidingWindowLimiter(effectiveQps);
            default -> new TokenBucketLimiter(effectiveQps, rule.burst);
        };
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPermits", totalPermits.get());
        stats.put("totalRejects", totalRejects.get());
        stats.put("systemLoadFactor", systemLoadFactor);
        stats.put("activeLimiters", localLimiters.size());
        stats.put("rulesCount", rules.size());
        
        // 按规则统计
        Map<String, Map<String, Long>> ruleStats = new LinkedHashMap<>();
        for (String ruleName : rules.keySet()) {
            Map<String, Long> rs = new LinkedHashMap<>();
            LongAdder permits = permitCounts.get(ruleName);
            LongAdder rejects = rejectCounts.get(ruleName);
            rs.put("permits", permits != null ? permits.sum() : 0);
            rs.put("rejects", rejects != null ? rejects.sum() : 0);
            ruleStats.put(ruleName, rs);
        }
        stats.put("ruleStatistics", ruleStats);
        
        // 规则配置
        Map<String, Map<String, Object>> ruleConfigs = new LinkedHashMap<>();
        for (var entry : rules.entrySet()) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("qps", entry.getValue().qps);
            config.put("burst", entry.getValue().burst);
            config.put("algorithm", entry.getValue().algorithm.name());
            ruleConfigs.put(entry.getKey(), config);
        }
        stats.put("rules", ruleConfigs);
        
        return stats;
    }
    
    // ========== 限流算法实现 ==========
    
    /**
     * 限流桶接口
     */
    private interface RateLimiterBucket {
        boolean tryAcquire();
        long getWaitTimeMs();
        long getLastAccessTime();
    }
    
    /**
     * 令牌桶限流器
     */
    private static class TokenBucketLimiter implements RateLimiterBucket {
        private final int refillRate;
        private final int capacity;
        private double tokens;
        private long lastRefillTime;
        private volatile long lastAccessTime;
        
        TokenBucketLimiter(int refillRate, int capacity) {
            this.refillRate = refillRate;
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillTime = System.nanoTime();
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        @Override
        public synchronized boolean tryAcquire() {
            lastAccessTime = System.currentTimeMillis();
            refillTokens();
            
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
        
        private void refillTokens() {
            long now = System.nanoTime();
            double elapsed = (now - lastRefillTime) / 1_000_000_000.0;
            double refill = elapsed * refillRate;
            tokens = Math.min(capacity, tokens + refill);
            lastRefillTime = now;
        }
        
        @Override
        public long getWaitTimeMs() {
            if (tokens >= 1) return 0;
            double needed = 1 - tokens;
            return (long) (needed / refillRate * 1000);
        }
        
        @Override
        public long getLastAccessTime() {
            return lastAccessTime;
        }
    }
    
    /**
     * 漏桶限流器
     */
    private static class LeakyBucketLimiter implements RateLimiterBucket {
        private final int rate;
        private long lastLeakTime;
        private double water;
        private final double capacity;
        private volatile long lastAccessTime;
        
        LeakyBucketLimiter(int rate) {
            this.rate = rate;
            this.capacity = rate * 2;
            this.water = 0;
            this.lastLeakTime = System.nanoTime();
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        @Override
        public synchronized boolean tryAcquire() {
            lastAccessTime = System.currentTimeMillis();
            leak();
            
            if (water < capacity) {
                water += 1;
                return true;
            }
            return false;
        }
        
        private void leak() {
            long now = System.nanoTime();
            double elapsed = (now - lastLeakTime) / 1_000_000_000.0;
            double leaked = elapsed * rate;
            water = Math.max(0, water - leaked);
            lastLeakTime = now;
        }
        
        @Override
        public long getWaitTimeMs() {
            if (water < capacity) return 0;
            double overflow = water - capacity + 1;
            return (long) (overflow / rate * 1000);
        }
        
        @Override
        public long getLastAccessTime() {
            return lastAccessTime;
        }
    }
    
    /**
     * 滑动窗口限流器
     */
    private static class SlidingWindowLimiter implements RateLimiterBucket {
        private final int limit;
        private final Queue<Long> timestamps;
        private volatile long lastAccessTime;
        
        SlidingWindowLimiter(int limit) {
            this.limit = limit;
            this.timestamps = new ConcurrentLinkedQueue<>();
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        @Override
        public synchronized boolean tryAcquire() {
            lastAccessTime = System.currentTimeMillis();
            long now = System.currentTimeMillis();
            long windowStart = now - 1000; // 1秒窗口
            
            // 清除窗口外的时间戳
            while (!timestamps.isEmpty() && timestamps.peek() < windowStart) {
                timestamps.poll();
            }
            
            if (timestamps.size() < limit) {
                timestamps.offer(now);
                return true;
            }
            return false;
        }
        
        @Override
        public long getWaitTimeMs() {
            if (timestamps.size() < limit) return 0;
            Long oldest = timestamps.peek();
            if (oldest == null) return 0;
            return Math.max(0, oldest + 1000 - System.currentTimeMillis());
        }
        
        @Override
        public long getLastAccessTime() {
            return lastAccessTime;
        }
    }
    
    // ========== 内部类 ==========
    
    /**
     * 限流算法
     */
    public enum Algorithm {
        TOKEN_BUCKET,    // 令牌桶
        LEAKY_BUCKET,    // 漏桶
        SLIDING_WINDOW   // 滑动窗口
    }
    
    /**
     * 限流规则
     */
    @Data
    @lombok.Builder
    public static class RateLimitRule {
        private String name;
        private int qps;
        private int burst;
        private Algorithm algorithm;
    }
}
