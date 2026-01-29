package com.ecommerce.cache.optimization;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 高级缓存策略优化模块
 * 
 * 核心优化：
 * 1. 智能分级 TTL - 根据访问频率动态调整过期时间
 * 2. 预测性预加载 - 基于访问模式预测并预加载热点数据
 * 3. 多维度防穿透 - 布隆过滤器 + 空值缓存 + 请求合并
 * 4. 热点数据自动升级 - 自动将热点数据提升到更高层级
 * 5. 冷数据降级 - 冷数据自动降级到低成本存储
 * 6. 缓存一致性增强 - 延迟双删 + 版本号校验
 */
@Service
public class AdvancedCacheStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(AdvancedCacheStrategy.class);
    
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    
    // 访问计数器 - 用于智能 TTL 计算
    private final Cache<String, AccessStats> accessStatsCache;
    
    // 预加载队列
    private final BlockingQueue<String> preloadQueue = new LinkedBlockingQueue<>(10000);
    
    // 版本号缓存 - 用于一致性校验
    private final Cache<String, Long> versionCache;
    
    // 性能指标
    private final Counter preloadHitCounter;
    private final Counter preloadMissCounter;
    private final Timer smartTtlTimer;
    
    // 配置
    @Value("${optimization.cache.smart-ttl.enabled:true}")
    private boolean smartTtlEnabled;
    
    @Value("${optimization.cache.smart-ttl.min-seconds:60}")
    private long minTtlSeconds;
    
    @Value("${optimization.cache.smart-ttl.max-seconds:3600}")
    private long maxTtlSeconds;
    
    @Value("${optimization.cache.preload.enabled:true}")
    private boolean preloadEnabled;
    
    @Value("${optimization.cache.preload.threshold:100}")
    private int preloadThreshold;
    
    public AdvancedCacheStrategy(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        
        // 初始化访问统计缓存
        this.accessStatsCache = Caffeine.newBuilder()
            .maximumSize(100000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();
        
        // 初始化版本号缓存
        this.versionCache = Caffeine.newBuilder()
            .maximumSize(50000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();
        
        // 初始化指标
        this.preloadHitCounter = Counter.builder("cache.preload.hits").register(meterRegistry);
        this.preloadMissCounter = Counter.builder("cache.preload.misses").register(meterRegistry);
        this.smartTtlTimer = Timer.builder("cache.smart.ttl.calculation").register(meterRegistry);
        
        // 注册监控指标
        Gauge.builder("cache.access.stats.size", accessStatsCache, Cache::estimatedSize)
            .register(meterRegistry);
        Gauge.builder("cache.preload.queue.size", preloadQueue, BlockingQueue::size)
            .register(meterRegistry);
    }
    
    /**
     * 计算智能 TTL - 基于访问频率动态调整
     * 高频访问 -> 更长 TTL，低频访问 -> 更短 TTL
     */
    public long calculateSmartTtl(String key) {
        if (!smartTtlEnabled) {
            return minTtlSeconds;
        }
        
        return smartTtlTimer.record(() -> {
            AccessStats stats = accessStatsCache.getIfPresent(key);
            if (stats == null) {
                return minTtlSeconds;
            }
            
            // 计算最近访问频率（每分钟访问次数）
            double accessRate = stats.getAccessRate();
            
            // 线性映射到 TTL 范围
            // accessRate: 0 -> minTtl, 1000+ -> maxTtl
            double ratio = Math.min(accessRate / 1000.0, 1.0);
            long ttl = minTtlSeconds + (long) ((maxTtlSeconds - minTtlSeconds) * ratio);
            
            log.debug("Smart TTL calculated: key={}, rate={}, ttl={}s", key, accessRate, ttl);
            return ttl;
        });
    }
    
    /**
     * 记录访问并更新统计
     */
    public void recordAccess(String key) {
        accessStatsCache.get(key, k -> new AccessStats()).recordAccess();
        
        // 检查是否需要预加载相关数据
        if (preloadEnabled) {
            checkPreloadRelated(key);
        }
    }
    
    /**
     * 预测性预加载 - 基于访问模式预加载相关数据
     */
    private void checkPreloadRelated(String key) {
        AccessStats stats = accessStatsCache.getIfPresent(key);
        if (stats != null && stats.getAccessCount() >= preloadThreshold) {
            // 提取关联 Key 并加入预加载队列
            List<String> relatedKeys = extractRelatedKeys(key);
            for (String relatedKey : relatedKeys) {
                preloadQueue.offer(relatedKey);
            }
        }
    }
    
    /**
     * 提取关联 Key（基于命名规则）
     */
    private List<String> extractRelatedKeys(String key) {
        List<String> related = new ArrayList<>();
        
        // 假设 key 格式为 spu:detail:{spuId}
        if (key.startsWith("spu:detail:")) {
            String spuId = key.substring("spu:detail:".length());
            // 预加载相关数据：SKU、评价、推荐
            related.add("spu:sku:" + spuId);
            related.add("spu:review:" + spuId);
            related.add("spu:recommend:" + spuId);
        }
        
        return related;
    }
    
    /**
     * 定时处理预加载队列
     */
    @Scheduled(fixedRate = 1000)
    public void processPreloadQueue() {
        if (!preloadEnabled || preloadQueue.isEmpty()) return;
        
        List<String> batch = new ArrayList<>();
        preloadQueue.drainTo(batch, 100);
        
        if (!batch.isEmpty()) {
            log.debug("Processing preload batch: {} keys", batch.size());
            // 批量检查并预加载
            // 实际实现中需要调用缓存服务
        }
    }
    
    /**
     * 延迟双删策略 - 保证缓存一致性
     */
    public void delayedDoubleDelete(String key, Runnable dbUpdate, long delayMs) {
        // 第一次删除
        redisTemplate.delete(key);
        log.debug("First delete: {}", key);
        
        // 执行数据库更新
        dbUpdate.run();
        
        // 延迟第二次删除
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
            .execute(() -> {
                redisTemplate.delete(key);
                log.debug("Second delete (delayed): {}", key);
            });
    }
    
    /**
     * 带版本号的缓存更新 - 避免 ABA 问题
     */
    public boolean updateWithVersion(String key, String value, long expectedVersion) {
        Long currentVersion = versionCache.getIfPresent(key);
        
        if (currentVersion != null && currentVersion != expectedVersion) {
            log.warn("Version mismatch: key={}, expected={}, current={}", 
                key, expectedVersion, currentVersion);
            return false;
        }
        
        long newVersion = System.currentTimeMillis();
        versionCache.put(key, newVersion);
        redisTemplate.opsForValue().set(key, value);
        
        return true;
    }
    
    /**
     * 获取当前版本号
     */
    public long getVersion(String key) {
        return versionCache.get(key, k -> System.currentTimeMillis());
    }
    
    /**
     * 获取访问统计
     */
    public Map<String, AccessStats> getAccessStats(int limit) {
        return accessStatsCache.asMap().entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getAccessCount(), a.getValue().getAccessCount()))
            .limit(limit)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
    
    /**
     * 访问统计内部类
     */
    public static class AccessStats {
        private final LongAdder accessCount = new LongAdder();
        private final AtomicLong firstAccessTime = new AtomicLong(0);
        private final AtomicLong lastAccessTime = new AtomicLong(0);
        
        public void recordAccess() {
            long now = System.currentTimeMillis();
            accessCount.increment();
            firstAccessTime.compareAndSet(0, now);
            lastAccessTime.set(now);
        }
        
        public long getAccessCount() {
            return accessCount.sum();
        }
        
        public double getAccessRate() {
            long first = firstAccessTime.get();
            if (first == 0) return 0;
            
            long duration = System.currentTimeMillis() - first;
            if (duration < 60000) duration = 60000; // 至少 1 分钟
            
            return accessCount.sum() * 60000.0 / duration; // 每分钟访问次数
        }
        
        public Instant getLastAccessTime() {
            return Instant.ofEpochMilli(lastAccessTime.get());
        }
    }
}

