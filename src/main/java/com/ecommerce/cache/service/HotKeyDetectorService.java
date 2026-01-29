package com.ecommerce.cache.service;

import com.google.common.util.concurrent.AtomicDouble;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 热点 Key 检测服务 - 增强版
 * 
 * 优化特性：
 * 1. 本地计数器减少 Redis 访问（LongAdder 高并发）
 * 2. 滑动窗口算法优化（分桶计数）
 * 3. 热点衰减算法（防止频繁切换）
 * 4. 全面的 Metrics 监控
 * 
 * 基于滑动窗口统计 Key 访问频率
 * 超过 5K QPS 自动触发热点标记和分片
 */
@Service
public class HotKeyDetectorService {
    
    private static final Logger log = LoggerFactory.getLogger(HotKeyDetectorService.class);
    
    // 统计 Key 前缀
    private static final String STATS_PREFIX = "hotkey:stats:";
    // 本地计数器同步周期（毫秒）
    private static final long LOCAL_SYNC_INTERVAL_MS = 1000;
    // 热点衰减因子
    private static final double DECAY_FACTOR = 0.9;
    
    @Value("${multilevel-cache.hot-key.window-seconds:10}")
    private int windowSize;
    
    @Value("${multilevel-cache.hot-key.threshold-qps:5000}")
    private int hotThreshold;
    
    @Value("${multilevel-cache.hot-key.shard-count:10}")
    private int shardCount;
    
    @Value("${multilevel-cache.hot-key.local-counter-enabled:true}")
    private boolean localCounterEnabled;
    
    private final RedissonClient redissonClient;
    private final L1CacheService l1CacheService;
    private final MeterRegistry meterRegistry;
    
    // 热点 Key 集合
    private final Set<String> hotKeySet = ConcurrentHashMap.newKeySet();
    // 热点 Key QPS 统计
    private final Map<String, Double> hotKeyQpsMap = new ConcurrentHashMap<>();
    
    // 本地计数器（高性能）
    private final ConcurrentHashMap<String, LongAdder> localCounters = new ConcurrentHashMap<>();
    private final AtomicLong lastSyncTime = new AtomicLong(System.currentTimeMillis());
    
    // 分桶计数器（滑动窗口）
    private final ConcurrentHashMap<String, long[]> bucketCounters = new ConcurrentHashMap<>();
    private static final int BUCKET_COUNT = 10; // 10个桶，每桶 1 秒
    
    // 指标
    private Counter hotKeyDetectedCounter;
    private Counter hotKeyCooledCounter;

    // Lua 检测脚本
    private static final String DETECTOR_SCRIPT = """
        local stats_key = KEYS[1]
        local now = tonumber(ARGV[1])
        local window_size = tonumber(ARGV[2]) or 10
        local threshold = tonumber(ARGV[3]) or 5000
        local count_delta = tonumber(ARGV[4]) or 1
        
        local window_start = now - window_size
        redis.call('ZREMRANGEBYSCORE', stats_key, '-inf', window_start)
        redis.call('ZADD', stats_key, now, now .. ':' .. math.random(1000000))
        if count_delta > 1 then
            for i = 2, count_delta do
                redis.call('ZADD', stats_key, now, now .. ':' .. math.random(1000000) .. ':' .. i)
            end
        end
        redis.call('EXPIRE', stats_key, window_size * 2)
        
        local count = redis.call('ZCOUNT', stats_key, window_start, '+inf')
        local qps = count / window_size
        
        if qps >= threshold then
            return {qps, 1}
        else
            return {qps, 0}
        end
        """;

    public HotKeyDetectorService(RedissonClient redissonClient, 
                                  L1CacheService l1CacheService,
                                  MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.l1CacheService = l1CacheService;
        this.meterRegistry = meterRegistry;
    }
    
    @PostConstruct
    public void init() {
        // 注册指标
        hotKeyDetectedCounter = Counter.builder("cache.hotkey.detected")
            .description("Hot keys detected")
            .register(meterRegistry);
        hotKeyCooledCounter = Counter.builder("cache.hotkey.cooled")
            .description("Hot keys cooled down")
            .register(meterRegistry);
        
        Gauge.builder("cache.hotkey.count", hotKeySet, Set::size)
            .description("Current hot key count")
            .register(meterRegistry);
            
        Gauge.builder("cache.hotkey.local.counters", localCounters, Map::size)
            .description("Local counter count")
            .register(meterRegistry);
            
        log.info("HotKeyDetectorService initialized with threshold={} QPS, window={}s, localCounter={}",
            hotThreshold, windowSize, localCounterEnabled);
    }

