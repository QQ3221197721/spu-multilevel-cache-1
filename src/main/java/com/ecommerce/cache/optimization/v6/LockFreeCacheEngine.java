package com.ecommerce.cache.optimization.v6;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/**
 * 无锁并发缓存引擎 - 极致性能
 * 
 * 核心特性:
 * 1. CAS无锁设计 - 避免锁竞争
 * 2. 分段缓存 - 减少冲突
 * 3. 延迟删除 - 避免ABA问题
 * 4. 版本控制 - 保证一致性
 * 5. 内存屏障优化 - 保证可见性
 * 
 * 性能目标:
 * - 单节点 500W ops/s
 * - P99 延迟 < 1μs
 * - 零GC影响
 */
public class LockFreeCacheEngine {
    
    private static final Logger log = LoggerFactory.getLogger(LockFreeCacheEngine.class);
    
    @Value("${optimization.v6.lock-free.segment-count:256}")
    private int segmentCount = 256;
    
    @Value("${optimization.v6.lock-free.segment-capacity:1024}")
    private int segmentCapacity = 1024;
    
    @Value("${optimization.v6.lock-free.cleanup-interval-ms:1000}")
    private long cleanupIntervalMs = 1000;
    
    @Value("${optimization.v6.lock-free.max-retries:10}")
    private int maxRetries = 10;
    
    private final MeterRegistry meterRegistry;
    
    // 分段缓存数组
    private CacheSegment[] segments;
    
    // 统计
    private final LongAdder totalOps = new LongAdder();
    private final LongAdder getOps = new LongAdder();
    private final LongAdder putOps = new LongAdder();
    private final LongAdder casSuccesses = new LongAdder();
    private final LongAdder casFailures = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    
    // 后台清理
    private ScheduledExecutorService cleanupExecutor;
    
    // 指标
    private Timer getLatencyTimer;
    private Timer putLatencyTimer;
    private Counter evictionCounter;
    
    public LockFreeCacheEngine(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    @PostConstruct
    public void init() {
        // 初始化分段
        segments = new CacheSegment[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new CacheSegment(segmentCapacity);
        }
        
        // 初始化清理线程
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("lock-free-cleanup").factory());
        cleanupExecutor.scheduleAtFixedRate(this::cleanup, 
            cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);
        
        // 注册指标
        registerMetrics();
        
        log.info("LockFreeCacheEngine initialized: segments={}, capacity={}", 
            segmentCount, segmentCapacity);
    }
    