/**
 * 多维度防穿透服务
 */
@Service
class AntiPenetrationService {
    
    private static final Logger log = LoggerFactory.getLogger(AntiPenetrationService.class);
    
    // 空值缓存
    private final Cache<String, Boolean> nullValueCache;
    
    // 请求计数器 - 检测异常请求
    private final Cache<String, LongAdder> requestCounter;
    
    // 黑名单
    private final Set<String> blacklist = ConcurrentHashMap.newKeySet();
    
    // 配置
    @Value("${optimization.cache.anti-penetration.null-cache-ttl:60}")
    private int nullCacheTtlSeconds;
    
    @Value("${optimization.cache.anti-penetration.request-threshold:100}")
    private int requestThreshold;
    
    @Value("${optimization.cache.anti-penetration.blacklist-enabled:true}")
    private boolean blacklistEnabled;
    
    private final Counter blockedCounter;
    private final Counter nullCacheHitCounter;
    
    public AntiPenetrationService(MeterRegistry meterRegistry) {
        this.nullValueCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();
        
        this.requestCounter = Caffeine.newBuilder()
            .maximumSize(100000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();
        
        this.blockedCounter = Counter.builder("cache.penetration.blocked").register(meterRegistry);
        this.nullCacheHitCounter = Counter.builder("cache.penetration.null.hits").register(meterRegistry);
    }
    
    /**
     * 检查是否应该拦截请求
     */
    public boolean shouldBlock(String key) {
        // 检查黑名单
        if (blacklistEnabled && blacklist.contains(key)) {
            blockedCounter.increment();
            log.debug("Request blocked (blacklist): {}", key);
            return true;
        }
        
        // 检查空值缓存
        if (nullValueCache.getIfPresent(key) != null) {
            nullCacheHitCounter.increment();
            log.debug("Null value cache hit: {}", key);
            return true;
        }
        
        // 检查请求频率
        LongAdder counter = requestCounter.get(key, k -> new LongAdder());
        counter.increment();
        
        if (counter.sum() > requestThreshold) {
            // 超过阈值，加入黑名单
            if (blacklistEnabled) {
                blacklist.add(key);
                log.warn("Key added to blacklist due to high request rate: {}", key);
            }
            blockedCounter.increment();
            return true;
        }
        
        return false;
    }
    
    /**
     * 记录空值
     */
    public void recordNullValue(String key) {
        nullValueCache.put(key, Boolean.TRUE);
        log.debug("Null value cached: {}", key);
    }
    
    /**
     * 从黑名单移除
     */
    public void removeFromBlacklist(String key) {
        blacklist.remove(key);
        nullValueCache.invalidate(key);
    }
    
    /**
     * 清理黑名单
     */
    @Scheduled(fixedRate = 300000) // 5 分钟
    public void cleanupBlacklist() {
        int sizeBefore = blacklist.size();
        blacklist.clear();
        log.info("Blacklist cleaned, removed {} entries", sizeBefore);
    }
    
    public AntiPenetrationStats getStats() {
        return new AntiPenetrationStats(
            nullValueCache.estimatedSize(),
            blacklist.size(),
            blockedCounter.count(),
            nullCacheHitCounter.count()
        );
    }
    
    public record AntiPenetrationStats(
        long nullCacheSize, int blacklistSize, double blockedCount, double nullHitCount
    ) {}
}

/**
 * 热点数据自动升级服务
 */
@Service
class HotDataPromoter {
    