    /**
     * 记录 Key 访问并检测是否为热点 - 优化版
     * 使用本地计数器减少 Redis 访问
     * 
     * @param key 原始 Key
     * @return true=热点 Key，false=普通 Key
     */
    public boolean recordAccess(String key) {
        // 已经是热点直接返回
        if (hotKeySet.contains(key)) {
            // 更新本地计数器
            incrementLocalCounter(key);
            return true;
        }
        
        if (localCounterEnabled) {
            // 本地计数器模式
            return recordAccessWithLocalCounter(key);
        } else {
            // 直接 Redis 模式
            return recordAccessDirect(key);
        }
    }
    
    /**
     * 本地计数器模式：先本地统计，定时同步到 Redis
     */
    private boolean recordAccessWithLocalCounter(String key) {
        // 增加本地计数
        LongAdder counter = localCounters.computeIfAbsent(key, k -> new LongAdder());
        counter.increment();
        
        // 分桶计数（滑动窗口）
        int currentBucket = (int) ((System.currentTimeMillis() / 1000) % BUCKET_COUNT);
        long[] buckets = bucketCounters.computeIfAbsent(key, k -> new long[BUCKET_COUNT]);
        buckets[currentBucket]++;
        
        // 计算本地 QPS
        long localQps = calculateLocalQps(buckets);
        
        // 本地 QPS 超过阈值的 80% 时，同步到 Redis 确认
        if (localQps >= hotThreshold * 0.8) {
            return syncAndCheckHotKey(key, counter.sum());
        }
        
        return false;
    }
    
    /**
     * 计算本地 QPS（滑动窗口）
     */
    private long calculateLocalQps(long[] buckets) {
        long total = 0;
        for (long count : buckets) {
            total += count;
        }
        return total / BUCKET_COUNT;
    }
    
    /**
     * 同步到 Redis 并检查热点状态
     */
    private boolean syncAndCheckHotKey(String key, long localCount) {
        try {
            String statsKey = STATS_PREFIX + key;
            long now = Instant.now().getEpochSecond();
            
            List<Object> result = redissonClient.getScript().eval(
                RScript.Mode.READ_WRITE,
                DETECTOR_SCRIPT,
                RScript.ReturnType.MULTI,
                Collections.singletonList(statsKey),
                String.valueOf(now),
                String.valueOf(windowSize),
                String.valueOf(hotThreshold),
                String.valueOf(localCount)
            );
            
            double qps = ((Number) result.get(0)).doubleValue();
            boolean isHot = ((Number) result.get(1)).intValue() == 1;
            
            if (isHot && !hotKeySet.contains(key)) {
                onHotKeyDetected(key, qps);
            }
            
            if (isHot) {
                hotKeyQpsMap.put(key, qps);
            }
            
            return isHot;
            
        } catch (Exception e) {
            log.warn("Failed to sync hot key check: {}", key, e);
            return hotKeySet.contains(key);
        }
    }
    
    /**
     * 增加本地计数器
     */
    private void incrementLocalCounter(String key) {
        localCounters.computeIfAbsent(key, k -> new LongAdder()).increment();
    }
    
    /**
     * 直接 Redis 模式（原有逻辑）
     */
    private boolean recordAccessDirect(String key) {
        try {
            String statsKey = STATS_PREFIX + key;
            long now = Instant.now().getEpochSecond();
            
            List<Object> result = redissonClient.getScript().eval(
                RScript.Mode.READ_WRITE,
                DETECTOR_SCRIPT,
                RScript.ReturnType.MULTI,
                Collections.singletonList(statsKey),
                String.valueOf(now),
                String.valueOf(windowSize),
                String.valueOf(hotThreshold),
                "1"
            );
            
            double qps = ((Number) result.get(0)).doubleValue();
            boolean isHot = ((Number) result.get(1)).intValue() == 1;
            
            if (isHot && !hotKeySet.contains(key)) {
                onHotKeyDetected(key, qps);
            } else if (!isHot && hotKeySet.contains(key)) {
                onHotKeyCooled(key);
            }
            
            if (isHot) {
                hotKeyQpsMap.put(key, qps);
            }
            
            return isHot;
            
        } catch (Exception e) {
            log.warn("Failed to record access for key: {}", key, e);
            return hotKeySet.contains(key);
        }
    }

    /**
     * 热点 Key 检测回调：自动拆分
     */
    private void onHotKeyDetected(String key, double qps) {
        log.warn("Hot key detected! key={}, qps={}, auto-shard to {} sub-keys", 
            key, String.format("%.2f", qps), shardCount);
        
        hotKeySet.add(key);
        hotKeyQpsMap.put(key, qps);
        l1CacheService.markAsHotKey(key);
        hotKeyDetectedCounter.increment();
    }

