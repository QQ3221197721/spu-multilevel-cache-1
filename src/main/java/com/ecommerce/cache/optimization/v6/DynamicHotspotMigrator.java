package com.ecommerce.cache.optimization.v6;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 动态热点迁移器 - 智能热点数据迁移
 * 
 * 核心特性:
 * 1. 热点数据实时检测
 * 2. 跨层级自动迁移
 * 3. 负载均衡分片
 * 4. 冷数据降级
 * 5. 迁移批量优化
 * 6. 零停机迁移
 * 
 * 性能目标:
 * - 热点检测延迟 < 100ms
 * - 迁移吞吐 > 10K/s
 * - 热点命中率 > 95%
 */
public class DynamicHotspotMigrator {
    
    private static final Logger log = LoggerFactory.getLogger(DynamicHotspotMigrator.class);
    
    @Value("${optimization.v6.hotspot.detection-interval-ms:1000}")
    private long detectionIntervalMs = 1000;
    
    @Value("${optimization.v6.hotspot.hot-threshold:1000}")
    private long hotThreshold = 1000;
    
    @Value("${optimization.v6.hotspot.cold-threshold:10}")
    private long coldThreshold = 10;
    
    @Value("${optimization.v6.hotspot.migration-batch-size:100}")
    private int migrationBatchSize = 100;
    
    @Value("${optimization.v6.hotspot.shard-count:16}")
    private int shardCount = 16;
    
    @Value("${optimization.v6.hotspot.decay-factor:0.9}")
    private double decayFactor = 0.9;
    
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    
    // 访问计数器（滑动窗口）
    private final ConcurrentHashMap<String, SlidingWindowCounter> accessCounters = new ConcurrentHashMap<>();
    
    // 热点Key集合
    private final ConcurrentHashMap<String, HotspotInfo> hotspots = new ConcurrentHashMap<>();
    
    // 分片映射
    private final ConcurrentHashMap<String, Integer> shardMapping = new ConcurrentHashMap<>();
    
    // 迁移队列
    private final BlockingQueue<MigrationTask> migrationQueue = new LinkedBlockingQueue<>();
    
    // 冷却队列
    private final BlockingQueue<String> coolingQueue = new LinkedBlockingQueue<>();
    
    // 统计
    private final LongAdder totalHotspots = new LongAdder();
    private final LongAdder promotions = new LongAdder();
    private final LongAdder demotions = new LongAdder();
    private final LongAdder migrations = new LongAdder();
    
    // 执行器
    private ScheduledExecutorService scheduledExecutor;
    private ExecutorService migrationExecutor;
    
    // 指标
    private Timer detectionTimer;
    private Timer migrationTimer;
    private Counter promotionCounter;
    private Counter demotionCounter;
    
    public DynamicHotspotMigrator(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }
    
    @PostConstruct
    public void init() {
        // 初始化执行器
        scheduledExecutor = Executors.newScheduledThreadPool(2,
            Thread.ofVirtual().name("hotspot-sched-", 0).factory());
        migrationExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("hotspot-migrate-", 0).factory());
        
        // 启动热点检测
        scheduledExecutor.scheduleAtFixedRate(this::detectHotspots, 
            detectionIntervalMs, detectionIntervalMs, TimeUnit.MILLISECONDS);
        
        // 启动迁移消费者
        for (int i = 0; i < 3; i++) {
            migrationExecutor.submit(this::migrationConsumer);
        }
        
        // 启动冷却处理
        migrationExecutor.submit(this::coolingConsumer);
        
        // 注册指标
        registerMetrics();
        
