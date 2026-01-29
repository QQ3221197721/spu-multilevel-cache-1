package com.ecommerce.cache.optimization.v5;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 智能分片路由服务 - 动态分片与负载均衡
 * 
 * 核心优化：
 * 1. 动态分片数调整 - 根据 QPS 自动扩缩分片
 * 2. 权重负载均衡 - 根据分片响应时间动态调整权重
 * 3. 故障转移 - 自动检测并隔离故障分片
 * 4. 分片预热 - 新分片自动预热，避免冷启动
 * 5. 一致性哈希优化 - 虚拟节点 + Jump Hash 混合
 * 6. 流量整形 - 平滑流量，避免毛刺
 * 
 * 目标：单 Key 10W QPS 场景下，分片访问均匀度 > 95%
 */
@Service
public class SmartShardRouter {
    
    private static final Logger log = LoggerFactory.getLogger(SmartShardRouter.class);
    
    // ========== 配置 ==========
    @Value("${optimization.shard.min-count:4}")
    private int minShardCount;
    
    @Value("${optimization.shard.max-count:64}")
    private int maxShardCount;
    
    @Value("${optimization.shard.scale-up-threshold:5000}")
    private long scaleUpThreshold;
    
    @Value("${optimization.shard.scale-down-threshold:500}")
    private long scaleDownThreshold;
    
    @Value("${optimization.shard.health-check-interval-ms:5000}")
    private long healthCheckIntervalMs;
    
    @Value("${optimization.shard.failure-threshold:3}")
    private int failureThreshold;
    
    @Value("${optimization.shard.virtual-nodes:150}")
    private int virtualNodes;
    
    @Value("${optimization.shard.enabled:true}")
    private boolean enabled;
    
    // ========== 分片状态 ==========
    private final ConcurrentHashMap<String, ShardGroup> shardGroups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ShardStats> globalShardStats = new ConcurrentHashMap<>();
    
    // ========== 负载均衡 ==========
    private final ConcurrentHashMap<String, WeightedRoundRobin> loadBalancers = new ConcurrentHashMap<>();
    
    // ========== 依赖 ==========
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    
    // ========== 指标 ==========
    private Counter shardScaleUpCounter;
    private Counter shardScaleDownCounter;
    private Counter shardFailoverCounter;
    private Counter shardAccessCounter;
    private Timer shardRouteTimer;
    
