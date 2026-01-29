package com.ecommerce.cache.optimization.v5;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * HeavyKeeper 热点检测算法 - 高性能 Top-K 热点 Key 检测
 * 
 * 核心优势：
 * 1. 内存效率高 - 固定内存占用，O(k) 空间复杂度
 * 2. 检测准确率高 - 基于 Count-Min Sketch + 最小堆
 * 3. 支持衰减 - 自然淘汰冷 Key，适应流量变化
 * 4. 无锁设计 - 高并发场景下性能优异
 * 5. 支持指纹去重 - 减少哈希冲突影响
 * 
 * 算法原理：
 * - 使用多行哈希桶存储 Key 指纹和计数
 * - 采用指数衰减策略自动淘汰低频 Key
 * - 使用最小堆维护 Top-K 热点
 * 
 * @see <a href="https://www.usenix.org/conference/atc18/presentation/gong">HeavyKeeper Paper</a>
 */
@Service
public class HeavyKeeperHotKeyDetector {
    
    private static final Logger log = LoggerFactory.getLogger(HeavyKeeperHotKeyDetector.class);
    
    // ========== 算法参数 ==========
    private static final int DEFAULT_DEPTH = 4;          // 哈希行数
    private static final int DEFAULT_WIDTH = 65536;      // 每行桶数
    private static final double DEFAULT_DECAY_RATE = 1.08; // 衰减率
    private static final int DEFAULT_TOP_K = 100;        // Top-K 数量
    
    @Value("${optimization.heavy-keeper.depth:4}")
    private int depth;
    
    @Value("${optimization.heavy-keeper.width:65536}")
    private int width;
    
    @Value("${optimization.heavy-keeper.decay-rate:1.08}")
    private double decayRate;
    
    @Value("${optimization.heavy-keeper.top-k:100}")
    private int topK;
    
    @Value("${optimization.heavy-keeper.hot-threshold:1000}")
    private long hotThreshold;
    
    @Value("${optimization.heavy-keeper.enabled:true}")
    private boolean enabled;
    
    // ========== 数据结构 ==========
    
    // 桶：存储指纹和计数
    private volatile Bucket[][] buckets;
    
    // Top-K 堆
    private final ConcurrentHashMap<String, AtomicLong> hotKeyCounters = new ConcurrentHashMap<>();
    private final Set<String> confirmedHotKeys = ConcurrentHashMap.newKeySet();
    
    // 哈希种子
    private final long[] hashSeeds = new long[DEFAULT_DEPTH];
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Counter hotKeyAddedCounter;
    private Counter hotKeyRemovedCounter;
    private Counter decayEventsCounter;
    private final LongAdder totalOperations = new LongAdder();
    private final LongAdder totalDecays = new LongAdder();
    