    /**
     * 热点 Key 降温回调（带衰减）
     */
    private void onHotKeyCooled(String key) {
        // 应用衰减因子，防止频繁切换
        Double currentQps = hotKeyQpsMap.get(key);
        if (currentQps != null) {
            double decayedQps = currentQps * DECAY_FACTOR;
            if (decayedQps < hotThreshold * 0.5) {
                // 真正降温
                log.info("Hot key cooled down: {}", key);
                hotKeySet.remove(key);
                hotKeyQpsMap.remove(key);
                l1CacheService.unmarkHotKey(key);
                localCounters.remove(key);
                bucketCounters.remove(key);
                hotKeyCooledCounter.increment();
            } else {
                // 更新衰减后的 QPS
                hotKeyQpsMap.put(key, decayedQps);
            }
        }
    }

    /**
     * 获取 Top N 热点 Key
     */
    public List<String> getTopHotKeys(int n) {
        return hotKeyQpsMap.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(n)
            .map(Map.Entry::getKey)
            .toList();
    }

    /**
     * 获取所有热点 Key
     */
    public Set<String> getAllHotKeys() {
        return Collections.unmodifiableSet(hotKeySet);
    }

    /**
     * 获取热点 Key 数量
     */
    public int getHotKeyCount() {
        return hotKeySet.size();
    }

    /**
     * 检查是否为热点 Key
     */
    public boolean isHotKey(String key) {
        return hotKeySet.contains(key);
    }

    /**
     * 获取 Key 的 QPS
     */
    public Double getKeyQps(String key) {
        return hotKeyQpsMap.get(key);
    }

    /**
     * 手动标记为热点 Key
     */
    public void markAsHotKey(String key) {
        hotKeySet.add(key);
        l1CacheService.markAsHotKey(key);
        log.info("Manually marked as hot key: {}", key);
    }

    /**
     * 手动取消热点标记
     */
    public void unmarkHotKey(String key) {
        hotKeySet.remove(key);
        hotKeyQpsMap.remove(key);
        l1CacheService.unmarkHotKey(key);
        log.info("Manually unmarked hot key: {}", key);
    }

    /**
     * 定期清理冷却的热点 Key（每分钟）- 优化版
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupCooledKeys() {
        int before = hotKeySet.size();
        log.debug("Hot key cleanup check, current count: {}", before);
        
        // 清理 QPS 过低的 Key（带衰减）
        hotKeyQpsMap.entrySet().removeIf(entry -> {
            double decayedQps = entry.getValue() * DECAY_FACTOR;
            if (decayedQps < hotThreshold * 0.5) {
                String key = entry.getKey();
                hotKeySet.remove(key);
                l1CacheService.unmarkHotKey(key);
                localCounters.remove(key);
                bucketCounters.remove(key);
                log.info("Cleaned up cooled hot key: {}, qps: {}", key, String.format("%.2f", entry.getValue()));
                hotKeyCooledCounter.increment();
                return true;
            }
            // 更新衰减后的 QPS
            entry.setValue(decayedQps);
            return false;
        });
        
        // 清理本地计数器（防止内存泄漏）
        cleanupLocalCounters();
        
        int after = hotKeySet.size();
        if (before != after) {
            log.info("Hot key cleanup completed, before: {}, after: {}", before, after);
        }
    }
    
    /**
     * 清理本地计数器
     */
    private void cleanupLocalCounters() {
        // 保留热点 Key 的计数器，清理其他
        localCounters.keySet().removeIf(key -> !hotKeySet.contains(key));
        bucketCounters.keySet().removeIf(key -> !hotKeySet.contains(key));
        
        // 重置分桶计数器（每分钟）
        int currentBucket = (int) ((System.currentTimeMillis() / 1000) % BUCKET_COUNT);
        bucketCounters.values().forEach(buckets -> {
            // 清理过期的桶
            for (int i = 0; i < BUCKET_COUNT; i++) {
                if (Math.abs(i - currentBucket) > 2) {
                    buckets[i] = 0;
                }
            }
        });
    }

    /**
     * 获取热点 Key 统计摘要（增强版）
     */
    public HotKeyStats getStats() {
        double avgQps = hotKeyQpsMap.values().stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0);
        
        double maxQps = hotKeyQpsMap.values().stream()
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0);
        
        return new HotKeyStats(
            hotKeySet.size(), 
            avgQps, 
            maxQps, 
            hotThreshold, 
            windowSize,
            localCounters.size(),
            hotKeyDetectedCounter.count(),
            hotKeyCooledCounter.count()
        );
    }

    public record HotKeyStats(
        int hotKeyCount,
        double avgQps,
        double maxQps,
        int threshold,
        int windowSeconds,
        int localCounterCount,
        double totalDetected,
        double totalCooled
    ) {}
}