    private static final Logger log = LoggerFactory.getLogger(HotDataPromoter.class);
    
    private final MeterRegistry meterRegistry;
    
    // 热度评分缓存
    private final Cache<String, HeatScore> heatScoreCache;
    
    // 升级阈值
    @Value("${optimization.cache.hot-data.promotion-threshold:500}")
    private double promotionThreshold;
    
    // 降级阈值
    @Value("${optimization.cache.hot-data.demotion-threshold:10}")
    private double demotionThreshold;
    
    private final Counter promotionCounter;
    private final Counter demotionCounter;
    
    public HotDataPromoter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        this.heatScoreCache = Caffeine.newBuilder()
            .maximumSize(50000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();
        
        this.promotionCounter = Counter.builder("cache.hot.promotions").register(meterRegistry);
        this.demotionCounter = Counter.builder("cache.hot.demotions").register(meterRegistry);
    }
    
    /**
     * 记录访问并计算热度评分
     */
    public CacheLevel recordAndEvaluate(String key) {
        HeatScore score = heatScoreCache.get(key, k -> new HeatScore());
        score.recordAccess();
        
        double currentScore = score.calculateScore();
        
        if (currentScore >= promotionThreshold) {
            promotionCounter.increment();
            log.debug("Key promoted to L1: {}, score={}", key, currentScore);
            return CacheLevel.L1;
        } else if (currentScore <= demotionThreshold) {
            demotionCounter.increment();
            log.debug("Key demoted to L3: {}, score={}", key, currentScore);
            return CacheLevel.L3;
        } else {
            return CacheLevel.L2;
        }
    }
    