    public HeavyKeeperHotKeyDetector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 初始化哈希种子
        Random random = new Random(42);
        for (int i = 0; i < DEFAULT_DEPTH; i++) {
            hashSeeds[i] = random.nextLong();
        }
    }
    
    @PostConstruct
    public void init() {
        // 使用配置或默认值
        if (depth <= 0) depth = DEFAULT_DEPTH;
        if (width <= 0) width = DEFAULT_WIDTH;
        if (decayRate <= 1.0) decayRate = DEFAULT_DECAY_RATE;
        if (topK <= 0) topK = DEFAULT_TOP_K;
        
        // 初始化桶数组
        buckets = new Bucket[depth][width];
        for (int i = 0; i < depth; i++) {
            for (int j = 0; j < width; j++) {
                buckets[i][j] = new Bucket();
            }
        }
        
        // 注册指标
        hotKeyAddedCounter = Counter.builder("heavy.keeper.hotkey.added")
            .description("Hot keys added by HeavyKeeper")
            .register(meterRegistry);
        hotKeyRemovedCounter = Counter.builder("heavy.keeper.hotkey.removed")
            .description("Hot keys removed by HeavyKeeper")
            .register(meterRegistry);
        decayEventsCounter = Counter.builder("heavy.keeper.decay.events")
            .description("Decay events in HeavyKeeper")
            .register(meterRegistry);
        
        Gauge.builder("heavy.keeper.hotkey.count", confirmedHotKeys, Set::size)
            .description("Current hot key count by HeavyKeeper")
            .register(meterRegistry);
        Gauge.builder("heavy.keeper.operations.total", totalOperations, LongAdder::sum)
            .register(meterRegistry);
        
        log.info("HeavyKeeper initialized: depth={}, width={}, decayRate={}, topK={}, threshold={}",
            depth, width, decayRate, topK, hotThreshold);
    }
    
    /**
     * 记录 Key 访问并返回估计计数
     * 
     * @param key 访问的 Key
     * @return 估计的访问频率
     */
    public long add(String key) {
        if (!enabled) return 0;
        
        totalOperations.increment();
        long fingerprint = getFingerprint(key);
        long minCount = Long.MAX_VALUE;
        
        for (int i = 0; i < depth; i++) {
            int index = getIndex(key, i);
            Bucket bucket = buckets[i][index];
            
            if (bucket.fingerprint == fingerprint) {
                // 指纹匹配，增加计数
                long newCount = bucket.incrementCount();
                minCount = Math.min(minCount, newCount);
            } else if (bucket.count == 0) {
                // 空桶，占据
                bucket.setFingerprint(fingerprint);
                bucket.setCount(1);
                minCount = Math.min(minCount, 1);
            } else {
                // 指纹不匹配，触发衰减
                if (shouldDecay()) {
                    long newCount = bucket.decrementCount();
                    totalDecays.increment();
                    decayEventsCounter.increment();
                    
                    if (newCount == 0) {
                        // 衰减到0，替换为新Key
                        bucket.setFingerprint(fingerprint);
                        bucket.setCount(1);
                        minCount = Math.min(minCount, 1);
                    }
                }
            }
        }
        
        // 更新热点统计
        if (minCount != Long.MAX_VALUE && minCount >= hotThreshold) {
            updateHotKey(key, minCount);
        }
        
        return minCount == Long.MAX_VALUE ? 0 : minCount;
    }
    
    /**
     * 批量添加（高效）
     */
    public void addBatch(Collection<String> keys) {
        keys.forEach(this::add);
    }
    
    /**
     * 查询 Key 的估计计数
     */
    public long query(String key) {
        if (!enabled) return 0;
        
        long fingerprint = getFingerprint(key);
        long minCount = Long.MAX_VALUE;
        
        for (int i = 0; i < depth; i++) {
            int index = getIndex(key, i);
            Bucket bucket = buckets[i][index];
            
            if (bucket.fingerprint == fingerprint) {
                minCount = Math.min(minCount, bucket.count);
            }
        }
        
        return minCount == Long.MAX_VALUE ? 0 : minCount;
    }
    
    /**
     * 检查是否为热点 Key
     */
    public boolean isHotKey(String key) {
        return confirmedHotKeys.contains(key);
    }
    
    /**
     * 获取所有热点 Key
     */
    public Set<String> getHotKeys() {
        return Collections.unmodifiableSet(confirmedHotKeys);
    }
    
    /**
     * 获取 Top-K 热点 Key（带计数）
     */
    public List<HotKeyEntry> getTopKHotKeys(int k) {
        return hotKeyCounters.entrySet().stream()
            .map(e -> new HotKeyEntry(e.getKey(), e.getValue().get()))
            .sorted(Comparator.comparingLong(HotKeyEntry::count).reversed())
            .limit(k)
            .collect(Collectors.toList());
    }
    
    /**
     * 手动标记为热点
     */
    public void markAsHotKey(String key) {
        confirmedHotKeys.add(key);
        hotKeyCounters.computeIfAbsent(key, k -> new AtomicLong(hotThreshold));
        hotKeyAddedCounter.increment();
    }
    
    /**
     * 手动取消热点标记
     */
    public void unmarkHotKey(String key) {
        confirmedHotKeys.remove(key);
        hotKeyCounters.remove(key);
        hotKeyRemovedCounter.increment();
    }
    
    /**
     * 定期衰减和清理（每秒）
     */
    @Scheduled(fixedRate = 1000)
    public void periodicDecay() {
        if (!enabled) return;
        
        // 对所有桶进行周期性衰减
        for (int i = 0; i < depth; i++) {
            for (int j = 0; j < width; j++) {
                Bucket bucket = buckets[i][j];
                if (bucket.count > 0 && ThreadLocalRandom.current().nextDouble() < 0.1) {
                    bucket.decrementCount();
                }
            }
        }
        
        // 清理冷却的热点
        hotKeyCounters.entrySet().removeIf(entry -> {
            long count = entry.getValue().get();
            if (count < hotThreshold / 2) {
                confirmedHotKeys.remove(entry.getKey());
                hotKeyRemovedCounter.increment();
                return true;
            }
            // 应用衰减
            entry.getValue().updateAndGet(v -> (long) (v * 0.95));
            return false;
        });
    }
    
    /**
     * 重置所有数据
     */
    public void reset() {
        for (int i = 0; i < depth; i++) {
            for (int j = 0; j < width; j++) {
                buckets[i][j].reset();
            }
        }
        hotKeyCounters.clear();
        confirmedHotKeys.clear();
        log.info("HeavyKeeper reset completed");
    }
    
    /**
     * 获取统计信息
     */
    public HeavyKeeperStats getStats() {
        long nonEmptyBuckets = 0;
        long totalCount = 0;
        
        for (int i = 0; i < depth; i++) {
            for (int j = 0; j < width; j++) {
                if (buckets[i][j].count > 0) {
                    nonEmptyBuckets++;
                    totalCount += buckets[i][j].count;
                }
            }
        }
        
        return new HeavyKeeperStats(
            confirmedHotKeys.size(),
            hotKeyCounters.size(),
            nonEmptyBuckets,
            (long) depth * width,
            totalCount,
            totalOperations.sum(),
            totalDecays.sum(),
            hotThreshold
        );
    }
    
    // ========== 私有方法 ==========
    
    private void updateHotKey(String key, long count) {
        AtomicLong counter = hotKeyCounters.computeIfAbsent(key, k -> new AtomicLong(0));
        long oldValue = counter.getAndSet(count);
        
        if (oldValue < hotThreshold && count >= hotThreshold) {
            // 新晋热点
            confirmedHotKeys.add(key);
            hotKeyAddedCounter.increment();
            log.debug("New hot key detected: {}, count: {}", key, count);
        }
    }
    
    private boolean shouldDecay() {
        // 概率衰减：1/decayRate 的概率触发衰减
        return ThreadLocalRandom.current().nextDouble() * decayRate < 1.0;
    }
    
    private int getIndex(String key, int row) {
        long hash = murmurHash64(key.getBytes(StandardCharsets.UTF_8), hashSeeds[row]);
        return (int) ((hash & 0x7FFFFFFFFFFFFFFFL) % width);
    }
    
    private long getFingerprint(String key) {
        return murmurHash64(key.getBytes(StandardCharsets.UTF_8), 0x1234567890ABCDEFL);
    }
    
    /**
     * MurmurHash64A - 高性能哈希
     */
    private static long murmurHash64(byte[] data, long seed) {
        final long m = 0xc6a4a7935bd1e995L;
        final int r = 47;
        
        long h = seed ^ (data.length * m);
        
        int length = data.length;
        int length8 = length / 8;
        
        for (int i = 0; i < length8; i++) {
            int i8 = i * 8;
            long k = ((long) data[i8] & 0xff) |
                     (((long) data[i8 + 1] & 0xff) << 8) |
                     (((long) data[i8 + 2] & 0xff) << 16) |
                     (((long) data[i8 + 3] & 0xff) << 24) |
                     (((long) data[i8 + 4] & 0xff) << 32) |
                     (((long) data[i8 + 5] & 0xff) << 40) |
                     (((long) data[i8 + 6] & 0xff) << 48) |
                     (((long) data[i8 + 7] & 0xff) << 56);
            
            k *= m;
            k ^= k >>> r;
            k *= m;
            
            h ^= k;
            h *= m;
        }
        
        int remaining = length % 8;
        if (remaining > 0) {
            int offset = length8 * 8;
            for (int i = 0; i < remaining; i++) {
                h ^= ((long) data[offset + i] & 0xff) << (i * 8);
            }
            h *= m;
        }
        
        h ^= h >>> r;
        h *= m;
        h ^= h >>> r;
        
        return h;
    }
    
    // ========== 内部类 ==========
    
    /**
     * 桶结构（无锁设计）
     */
    private static class Bucket {
        volatile long fingerprint;
        volatile long count;
        
        void setFingerprint(long fp) {
            this.fingerprint = fp;
        }
        
        void setCount(long c) {
            this.count = c;
        }
        
        long incrementCount() {
            return ++count;
        }
        
        long decrementCount() {
            if (count > 0) count--;
            return count;
        }
        
        void reset() {
            fingerprint = 0;
            count = 0;
        }
    }
    
    /**
     * 热点 Key 条目
     */
    public record HotKeyEntry(String key, long count) {}
    
    /**
     * 统计信息
     */
    public record HeavyKeeperStats(
        int confirmedHotKeyCount,
        int trackedKeyCount,
        long nonEmptyBuckets,
        long totalBuckets,
        long totalCount,
        long totalOperations,
        long totalDecays,
        long hotThreshold
    ) {}
}