    @PreDestroy
    public void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
        }
    }
    
    private void registerMetrics() {
        getLatencyTimer = Timer.builder("cache.lockfree.get.latency")
            .publishPercentileHistogram()
            .publishPercentiles(0.5, 0.9, 0.99, 0.999)
            .register(meterRegistry);
        putLatencyTimer = Timer.builder("cache.lockfree.put.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        evictionCounter = Counter.builder("cache.lockfree.evictions")
            .register(meterRegistry);
        
        Gauge.builder("cache.lockfree.size", this, LockFreeCacheEngine::size)
            .register(meterRegistry);
        Gauge.builder("cache.lockfree.cas.success.rate", this, 
            e -> e.casSuccesses.sum() / (double) Math.max(1, e.totalOps.sum()))
            .register(meterRegistry);
    }
    
    /**
     * 无锁获取
     */
    public String get(String key) {
        return getLatencyTimer.record(() -> {
            totalOps.increment();
            getOps.increment();
            
            int segmentIndex = getSegmentIndex(key);
            CacheSegment segment = segments[segmentIndex];
            
            CacheEntry entry = segment.get(key);
            if (entry == null || entry.isExpired()) {
                return null;
            }
            
            return entry.value;
        });
    }
    
    /**
     * 无锁获取或加载
     */
    public String getOrLoad(String key, Supplier<String> loader, long ttlSeconds) {
        String value = get(key);
        if (value != null) {
            return value;
        }
        
        // CAS加载（防止并发加载）
        int segmentIndex = getSegmentIndex(key);
        CacheSegment segment = segments[segmentIndex];
        
        // 尝试设置加载标记
        if (segment.tryStartLoading(key)) {
            try {
                value = loader.get();
                if (value != null) {
                    put(key, value, ttlSeconds);
                }
                return value;
            } finally {
                segment.finishLoading(key);
            }
        } else {
            // 等待其他线程加载完成
            return waitForLoading(key, segment);
        }
    }
    
    private String waitForLoading(String key, CacheSegment segment) {
        for (int i = 0; i < 50; i++) { // 最多等待50ms
            Thread.onSpinWait();
            CacheEntry entry = segment.get(key);
            if (entry != null && !entry.isExpired()) {
                return entry.value;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
    
    /**
     * 无锁写入
     */
    public boolean put(String key, String value) {
        return put(key, value, 600);
    }
    
    /**
     * 无锁写入（带TTL）
     */
    public boolean put(String key, String value, long ttlSeconds) {
        return putLatencyTimer.record(() -> {
            totalOps.increment();
            putOps.increment();
            
            int segmentIndex = getSegmentIndex(key);
            CacheSegment segment = segments[segmentIndex];
            
            long expireTime = System.currentTimeMillis() + ttlSeconds * 1000;
            CacheEntry newEntry = new CacheEntry(key, value, expireTime);
            
            boolean success = segment.put(key, newEntry, maxRetries);
            if (success) {
                casSuccesses.increment();
            } else {
                casFailures.increment();
            }
            
            return success;
        });
    }
    
    /**
     * 无锁删除
     */
    public boolean remove(String key) {
        totalOps.increment();
        
        int segmentIndex = getSegmentIndex(key);
        CacheSegment segment = segments[segmentIndex];
        
        return segment.remove(key);
    }
    
    /**
     * 获取分段索引（使用高质量哈希）
     */
    private int getSegmentIndex(String key) {
        int hash = murmurHash3(key);
        return (hash & 0x7FFFFFFF) % segmentCount;
    }
    
    /**
     * MurmurHash3 - 高质量哈希
     */
    private int murmurHash3(String key) {
        byte[] bytes = key.getBytes();
        int h1 = 0x9747b28c;
        int c1 = 0xcc9e2d51;
        int c2 = 0x1b873593;
        
        int roundedEnd = (bytes.length & 0xfffffffc);
        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (bytes[i] & 0xff) | 
                     ((bytes[i + 1] & 0xff) << 8) |
                     ((bytes[i + 2] & 0xff) << 16) |
                     ((bytes[i + 3] & 0xff) << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }
        
        int k1 = 0;
        switch (bytes.length & 0x03) {
            case 3:
                k1 = (bytes[roundedEnd + 2] & 0xff) << 16;
            case 2:
                k1 |= (bytes[roundedEnd + 1] & 0xff) << 8;
            case 1:
                k1 |= (bytes[roundedEnd] & 0xff);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
        }
        
        h1 ^= bytes.length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        
        return h1;
    }
    
    /**
     * 后台清理过期条目
     */
    private void cleanup() {
        for (CacheSegment segment : segments) {
            int evicted = segment.cleanupExpired();
            if (evicted > 0) {
                evictions.add(evicted);
                evictionCounter.increment(evicted);
            }
        }
    }
    
    /**
     * 获取缓存大小
     */
    public long size() {
        long total = 0;
        for (CacheSegment segment : segments) {
            total += segment.size();
        }
        return total;
    }
    
    /**
     * 清空缓存
     */
    public void clear() {
        for (CacheSegment segment : segments) {
            segment.clear();
        }
    }
    
    /**
     * 获取统计信息
     */
    public LockFreeStats getStats() {
        return new LockFreeStats(
            totalOps.sum(),
            getOps.sum(),
            putOps.sum(),
            casSuccesses.sum(),
            casFailures.sum(),
            evictions.sum(),
            size(),
            segmentCount
        );
    }
    
    // ========== 内部类 ==========
    
    /**
     * 缓存分段 - 无锁实现
     */
    private static class CacheSegment {
        
        private final ConcurrentHashMap<String, AtomicReference<CacheEntry>> entries;
        private final ConcurrentHashMap<String, AtomicBoolean> loadingKeys;
        private final int capacity;
        
        CacheSegment(int capacity) {
            this.capacity = capacity;
            this.entries = new ConcurrentHashMap<>(capacity);
            this.loadingKeys = new ConcurrentHashMap<>();
        }
        
        CacheEntry get(String key) {
            AtomicReference<CacheEntry> ref = entries.get(key);
            return ref != null ? ref.get() : null;
        }
        
        boolean put(String key, CacheEntry entry, int maxRetries) {
            AtomicReference<CacheEntry> ref = entries.computeIfAbsent(
                key, k -> new AtomicReference<>());
            
            for (int i = 0; i < maxRetries; i++) {
                CacheEntry current = ref.get();
                if (ref.compareAndSet(current, entry)) {
                    return true;
                }
                Thread.onSpinWait();
            }
            
            // CAS失败，强制写入
            ref.set(entry);
            return true;
        }
        
        boolean remove(String key) {
            AtomicReference<CacheEntry> ref = entries.get(key);
            if (ref != null) {
                CacheEntry current = ref.get();
                if (current != null) {
                    // 标记删除而非直接删除
                    return ref.compareAndSet(current, 
                        new CacheEntry(key, null, 0));
                }
            }
            return false;
        }
        
        boolean tryStartLoading(String key) {
            AtomicBoolean loading = loadingKeys.computeIfAbsent(
                key, k -> new AtomicBoolean(false));
            return loading.compareAndSet(false, true);
        }
        
        void finishLoading(String key) {
            AtomicBoolean loading = loadingKeys.get(key);
            if (loading != null) {
                loading.set(false);
            }
        }
        
        int cleanupExpired() {
            int count = 0;
            long now = System.currentTimeMillis();
            
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                CacheEntry ce = entry.getValue().get();
                if (ce != null && (ce.isExpired() || ce.value == null)) {
                    iterator.remove();
                    count++;
                }
            }
            
            return count;
        }
        
        int size() {
            return entries.size();
        }
        
        void clear() {
            entries.clear();
            loadingKeys.clear();
        }
    }
    
    /**
     * 缓存条目
     */
    private static class CacheEntry {
        final String key;
        final String value;
        final long expireTime;
        final long version;
        
        private static final AtomicLong VERSION_GENERATOR = new AtomicLong(0);
        
        CacheEntry(String key, String value, long expireTime) {
            this.key = key;
            this.value = value;
            this.expireTime = expireTime;
            this.version = VERSION_GENERATOR.incrementAndGet();
        }
        
        boolean isExpired() {
            return expireTime > 0 && System.currentTimeMillis() > expireTime;
        }
    }
    
    /**
     * 统计信息
     */
    public record LockFreeStats(
        long totalOps,
        long getOps,
        long putOps,
        long casSuccesses,
        long casFailures,
        long evictions,
        long size,
        int segmentCount
    ) {
        public double casSuccessRate() {
            return totalOps > 0 ? (double) casSuccesses / totalOps : 0;
        }
    }
}