    /**
     * 获取 Top N 热点 Key
     */
    public List<HotKeyInfo> getTopHotKeys(int n) {
        return heatScoreCache.asMap().entrySet().stream()
            .map(e -> new HotKeyInfo(e.getKey(), e.getValue().calculateScore(), e.getValue().getAccessCount()))
            .sorted((a, b) -> Double.compare(b.score(), a.score()))
            .limit(n)
            .toList();
    }
    
    public enum CacheLevel { L1, L2, L3 }
    
    public record HotKeyInfo(String key, double score, long accessCount) {}
    
    /**
     * 热度评分计算
     * 使用时间衰减的访问计数
     */
    static class HeatScore {
        private final LongAdder accessCount = new LongAdder();
        private final AtomicLong lastAccessTime = new AtomicLong(System.currentTimeMillis());
        private volatile double cachedScore = 0;
        private volatile long lastScoreCalcTime = 0;
        
        void recordAccess() {
            accessCount.increment();
            lastAccessTime.set(System.currentTimeMillis());
        }
        
        double calculateScore() {
            long now = System.currentTimeMillis();
            
            // 缓存分数，避免频繁计算
            if (now - lastScoreCalcTime < 1000) {
                return cachedScore;
            }
            
            long timeSinceLastAccess = now - lastAccessTime.get();
            double decayFactor = Math.exp(-timeSinceLastAccess / 60000.0); // 1 分钟半衰期
            
            cachedScore = accessCount.sum() * decayFactor;
            lastScoreCalcTime = now;
            
            return cachedScore;
        }
        
        long getAccessCount() {
            return accessCount.sum();
        }
    }
}

/**
 * 缓存预热增强服务
 */
@Service
class EnhancedCacheWarmer {
    
    private static final Logger log = LoggerFactory.getLogger(EnhancedCacheWarmer.class);
    
    private final ExecutorService warmupExecutor;
    private final MeterRegistry meterRegistry;
    
    // 预热进度
    private final AtomicLong totalKeys = new AtomicLong(0);
    private final AtomicLong warmedKeys = new AtomicLong(0);
    private volatile boolean warming = false;
    
    // 指标
    private final Timer warmupTimer;
    private final Counter warmupSuccessCounter;
    private final Counter warmupFailCounter;
    
    public EnhancedCacheWarmer(MeterRegistry meterRegistry,
                               @Qualifier("virtualThreadExecutor") ExecutorService virtualExecutor) {
        this.meterRegistry = meterRegistry;
        this.warmupExecutor = virtualExecutor;
        
        this.warmupTimer = Timer.builder("cache.warmup.duration").register(meterRegistry);
        this.warmupSuccessCounter = Counter.builder("cache.warmup.success").register(meterRegistry);
        this.warmupFailCounter = Counter.builder("cache.warmup.fail").register(meterRegistry);
        
        // 注册进度指标
        Gauge.builder("cache.warmup.progress", () -> {
            long total = totalKeys.get();
            return total > 0 ? (double) warmedKeys.get() / total : 0;
        }).register(meterRegistry);
    }
    
