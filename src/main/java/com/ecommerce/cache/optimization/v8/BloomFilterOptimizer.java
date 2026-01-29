package com.ecommerce.cache.optimization.v8;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 高性能布隆过滤器优化器
 * 
 * 核心特性:
 * 1. 分片布隆过滤器: 减少锁竞争
 * 2. 计数布隆过滤器: 支持删除操作
 * 3. 可扩展过滤器: 自动扩容
 * 4. 持久化支持: 数据持久化与恢复
 * 5. 多层过滤: 热数据/冷数据分离
 * 6. 统计分析: 误判率实时监控
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomFilterOptimizer {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV8Properties properties;
    
    // ========== 过滤器存储 ==========
    
    /** 分片过滤器 */
    private BloomFilterShard[] shards;
    
    /** 计数过滤器(支持删除) */
    private CountingBloomFilter countingFilter;
    
    /** 可扩展过滤器 */
    private final List<StandardBloomFilter> scalableFilters = new CopyOnWriteArrayList<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong addCount = new AtomicLong(0);
    private final AtomicLong queryCount = new AtomicLong(0);
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong fpCount = new AtomicLong(0);
    
    // ========== 配置 ==========
    
    private int shardCount;
    private long expectedInsertions;
    private double fpp;
    
    // ========== 指标 ==========
    
    private Counter addCounter;
    private Counter queryCounter;
    private Counter hitCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getBloomFilter().isEnabled()) {
            log.info("[布隆过滤器] 已禁用");
            return;
        }
        
        shardCount = properties.getBloomFilter().getShards();
        expectedInsertions = properties.getBloomFilter().getExpectedInsertions();
        fpp = properties.getBloomFilter().getFpp();
        
        // 初始化分片
        shards = new BloomFilterShard[shardCount];
        long perShardSize = expectedInsertions / shardCount;
        for (int i = 0; i < shardCount; i++) {
            shards[i] = new BloomFilterShard(perShardSize, fpp);
        }
        
        // 初始化计数过滤器
        countingFilter = new CountingBloomFilter(expectedInsertions / 10, fpp);
        
        // 初始化第一个可扩展过滤器
        scalableFilters.add(new StandardBloomFilter(expectedInsertions / 10, fpp));
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bloom-filter-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 尝试加载持久化数据
        if (properties.getBloomFilter().isPersistenceEnabled()) {
            loadFromDisk();
        }
        
        // 定期持久化
        scheduler.scheduleWithFixedDelay(
            this::persistToDisk,
            60,
            60,
            TimeUnit.SECONDS
        );
        
        // 定期检查扩容
        if (properties.getBloomFilter().isAutoResize()) {
            scheduler.scheduleWithFixedDelay(
                this::checkAndExpand,
                30,
                30,
                TimeUnit.SECONDS
            );
        }
        
        log.info("[布隆过滤器] 初始化完成 - 分片: {}, 预期容量: {}, 误判率: {}",
            shardCount, expectedInsertions, fpp);
    }
    
    @PreDestroy
    public void shutdown() {
        if (properties.getBloomFilter().isPersistenceEnabled()) {
            persistToDisk();
        }
        
        if (scheduler != null) {
            scheduler.shutdown();
        }
        
        log.info("[布隆过滤器] 已关闭 - 添加: {}, 查询: {}, 命中: {}",
            addCount.get(), queryCount.get(), hitCount.get());
    }
    
    private void initMetrics() {
        addCounter = Counter.builder("cache.bloom.add")
            .description("布隆过滤器添加次数")
            .register(meterRegistry);
        
        queryCounter = Counter.builder("cache.bloom.query")
            .description("布隆过滤器查询次数")
            .register(meterRegistry);
        
        hitCounter = Counter.builder("cache.bloom.hit")
            .description("布隆过滤器命中次数")
            .register(meterRegistry);
        
        Gauge.builder("cache.bloom.fill_ratio", this::getOverallFillRatio)
            .description("布隆过滤器填充率")
            .register(meterRegistry);
    }
    
    // ========== 核心API ==========
    
    /**
     * 添加元素
     */
    public void add(String key) {
        int shard = getShardIndex(key);
        shards[shard].add(key);
        
        addCount.incrementAndGet();
        addCounter.increment();
    }
    
    /**
     * 批量添加
     */
    public void addAll(Collection<String> keys) {
        for (String key : keys) {
            add(key);
        }
    }
    
    /**
     * 检查是否可能存在
     */
    public boolean mightContain(String key) {
        queryCount.incrementAndGet();
        queryCounter.increment();
        
        int shard = getShardIndex(key);
        boolean result = shards[shard].mightContain(key);
        
        if (result) {
            hitCount.incrementAndGet();
            hitCounter.increment();
        }
        
        return result;
    }
    
    /**
     * 批量检查
     */
    public Map<String, Boolean> mightContainBatch(Collection<String> keys) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (String key : keys) {
            results.put(key, mightContain(key));
        }
        return results;
    }
    
    /**
     * 添加到计数过滤器(支持删除)
     */
    public void addCounting(String key) {
        countingFilter.add(key);
        addCount.incrementAndGet();
    }
    
    /**
     * 从计数过滤器删除
     */
    public void removeCounting(String key) {
        countingFilter.remove(key);
    }
    
    /**
     * 检查计数过滤器
     */
    public boolean mightContainCounting(String key) {
        queryCount.incrementAndGet();
        return countingFilter.mightContain(key);
    }
    
    /**
     * 添加到可扩展过滤器
     */
    public void addScalable(String key) {
        StandardBloomFilter current = scalableFilters.get(scalableFilters.size() - 1);
        current.add(key);
        addCount.incrementAndGet();
    }
    
    /**
     * 检查可扩展过滤器
     */
    public boolean mightContainScalable(String key) {
        queryCount.incrementAndGet();
        
        for (StandardBloomFilter filter : scalableFilters) {
            if (filter.mightContain(key)) {
                hitCount.incrementAndGet();
                return true;
            }
        }
        return false;
    }
    
    /**
     * 记录误判
     */
    public void recordFalsePositive() {
        fpCount.incrementAndGet();
    }
    
    /**
     * 清空过滤器
     */
    public void clear() {
        for (BloomFilterShard shard : shards) {
            shard.clear();
        }
        countingFilter.clear();
        
        scalableFilters.clear();
        scalableFilters.add(new StandardBloomFilter(expectedInsertions / 10, fpp));
        
        addCount.set(0);
        queryCount.set(0);
        hitCount.set(0);
        fpCount.set(0);
        
        log.info("[布隆过滤器] 已清空");
    }
    
    // ========== 内部方法 ==========
    
    private int getShardIndex(String key) {
        return Math.abs(key.hashCode() % shardCount);
    }
    
    private void checkAndExpand() {
        StandardBloomFilter current = scalableFilters.get(scalableFilters.size() - 1);
        double fillRatio = current.getFillRatio();
        
        if (fillRatio > properties.getBloomFilter().getResizeThreshold()) {
            StandardBloomFilter newFilter = new StandardBloomFilter(
                expectedInsertions / 10,
                fpp / Math.pow(2, scalableFilters.size())
            );
            scalableFilters.add(newFilter);
            
            log.info("[布隆过滤器] 自动扩容 - 新增过滤器 #{}, 总数: {}",
                scalableFilters.size(), scalableFilters.size());
        }
    }
    
    private void persistToDisk() {
        if (!properties.getBloomFilter().isPersistenceEnabled()) {
            return;
        }
        
        String path = properties.getBloomFilter().getPersistencePath();
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        try {
            // 持久化分片
            for (int i = 0; i < shards.length; i++) {
                File file = new File(path, "shard_" + i + ".bloom");
                shards[i].saveTo(file);
            }
            
            log.debug("[布隆过滤器] 持久化完成");
        } catch (Exception e) {
            log.warn("[布隆过滤器] 持久化失败: {}", e.getMessage());
        }
    }
    
    private void loadFromDisk() {
        String path = properties.getBloomFilter().getPersistencePath();
        File dir = new File(path);
        
        if (!dir.exists()) {
            return;
        }
        
        try {
            for (int i = 0; i < shards.length; i++) {
                File file = new File(path, "shard_" + i + ".bloom");
                if (file.exists()) {
                    shards[i].loadFrom(file);
                }
            }
            
            log.info("[布隆过滤器] 从磁盘加载完成");
        } catch (Exception e) {
            log.warn("[布隆过滤器] 加载失败: {}", e.getMessage());
        }
    }
    
    private double getOverallFillRatio() {
        double total = 0;
        for (BloomFilterShard shard : shards) {
            total += shard.getFillRatio();
        }
        return total / shards.length;
    }
    
    // ========== 统计信息 ==========
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("addCount", addCount.get());
        stats.put("queryCount", queryCount.get());
        stats.put("hitCount", hitCount.get());
        stats.put("falsePositiveCount", fpCount.get());
        stats.put("shardCount", shardCount);
        stats.put("scalableFilterCount", scalableFilters.size());
        stats.put("overallFillRatio", String.format("%.2f%%", getOverallFillRatio() * 100));
        
        // 分片统计
        List<Map<String, Object>> shardStats = new ArrayList<>();
        for (int i = 0; i < shards.length; i++) {
            Map<String, Object> ss = new LinkedHashMap<>();
            ss.put("index", i);
            ss.put("fillRatio", String.format("%.2f%%", shards[i].getFillRatio() * 100));
            ss.put("size", shards[i].getSize());
            shardStats.add(ss);
        }
        stats.put("shards", shardStats);
        
        // 误判率
        if (hitCount.get() > 0) {
            stats.put("actualFpp", String.format("%.4f%%", 
                (double) fpCount.get() / hitCount.get() * 100));
        }
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    /**
     * 布隆过滤器分片
     */
    private static class BloomFilterShard {
        private final long[] bits;
        private final int numHashFunctions;
        private final int bitSize;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private long elementCount = 0;
        
        BloomFilterShard(long expectedInsertions, double fpp) {
            this.bitSize = optimalBitSize(expectedInsertions, fpp);
            this.numHashFunctions = optimalNumHashFunctions(expectedInsertions, bitSize);
            this.bits = new long[(bitSize + 63) / 64];
        }
        
        void add(String key) {
            lock.writeLock().lock();
            try {
                long hash64 = hash(key);
                int h1 = (int) hash64;
                int h2 = (int) (hash64 >>> 32);
                
                for (int i = 0; i < numHashFunctions; i++) {
                    int combinedHash = h1 + i * h2;
                    int pos = (combinedHash & Integer.MAX_VALUE) % bitSize;
                    bits[pos / 64] |= (1L << (pos % 64));
                }
                elementCount++;
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        boolean mightContain(String key) {
            lock.readLock().lock();
            try {
                long hash64 = hash(key);
                int h1 = (int) hash64;
                int h2 = (int) (hash64 >>> 32);
                
                for (int i = 0; i < numHashFunctions; i++) {
                    int combinedHash = h1 + i * h2;
                    int pos = (combinedHash & Integer.MAX_VALUE) % bitSize;
                    if ((bits[pos / 64] & (1L << (pos % 64))) == 0) {
                        return false;
                    }
                }
                return true;
            } finally {
                lock.readLock().unlock();
            }
        }
        
        void clear() {
            lock.writeLock().lock();
            try {
                Arrays.fill(bits, 0L);
                elementCount = 0;
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        double getFillRatio() {
            lock.readLock().lock();
            try {
                long setBits = 0;
                for (long word : bits) {
                    setBits += Long.bitCount(word);
                }
                return (double) setBits / bitSize;
            } finally {
                lock.readLock().unlock();
            }
        }
        
        long getSize() {
            return elementCount;
        }
        
        void saveTo(File file) throws IOException {
            lock.readLock().lock();
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
                dos.writeInt(bitSize);
                dos.writeInt(numHashFunctions);
                dos.writeLong(elementCount);
                for (long word : bits) {
                    dos.writeLong(word);
                }
            } finally {
                lock.readLock().unlock();
            }
        }
        
        void loadFrom(File file) throws IOException {
            lock.writeLock().lock();
            try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                dis.readInt(); // bitSize
                dis.readInt(); // numHashFunctions
                elementCount = dis.readLong();
                for (int i = 0; i < bits.length; i++) {
                    bits[i] = dis.readLong();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        private long hash(String key) {
            long h = 0;
            for (char c : key.toCharArray()) {
                h = 31 * h + c;
            }
            return h ^ (h >>> 33);
        }
        
        private static int optimalBitSize(long n, double p) {
            return (int) (-n * Math.log(p) / (Math.log(2) * Math.log(2)));
        }
        
        private static int optimalNumHashFunctions(long n, int m) {
            return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
        }
    }
    
    /**
     * 计数布隆过滤器
     */
    private static class CountingBloomFilter {
        private final int[] counters;
        private final int numHashFunctions;
        private final int size;
        
        CountingBloomFilter(long expectedInsertions, double fpp) {
            this.size = (int) (-expectedInsertions * Math.log(fpp) / (Math.log(2) * Math.log(2)));
            this.numHashFunctions = Math.max(1, (int) Math.round((double) size / expectedInsertions * Math.log(2)));
            this.counters = new int[size];
        }
        
        synchronized void add(String key) {
            long hash64 = hash(key);
            int h1 = (int) hash64;
            int h2 = (int) (hash64 >>> 32);
            
            for (int i = 0; i < numHashFunctions; i++) {
                int pos = ((h1 + i * h2) & Integer.MAX_VALUE) % size;
                if (counters[pos] < Integer.MAX_VALUE) {
                    counters[pos]++;
                }
            }
        }
        
        synchronized void remove(String key) {
            long hash64 = hash(key);
            int h1 = (int) hash64;
            int h2 = (int) (hash64 >>> 32);
            
            for (int i = 0; i < numHashFunctions; i++) {
                int pos = ((h1 + i * h2) & Integer.MAX_VALUE) % size;
                if (counters[pos] > 0) {
                    counters[pos]--;
                }
            }
        }
        
        synchronized boolean mightContain(String key) {
            long hash64 = hash(key);
            int h1 = (int) hash64;
            int h2 = (int) (hash64 >>> 32);
            
            for (int i = 0; i < numHashFunctions; i++) {
                int pos = ((h1 + i * h2) & Integer.MAX_VALUE) % size;
                if (counters[pos] == 0) {
                    return false;
                }
            }
            return true;
        }
        
        synchronized void clear() {
            Arrays.fill(counters, 0);
        }
        
        private long hash(String key) {
            long h = 0;
            for (char c : key.toCharArray()) {
                h = 31 * h + c;
            }
            return h ^ (h >>> 33);
        }
    }
    
    /**
     * 标准布隆过滤器
     */
    @Data
    private static class StandardBloomFilter {
        private final long[] bits;
        private final int numHashFunctions;
        private final int bitSize;
        private long elementCount = 0;
        
        StandardBloomFilter(long expectedInsertions, double fpp) {
            this.bitSize = (int) (-expectedInsertions * Math.log(fpp) / (Math.log(2) * Math.log(2)));
            this.numHashFunctions = Math.max(1, (int) Math.round((double) bitSize / expectedInsertions * Math.log(2)));
            this.bits = new long[(bitSize + 63) / 64];
        }
        
        synchronized void add(String key) {
            long hash64 = hash(key);
            int h1 = (int) hash64;
            int h2 = (int) (hash64 >>> 32);
            
            for (int i = 0; i < numHashFunctions; i++) {
                int pos = ((h1 + i * h2) & Integer.MAX_VALUE) % bitSize;
                bits[pos / 64] |= (1L << (pos % 64));
            }
            elementCount++;
        }
        
        synchronized boolean mightContain(String key) {
            long hash64 = hash(key);
            int h1 = (int) hash64;
            int h2 = (int) (hash64 >>> 32);
            
            for (int i = 0; i < numHashFunctions; i++) {
                int pos = ((h1 + i * h2) & Integer.MAX_VALUE) % bitSize;
                if ((bits[pos / 64] & (1L << (pos % 64))) == 0) {
                    return false;
                }
            }
            return true;
        }
        
        double getFillRatio() {
            long setBits = 0;
            for (long word : bits) {
                setBits += Long.bitCount(word);
            }
            return (double) setBits / bitSize;
        }
        
        private long hash(String key) {
            long h = 0;
            for (char c : key.toCharArray()) {
                h = 31 * h + c;
            }
            return h ^ (h >>> 33);
        }
    }
}