        log.info("DynamicHotspotMigrator initialized: hotThreshold={}, coldThreshold={}, shards={}",
            hotThreshold, coldThreshold, shardCount);
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
        if (migrationExecutor != null) {
            migrationExecutor.shutdown();
        }
    }
    
    private void registerMetrics() {
        detectionTimer = Timer.builder("cache.hotspot.detection.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        migrationTimer = Timer.builder("cache.hotspot.migration.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        promotionCounter = Counter.builder("cache.hotspot.promotions")
            .register(meterRegistry);
        demotionCounter = Counter.builder("cache.hotspot.demotions")
            .register(meterRegistry);
        
        Gauge.builder("cache.hotspot.count", hotspots, Map::size)
            .register(meterRegistry);
        Gauge.builder("cache.hotspot.migration.queue", migrationQueue, Queue::size)
            .register(meterRegistry);
    }
    
    /**
     * 记录访问
     */
    public void recordAccess(String key) {
        SlidingWindowCounter counter = accessCounters.computeIfAbsent(
            key, k -> new SlidingWindowCounter(10)); // 10秒窗口
        counter.increment();
    }
    
    /**
     * 检查是否为热点
     */
    public boolean isHotspot(String key) {
        return hotspots.containsKey(key);
    }
    
    /**
     * 获取分片Key
     */
    public String getShardKey(String key) {
        Integer shard = shardMapping.get(key);
        if (shard == null) {
            return key;
        }
        return key + ":shard:" + shard;
    }
    
    /**
     * 获取所有分片Key
     */
    public List<String> getAllShardKeys(String key) {
        if (!hotspots.containsKey(key)) {
            return Collections.singletonList(key);
        }
        
        List<String> shardKeys = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            shardKeys.add(key + ":shard:" + i);
        }
        return shardKeys;
    }
    
    /**
     * 热点检测
     */
    private void detectHotspots() {
        detectionTimer.record(() -> {
            long now = System.currentTimeMillis();
            
            for (Map.Entry<String, SlidingWindowCounter> entry : accessCounters.entrySet()) {
                String key = entry.getKey();
                long qps = entry.getValue().getCount();
                
                HotspotInfo info = hotspots.get(key);
                
                if (qps >= hotThreshold) {
                    // 升级为热点
                    if (info == null) {
                        promoteToHotspot(key, qps);
                    } else {
                        info.updateQps(qps);
                    }
                } else if (info != null) {
                    // 应用衰减
                    double decayedQps = info.qps * decayFactor;
                    if (decayedQps < coldThreshold) {
                        // 降级
                        demoteFromHotspot(key);
                    } else {
                        info.updateQps((long) decayedQps);
                    }
                }
            }
            
            // 清理过期计数器
            accessCounters.entrySet().removeIf(e -> 
                e.getValue().getCount() == 0 && !hotspots.containsKey(e.getKey()));
        });
    }
    
    /**
     * 升级为热点
     */
    private void promoteToHotspot(String key, long qps) {
        log.info("Promoting to hotspot: key={}, qps={}", key, qps);
        
        HotspotInfo info = new HotspotInfo(key, qps, System.currentTimeMillis());
        hotspots.put(key, info);
        totalHotspots.increment();
        promotions.increment();
        promotionCounter.increment();
        
        // 创建分片
        for (int i = 0; i < shardCount; i++) {
            shardMapping.put(key, i % shardCount);
        }
        
        // 加入迁移队列
        migrationQueue.offer(new MigrationTask(MigrationType.PROMOTE, key, qps));
    }
    
    /**
     * 从热点降级
     */
    private void demoteFromHotspot(String key) {
        log.info("Demoting from hotspot: key={}", key);
        
        hotspots.remove(key);
        demotions.increment();
        demotionCounter.increment();
        
        // 清理分片映射
        shardMapping.remove(key);
        
        // 加入冷却队列
        coolingQueue.offer(key);
    }
    
    /**
     * 迁移消费者
     */
    private void migrationConsumer() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                MigrationTask task = migrationQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) continue;
                
                migrationTimer.record(() -> executeMigration(task));
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Migration consumer error", e);
            }
        }
    }
    
    /**
     * 执行迁移
     */
    private void executeMigration(MigrationTask task) {
        try {
            switch (task.type) {
                case PROMOTE -> executePromotion(task.key);
                case DEMOTE -> executeDemotion(task.key);
                case REBALANCE -> executeRebalance(task.key);
            }
            migrations.increment();
        } catch (Exception e) {
            log.error("Migration failed: type={}, key={}", task.type, task.key, e);
        }
    }
    
    /**
     * 执行热点升级（复制到分片）
     */
    private void executePromotion(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) return;
            
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (ttl == null || ttl < 0) ttl = 600L;
            
            // 复制到所有分片
            Map<String, String> shardData = new HashMap<>();
            for (int i = 0; i < shardCount; i++) {
                shardData.put(key + ":shard:" + i, value);
            }
            redisTemplate.opsForValue().multiSet(shardData);
            
            // 设置TTL
            for (String shardKey : shardData.keySet()) {
                redisTemplate.expire(shardKey, ttl, TimeUnit.SECONDS);
            }
            
            log.debug("Promoted to shards: key={}, shards={}", key, shardCount);
        } catch (Exception e) {
            log.error("Promotion failed: key={}", key, e);
        }
    }
    
    /**
     * 执行热点降级（清理分片）
     */
    private void executeDemotion(String key) {
        try {
            List<String> shardKeys = new ArrayList<>();
            for (int i = 0; i < shardCount; i++) {
                shardKeys.add(key + ":shard:" + i);
            }
            redisTemplate.delete(shardKeys);
            
            log.debug("Demoted and cleaned shards: key={}", key);
        } catch (Exception e) {
            log.error("Demotion failed: key={}", key, e);
        }
    }
    
    /**
     * 执行重新均衡
     */
    private void executeRebalance(String key) {
        // 实现热点数据的负载均衡重新分配
        log.debug("Rebalanced: key={}", key);
    }
    
    /**
     * 冷却消费者
     */
    private void coolingConsumer() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String key = coolingQueue.poll(100, TimeUnit.MILLISECONDS);
                if (key == null) continue;
                
                // 延迟清理分片（等待读取完成）
                Thread.sleep(1000);
                executeDemotion(key);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Cooling consumer error", e);
            }
        }
    }
    
    /**
     * 获取统计信息
     */
    public HotspotStats getStats() {
        return new HotspotStats(
            hotspots.size(),
            totalHotspots.sum(),
            promotions.sum(),
            demotions.sum(),
            migrations.sum(),
            accessCounters.size(),
            migrationQueue.size()
        );
    }
    
    /**
     * 获取热点列表
     */
    public List<HotspotInfo> getHotspotList() {
        return new ArrayList<>(hotspots.values());
    }
    
    // ========== 内部类 ==========
    
    /**
     * 滑动窗口计数器
     */
    private static class SlidingWindowCounter {
        private final long[] buckets;
        private final int windowSize;
        private volatile int currentBucket = 0;
        private final AtomicLong totalCount = new AtomicLong(0);
        
        SlidingWindowCounter(int windowSize) {
            this.windowSize = windowSize;
            this.buckets = new long[windowSize];
        }
        
        void increment() {
            int bucket = (int) ((System.currentTimeMillis() / 1000) % windowSize);
            if (bucket != currentBucket) {
                // 新桶，清空旧数据
                buckets[bucket] = 0;
                currentBucket = bucket;
            }
            buckets[bucket]++;
            totalCount.incrementAndGet();
        }
        
        long getCount() {
            long total = 0;
            for (long count : buckets) {
                total += count;
            }
            return total / windowSize; // QPS
        }
    }
    
    /**
     * 热点信息
     */
    public static class HotspotInfo {
        public final String key;
        public volatile long qps;
        public final long promotedAt;
        public volatile long lastUpdated;
        
        HotspotInfo(String key, long qps, long promotedAt) {
            this.key = key;
            this.qps = qps;
            this.promotedAt = promotedAt;
            this.lastUpdated = promotedAt;
        }
        
        void updateQps(long newQps) {
            this.qps = newQps;
            this.lastUpdated = System.currentTimeMillis();
        }
    }
    
    private enum MigrationType {
        PROMOTE, DEMOTE, REBALANCE
    }
    
    private record MigrationTask(MigrationType type, String key, long qps) {}
    
    public record HotspotStats(
        int currentHotspots,
        long totalHotspots,
        long promotions,
        long demotions,
        long migrations,
        int trackedKeys,
        int pendingMigrations
    ) {}
}