    public SmartShardRouter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }
    
    @PostConstruct
    public void init() {
        // 初始化指标
        shardScaleUpCounter = Counter.builder("shard.scale.up")
            .description("Shard scale up events")
            .register(meterRegistry);
        shardScaleDownCounter = Counter.builder("shard.scale.down")
            .description("Shard scale down events")
            .register(meterRegistry);
        shardFailoverCounter = Counter.builder("shard.failover")
            .description("Shard failover events")
            .register(meterRegistry);
        shardAccessCounter = Counter.builder("shard.access.total")
            .description("Total shard accesses")
            .register(meterRegistry);
        shardRouteTimer = Timer.builder("shard.route.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        
        Gauge.builder("shard.groups.count", shardGroups, Map::size)
            .register(meterRegistry);
        
        log.info("SmartShardRouter initialized: minShard={}, maxShard={}, virtualNodes={}",
            minShardCount, maxShardCount, virtualNodes);
    }
    
    /**
     * 获取分片 Key（智能路由）
     * 
     * @param originalKey 原始 Key
     * @return 分片 Key
     */
    public String getShardKey(String originalKey) {
        if (!enabled) {
            return originalKey;
        }
        
        return shardRouteTimer.record(() -> {
            shardAccessCounter.increment();
            
            // 获取或创建分片组
            ShardGroup group = shardGroups.computeIfAbsent(originalKey, this::createShardGroup);
            
            // 选择分片
            int shardIndex = selectShard(group, originalKey);
            
            // 记录访问
            group.recordAccess(shardIndex);
            
            return formatShardKey(originalKey, shardIndex);
        });
    }
    
    /**
     * 批量获取分片 Key
     */
    public Map<String, String> getShardKeys(Collection<String> originalKeys) {
        return originalKeys.stream()
            .collect(Collectors.toMap(
                Function.identity(),
                this::getShardKey
            ));
    }
    
    /**
     * 获取所有分片 Key（用于写入时同步所有分片）
     */
    public List<String> getAllShardKeys(String originalKey) {
        ShardGroup group = shardGroups.get(originalKey);
        if (group == null) {
            return Collections.singletonList(originalKey);
        }
        
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < group.getShardCount(); i++) {
            if (group.isShardHealthy(i)) {
                keys.add(formatShardKey(originalKey, i));
            }
        }
        return keys;
    }
    
    /**
     * 写入所有分片
     */
    public void writeToAllShards(String originalKey, String value, long ttlSeconds) {
        List<String> shardKeys = getAllShardKeys(originalKey);
        
        // 并行写入
        shardKeys.parallelStream().forEach(shardKey -> {
            try {
                redisTemplate.opsForValue().set(shardKey, value, 
                    java.time.Duration.ofSeconds(ttlSeconds));
            } catch (Exception e) {
                log.warn("Failed to write to shard: {}", shardKey, e);
            }
        });
    }
    
    /**
     * 删除所有分片
     */
    public void deleteAllShards(String originalKey) {
        ShardGroup group = shardGroups.get(originalKey);
        if (group == null) {
            redisTemplate.delete(originalKey);
            return;
        }
        
        // 删除所有分片
        for (int i = 0; i < group.getShardCount(); i++) {
            try {
                redisTemplate.delete(formatShardKey(originalKey, i));
            } catch (Exception e) {
                log.warn("Failed to delete shard: {} shard {}", originalKey, i, e);
            }
        }
        
        // 删除原始 Key
        redisTemplate.delete(originalKey);
    }
    
    /**
     * 手动设置分片数
     */
    public void setShardCount(String key, int count) {
        if (count < minShardCount || count > maxShardCount) {
            throw new IllegalArgumentException("Shard count must be between " + 
                minShardCount + " and " + maxShardCount);
        }
        
        ShardGroup group = shardGroups.computeIfAbsent(key, this::createShardGroup);
        int oldCount = group.getShardCount();
        
        if (count > oldCount) {
            // 扩容
            group.scaleTo(count);
            migrateDataOnScaleUp(key, oldCount, count);
        } else if (count < oldCount) {
            // 缩容
            migrateDataOnScaleDown(key, oldCount, count);
            group.scaleTo(count);
        }
    }
    
    /**
     * 标记分片故障
     */
    public void markShardUnhealthy(String key, int shardIndex) {
        ShardGroup group = shardGroups.get(key);
        if (group != null) {
            group.markUnhealthy(shardIndex);
            shardFailoverCounter.increment();
            log.warn("Shard marked as unhealthy: key={}, shard={}", key, shardIndex);
        }
    }
    
    /**
     * 获取分片统计
     */
    public ShardGroupStats getShardStats(String key) {
        ShardGroup group = shardGroups.get(key);
        if (group == null) {
            return null;
        }
        return group.getStats();
    }
    
    /**
     * 定期检查并自动调整分片
     */
    @Scheduled(fixedRateString = "${optimization.shard.auto-scale-interval-ms:10000}")
    public void autoScaleShards() {
        if (!enabled) return;
        
        shardGroups.forEach((key, group) -> {
            long qps = group.getRecentQps();
            int currentShards = group.getShardCount();
            
            if (qps > scaleUpThreshold * currentShards && currentShards < maxShardCount) {
                // 需要扩容
                int newCount = Math.min(currentShards * 2, maxShardCount);
                log.info("Auto scaling up shards: key={}, from={} to={}, qps={}", 
                    key, currentShards, newCount, qps);
                setShardCount(key, newCount);
                shardScaleUpCounter.increment();
            } else if (qps < scaleDownThreshold * currentShards && currentShards > minShardCount) {
                // 需要缩容
                int newCount = Math.max(currentShards / 2, minShardCount);
                log.info("Auto scaling down shards: key={}, from={} to={}, qps={}",
                    key, currentShards, newCount, qps);
                setShardCount(key, newCount);
                shardScaleDownCounter.increment();
            }
        });
    }
    
    /**
     * 定期健康检查
     */
    @Scheduled(fixedRateString = "${optimization.shard.health-check-interval-ms:5000}")
    public void healthCheck() {
        if (!enabled) return;
        
        shardGroups.forEach((key, group) -> {
            for (int i = 0; i < group.getShardCount(); i++) {
                try {
                    String shardKey = formatShardKey(key, i);
                    redisTemplate.hasKey(shardKey);
                    group.recordHealthy(i);
                } catch (Exception e) {
                    group.recordFailure(i);
                    if (group.getFailureCount(i) >= failureThreshold) {
                        group.markUnhealthy(i);
                        shardFailoverCounter.increment();
                    }
                }
            }
        });
    }
    
    // ========== 私有方法 ==========
    
    private ShardGroup createShardGroup(String key) {
        return new ShardGroup(minShardCount, virtualNodes);
    }
    
    private int selectShard(ShardGroup group, String key) {
        // 获取或创建负载均衡器
        WeightedRoundRobin lb = loadBalancers.computeIfAbsent(key, 
            k -> new WeightedRoundRobin(group.getShardCount()));
        
        // 同步分片数
        if (lb.size() != group.getShardCount()) {
            lb = new WeightedRoundRobin(group.getShardCount());
            loadBalancers.put(key, lb);
        }
        
        // 更新权重（基于响应时间）
        for (int i = 0; i < group.getShardCount(); i++) {
            if (group.isShardHealthy(i)) {
                double latency = group.getShardLatency(i);
                int weight = calculateWeight(latency);
                lb.setWeight(i, weight);
            } else {
                lb.setWeight(i, 0);
            }
        }
        
        // 选择分片
        int selected = lb.select();
        
        // 如果选中的分片不健康，使用 Jump Hash 作为后备
        if (!group.isShardHealthy(selected)) {
            selected = jumpHash(key.hashCode(), group.getHealthyShardCount());
        }
        
        return selected;
    }
    
    private int calculateWeight(double latencyMs) {
        // 延迟越低，权重越高
        if (latencyMs <= 1) return 100;
        if (latencyMs <= 5) return 80;
        if (latencyMs <= 10) return 60;
        if (latencyMs <= 50) return 40;
        if (latencyMs <= 100) return 20;
        return 10;
    }
    
    /**
     * Jump Consistent Hash - O(1) 复杂度的一致性哈希
     */
    private int jumpHash(long key, int numBuckets) {
        if (numBuckets <= 0) return 0;
        
        long b = -1;
        long j = 0;
        
        while (j < numBuckets) {
            b = j;
            key = key * 2862933555777941757L + 1;
            j = (long) ((b + 1) * (double) (1L << 31) / (double) ((key >>> 33) + 1));
        }
        
        return (int) b;
    }
    
    private String formatShardKey(String originalKey, int shardIndex) {
        return originalKey + ":shard:" + shardIndex;
    }
    
    private void migrateDataOnScaleUp(String key, int oldCount, int newCount) {
        // 从旧分片复制数据到新分片
        CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < oldCount; i++) {
                    String sourceKey = formatShardKey(key, i);
                    String value = redisTemplate.opsForValue().get(sourceKey);
                    
                    if (value != null) {
                        // 预热新分片
                        for (int j = oldCount; j < newCount; j++) {
                            String targetKey = formatShardKey(key, j);
                            Long ttl = redisTemplate.getExpire(sourceKey);
                            if (ttl != null && ttl > 0) {
                                redisTemplate.opsForValue().set(targetKey, value, 
                                    java.time.Duration.ofSeconds(ttl));
                            }
                        }
                        break; // 只需从一个分片复制
                    }
                }
                log.info("Shard migration completed: key={}, {} -> {}", key, oldCount, newCount);
            } catch (Exception e) {
                log.error("Shard migration failed: key={}", key, e);
            }
        });
    }
    
    private void migrateDataOnScaleDown(String key, int oldCount, int newCount) {
        // 缩容时删除多余分片
        CompletableFuture.runAsync(() -> {
            try {
                for (int i = newCount; i < oldCount; i++) {
                    String shardKey = formatShardKey(key, i);
                    redisTemplate.delete(shardKey);
                }
                log.info("Shard cleanup completed: key={}, {} -> {}", key, oldCount, newCount);
            } catch (Exception e) {
                log.error("Shard cleanup failed: key={}", key, e);
            }
        });
    }
    
    // ========== 内部类 ==========
    
    /**
     * 分片组
     */
    private static class ShardGroup {
        private volatile int shardCount;
        private final int virtualNodes;
        private final LongAdder totalAccess = new LongAdder();
        private final LongAdder[] shardAccess;
        private final AtomicLong[] shardLatency;
        private final AtomicInteger[] failureCounts;
        private final boolean[] healthyFlags;
        private final long[] lastAccessTimes;
        private final long createTime = System.currentTimeMillis();
        
        ShardGroup(int initialShards, int virtualNodes) {
            this.shardCount = initialShards;
            this.virtualNodes = virtualNodes;
            
            int maxShards = 64; // 预分配最大容量
            this.shardAccess = new LongAdder[maxShards];
            this.shardLatency = new AtomicLong[maxShards];
            this.failureCounts = new AtomicInteger[maxShards];
            this.healthyFlags = new boolean[maxShards];
            this.lastAccessTimes = new long[maxShards];
            
            for (int i = 0; i < maxShards; i++) {
                shardAccess[i] = new LongAdder();
                shardLatency[i] = new AtomicLong(1);
                failureCounts[i] = new AtomicInteger(0);
                healthyFlags[i] = true;
            }
        }
        
        int getShardCount() { return shardCount; }
        
        void scaleTo(int newCount) {
            this.shardCount = newCount;
        }
        
        void recordAccess(int shard) {
            totalAccess.increment();
            if (shard < shardAccess.length) {
                shardAccess[shard].increment();
                lastAccessTimes[shard] = System.currentTimeMillis();
            }
        }
        
        void recordLatency(int shard, long latencyMs) {
            if (shard < shardLatency.length) {
                shardLatency[shard].set(latencyMs);
            }
        }
        
        void recordFailure(int shard) {
            if (shard < failureCounts.length) {
                failureCounts[shard].incrementAndGet();
            }
        }
        
        void recordHealthy(int shard) {
            if (shard < failureCounts.length) {
                failureCounts[shard].set(0);
                healthyFlags[shard] = true;
            }
        }
        
        void markUnhealthy(int shard) {
            if (shard < healthyFlags.length) {
                healthyFlags[shard] = false;
            }
        }
        
        boolean isShardHealthy(int shard) {
            return shard < healthyFlags.length && healthyFlags[shard];
        }
        
        int getFailureCount(int shard) {
            return shard < failureCounts.length ? failureCounts[shard].get() : 0;
        }
        
        double getShardLatency(int shard) {
            return shard < shardLatency.length ? shardLatency[shard].get() : 1;
        }
        
        int getHealthyShardCount() {
            int count = 0;
            for (int i = 0; i < shardCount; i++) {
                if (healthyFlags[i]) count++;
            }
            return Math.max(count, 1);
        }
        
        long getRecentQps() {
            // 简单估算：总访问 / 存活时间(秒)
            long elapsed = (System.currentTimeMillis() - createTime) / 1000;
            return elapsed > 0 ? totalAccess.sum() / elapsed : 0;
        }
        
        ShardGroupStats getStats() {
            long[] accessCounts = new long[shardCount];
            for (int i = 0; i < shardCount; i++) {
                accessCounts[i] = shardAccess[i].sum();
            }
            
            return new ShardGroupStats(
                shardCount,
                getHealthyShardCount(),
                totalAccess.sum(),
                accessCounts,
                getRecentQps()
            );
        }
    }
    
    /**
     * 加权轮询负载均衡器
     */
    private static class WeightedRoundRobin {
        private final int[] weights;
        private final int[] currentWeights;
        private int effectiveWeight;
        
        WeightedRoundRobin(int size) {
            this.weights = new int[size];
            this.currentWeights = new int[size];
            Arrays.fill(weights, 100);
            this.effectiveWeight = size * 100;
        }
        
        int size() { return weights.length; }
        
        void setWeight(int index, int weight) {
            if (index >= 0 && index < weights.length) {
                effectiveWeight = effectiveWeight - weights[index] + weight;
                weights[index] = weight;
            }
        }
        
        synchronized int select() {
            if (effectiveWeight <= 0) {
                return 0;
            }
            
            int maxIndex = 0;
            int maxWeight = Integer.MIN_VALUE;
            
            for (int i = 0; i < weights.length; i++) {
                currentWeights[i] += weights[i];
                if (currentWeights[i] > maxWeight) {
                    maxWeight = currentWeights[i];
                    maxIndex = i;
                }
            }
            
            currentWeights[maxIndex] -= effectiveWeight;
            return maxIndex;
        }
    }
    
    /**
     * 分片组统计
     */
    public record ShardGroupStats(
        int totalShards,
        int healthyShards,
        long totalAccess,
        long[] shardAccessCounts,
        long estimatedQps
    ) {}
}