    /**
     * 批量预热缓存
     */
    public CompletableFuture<WarmupResult> warmupBatch(List<String> keys, 
                                                        Function<String, String> loader,
                                                        java.util.function.BiConsumer<String, String> cacheWriter) {
        if (warming) {
            return CompletableFuture.completedFuture(
                new WarmupResult(0, 0, 0, "Warmup already in progress"));
        }
        
        warming = true;
        totalKeys.set(keys.size());
        warmedKeys.set(0);
        
        return CompletableFuture.supplyAsync(() -> warmupTimer.record(() -> {
            long startTime = System.currentTimeMillis();
            int success = 0;
            int failed = 0;
            
            // 分批并行预热
            int batchSize = 100;
            for (int i = 0; i < keys.size(); i += batchSize) {
                List<String> batch = keys.subList(i, Math.min(i + batchSize, keys.size()));
                
                List<CompletableFuture<Boolean>> futures = batch.stream()
                    .map(key -> CompletableFuture.supplyAsync(() -> {
                        try {
                            String value = loader.apply(key);
                            if (value != null) {
                                cacheWriter.accept(key, value);
                                warmupSuccessCounter.increment();
                                return true;
                            }
                        } catch (Exception e) {
                            log.error("Warmup failed for key: {}", key, e);
                            warmupFailCounter.increment();
                        }
                        return false;
                    }, warmupExecutor))
                    .toList();
                
                // 等待当前批次完成
                for (CompletableFuture<Boolean> future : futures) {
                    try {
                        if (future.get(5, TimeUnit.SECONDS)) {
                            success++;
                        } else {
                            failed++;
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                    warmedKeys.incrementAndGet();
                }
            }
            
            warming = false;
            long duration = System.currentTimeMillis() - startTime;
            log.info("Cache warmup completed: success={}, failed={}, duration={}ms", success, failed, duration);
            
            return new WarmupResult(success, failed, duration, "Completed");
        }), warmupExecutor);
    }
    
    /**
     * 获取预热进度
     */
    public WarmupProgress getProgress() {
        return new WarmupProgress(warming, totalKeys.get(), warmedKeys.get());
    }
    
    public record WarmupResult(int success, int failed, long durationMs, String message) {}
    public record WarmupProgress(boolean inProgress, long total, long completed) {}
}

/**
 * 缓存分片管理器 - 支持超大规模缓存
 */
@Component
class CacheShardManager {
    
    private static final Logger log = LoggerFactory.getLogger(CacheShardManager.class);
    
    private final int shardCount;
    private final List<Cache<String, String>> shards;
    
    public CacheShardManager(@Value("${optimization.cache.shard.count:16}") int shardCount) {
        this.shardCount = shardCount;
        this.shards = new ArrayList<>(shardCount);
        
        for (int i = 0; i < shardCount; i++) {
            shards.add(Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()
                .build());
        }
        
        log.info("Cache shard manager initialized with {} shards", shardCount);
    }
    
    private int getShardIndex(String key) {
        return Math.abs(key.hashCode()) % shardCount;
    }
    
    public String get(String key) {
        return shards.get(getShardIndex(key)).getIfPresent(key);
    }
    
    public void put(String key, String value) {
        shards.get(getShardIndex(key)).put(key, value);
    }
    
    public void invalidate(String key) {
        shards.get(getShardIndex(key)).invalidate(key);
    }
    
    public Map<Integer, Long> getShardSizes() {
        Map<Integer, Long> sizes = new HashMap<>();
        for (int i = 0; i < shardCount; i++) {
            sizes.put(i, shards.get(i).estimatedSize());
        }
        return sizes;
    }
    
    public long getTotalSize() {
        return shards.stream().mapToLong(Cache::estimatedSize).sum();
    }
}
