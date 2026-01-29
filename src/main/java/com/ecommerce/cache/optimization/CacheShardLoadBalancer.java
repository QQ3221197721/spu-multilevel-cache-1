package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 缓存分片负载均衡器 - 智能分片路由与负载均衡
 * 
 * 核心特性:
 * 1. 一致性哈希 - 最小化重新分配
 * 2. 虚拟节点 - 均匀分布负载
 * 3. 权重感知 - 根据节点容量分配
 * 4. 健康检查 - 自动摘除故障节点
 * 5. 热点分散 - 自动复制热点数据
 * 6. 动态扩缩容 - 平滑迁移
 * 
 * 目标: 负载偏差 < 5%, 故障转移 < 100ms
 */
@Service
public class CacheShardLoadBalancer {
    
    private static final Logger log = LoggerFactory.getLogger(CacheShardLoadBalancer.class);
    
    // ========== 配置参数 ==========
    @Value("${optimization.shard.virtual-nodes:150}")
    private int virtualNodesPerShard;
    
    @Value("${optimization.shard.replication-factor:2}")
    private int replicationFactor;
    
    @Value("${optimization.shard.hot-key-threshold:1000}")
    private long hotKeyThreshold;
    
    @Value("${optimization.shard.health-check-interval-ms:5000}")
    private long healthCheckIntervalMs;
    
    @Value("${optimization.shard.migration-batch-size:100}")
    private int migrationBatchSize;
    
    // ========== 数据结构 ==========
    
    // 一致性哈希环
    private final TreeMap<Long, ShardNode> hashRing = new TreeMap<>();
    
    // 分片节点信息
    private final ConcurrentHashMap<String, ShardNode> shardNodes = new ConcurrentHashMap<>();
    
    // 虚拟节点映射
    private final ConcurrentHashMap<Long, String> virtualNodeMapping = new ConcurrentHashMap<>();
    
    // 热点key追踪
    private final ConcurrentHashMap<String, LongAdder> hotKeyCounters = new ConcurrentHashMap<>();
    
    // 热点key复制记录
    private final ConcurrentHashMap<String, Set<String>> hotKeyReplicas = new ConcurrentHashMap<>();
    
    // 分片负载统计
    private final ConcurrentHashMap<String, ShardLoadStats> shardLoadStats = new ConcurrentHashMap<>();
    
    // 迁移任务队列
    private final BlockingQueue<MigrationTask> migrationQueue = new LinkedBlockingQueue<>();
    
    // 执行器
    private final ExecutorService migrationExecutor;
    private final ScheduledExecutorService healthCheckExecutor;
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Timer routingTimer;
    private Counter routingCounter;
    private Counter failoverCounter;
    private Counter hotKeyReplicationCounter;
    
    // 读写锁
    private final java.util.concurrent.locks.ReadWriteLock ringLock = 
        new java.util.concurrent.locks.ReentrantReadWriteLock();
    
    public CacheShardLoadBalancer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.migrationExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "shard-migration");
            t.setDaemon(true);
            return t;
        });
        this.healthCheckExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "shard-health-check");
            t.setDaemon(true);
            return t;
        });
    }
    
    @PostConstruct
    public void init() {
        routingTimer = Timer.builder("cache.shard.routing.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        routingCounter = Counter.builder("cache.shard.routing.total").register(meterRegistry);
        failoverCounter = Counter.builder("cache.shard.failover").register(meterRegistry);
        hotKeyReplicationCounter = Counter.builder("cache.shard.hotkey.replications").register(meterRegistry);
        
        Gauge.builder("cache.shard.nodes.count", shardNodes, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("cache.shard.ring.size", hashRing, TreeMap::size)
            .register(meterRegistry);
        Gauge.builder("cache.shard.hotkeys.count", hotKeyCounters, ConcurrentHashMap::size)
            .register(meterRegistry);
        
        // 启动健康检查
        startHealthCheck();
        
        // 启动迁移处理器
        startMigrationProcessor();
        
        log.info("CacheShardLoadBalancer initialized: virtualNodes={}, replication={}",
            virtualNodesPerShard, replicationFactor);
    }
    
    /**
     * 添加分片节点
     */
    public void addShard(String shardId, String address, int weight) {
        ringLock.writeLock().lock();
        try {
            ShardNode node = new ShardNode(shardId, address, weight, true, System.currentTimeMillis());
            shardNodes.put(shardId, node);
            
            // 添加虚拟节点到哈希环
            int virtualNodes = virtualNodesPerShard * weight;
            for (int i = 0; i < virtualNodes; i++) {
                long hash = hash(shardId + "#" + i);
                hashRing.put(hash, node);
                virtualNodeMapping.put(hash, shardId);
            }
            
            // 初始化负载统计
            shardLoadStats.put(shardId, new ShardLoadStats());
            
            log.info("Added shard: id={}, address={}, weight={}, virtualNodes={}",
                shardId, address, weight, virtualNodes);
            
            // 触发数据再平衡
            triggerRebalance();
        } finally {
            ringLock.writeLock().unlock();
        }
    }
    
    /**
     * 移除分片节点
     */
    public void removeShard(String shardId) {
        ringLock.writeLock().lock();
        try {
            ShardNode node = shardNodes.remove(shardId);
            if (node == null) return;
            
            // 移除虚拟节点
            hashRing.entrySet().removeIf(e -> e.getValue().shardId().equals(shardId));
            virtualNodeMapping.entrySet().removeIf(e -> e.getValue().equals(shardId));
            shardLoadStats.remove(shardId);
            
            log.info("Removed shard: {}", shardId);
            
            // 触发数据迁移
            triggerRebalance();
        } finally {
            ringLock.writeLock().unlock();
        }
    }
    
    /**
     * 路由key到分片 - 核心方法
     */
    public RoutingResult route(String key) {
        return routingTimer.record(() -> {
            routingCounter.increment();
            
            // 记录访问频率
            recordKeyAccess(key);
            
            // 检查是否为热点key的副本
            Set<String> replicas = hotKeyReplicas.get(key);
            if (replicas != null && !replicas.isEmpty()) {
                // 负载均衡选择副本
                String selectedReplica = selectReplicaByLoad(replicas);
                if (selectedReplica != null) {
                    return new RoutingResult(selectedReplica, true, getBackupShards(key, selectedReplica));
                }
            }
            
            // 一致性哈希路由
            ringLock.readLock().lock();
            try {
                if (hashRing.isEmpty()) {
                    return RoutingResult.EMPTY;
                }
                
                long hash = hash(key);
                
                // 查找最近的节点
                Map.Entry<Long, ShardNode> entry = hashRing.ceilingEntry(hash);
                if (entry == null) {
                    entry = hashRing.firstEntry();
                }
                
                ShardNode primaryNode = entry.getValue();
                
                // 检查节点健康状态
                if (!primaryNode.healthy()) {
                    ShardNode backup = findHealthyBackup(hash, primaryNode.shardId());
                    if (backup != null) {
                        failoverCounter.increment();
                        return new RoutingResult(backup.shardId(), false, 
                            getBackupShards(key, backup.shardId()));
                    }
                    return RoutingResult.EMPTY;
                }
                
                // 更新负载统计
                updateShardLoad(primaryNode.shardId());
                
                return new RoutingResult(primaryNode.shardId(), false, 
                    getBackupShards(key, primaryNode.shardId()));
            } finally {
                ringLock.readLock().unlock();
            }
        });
    }
    
    /**
     * 批量路由
     */
    public Map<String, List<String>> batchRoute(Collection<String> keys) {
        Map<String, List<String>> result = new HashMap<>();
        
        for (String key : keys) {
            RoutingResult routing = route(key);
            if (routing.primaryShard() != null) {
                result.computeIfAbsent(routing.primaryShard(), k -> new ArrayList<>()).add(key);
            }
        }
        
        return result;
    }
    
    /**
     * 记录key访问
     */
    private void recordKeyAccess(String key) {
        LongAdder counter = hotKeyCounters.computeIfAbsent(key, k -> new LongAdder());
        counter.increment();
    }
    
    /**
     * 根据负载选择副本
     */
    private String selectReplicaByLoad(Set<String> replicas) {
        String minLoadShard = null;
        long minLoad = Long.MAX_VALUE;
        
        for (String shardId : replicas) {
            ShardNode node = shardNodes.get(shardId);
            if (node != null && node.healthy()) {
                ShardLoadStats stats = shardLoadStats.get(shardId);
                if (stats != null && stats.getRequestsPerSecond() < minLoad) {
                    minLoad = stats.getRequestsPerSecond();
                    minLoadShard = shardId;
                }
            }
        }
        
        return minLoadShard;
    }
    
    /**
     * 查找健康的备份节点
     */
    private ShardNode findHealthyBackup(long hash, String excludeShardId) {
        // 沿哈希环查找下一个健康节点
        NavigableMap<Long, ShardNode> tailMap = hashRing.tailMap(hash, false);
        
        for (ShardNode node : tailMap.values()) {
            if (!node.shardId().equals(excludeShardId) && node.healthy()) {
                return node;
            }
        }
        
        // 环绕查找
        for (ShardNode node : hashRing.values()) {
            if (!node.shardId().equals(excludeShardId) && node.healthy()) {
                return node;
            }
        }
        
        return null;
    }
    
    /**
     * 获取备份分片
     */
    private List<String> getBackupShards(String key, String primaryShard) {
        List<String> backups = new ArrayList<>();
        long hash = hash(key);
        
        ringLock.readLock().lock();
        try {
            NavigableMap<Long, ShardNode> tailMap = hashRing.tailMap(hash, false);
            Set<String> seen = new HashSet<>();
            seen.add(primaryShard);
            
            // 查找后续节点作为备份
            for (ShardNode node : tailMap.values()) {
                if (!seen.contains(node.shardId()) && node.healthy()) {
                    backups.add(node.shardId());
                    seen.add(node.shardId());
                    if (backups.size() >= replicationFactor - 1) break;
                }
            }
            
            // 环绕查找
            if (backups.size() < replicationFactor - 1) {
                for (ShardNode node : hashRing.values()) {
                    if (!seen.contains(node.shardId()) && node.healthy()) {
                        backups.add(node.shardId());
                        seen.add(node.shardId());
                        if (backups.size() >= replicationFactor - 1) break;
                    }
                }
            }
        } finally {
            ringLock.readLock().unlock();
        }
        
        return backups;
    }
    
    /**
     * 更新分片负载
     */
    private void updateShardLoad(String shardId) {
        ShardLoadStats stats = shardLoadStats.get(shardId);
        if (stats != null) {
            stats.recordRequest();
        }
    }
    
    /**
     * 启动健康检查
     */
    private void startHealthCheck() {
        healthCheckExecutor.scheduleAtFixedRate(() -> {
            try {
                checkShardHealth();
                detectHotKeys();
            } catch (Exception e) {
                log.error("Health check error", e);
            }
        }, healthCheckIntervalMs, healthCheckIntervalMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 检查分片健康状态
     */
    private void checkShardHealth() {
        shardNodes.forEach((shardId, node) -> {
            boolean isHealthy = performHealthCheck(node);
            
            if (isHealthy != node.healthy()) {
                // 状态变化
                ShardNode updatedNode = new ShardNode(
                    node.shardId(), node.address(), node.weight(), 
                    isHealthy, System.currentTimeMillis()
                );
                shardNodes.put(shardId, updatedNode);
                
                // 更新哈希环中的节点
                ringLock.writeLock().lock();
                try {
                    hashRing.replaceAll((k, v) -> 
                        v.shardId().equals(shardId) ? updatedNode : v);
                } finally {
                    ringLock.writeLock().unlock();
                }
                
                log.info("Shard {} health changed: {} -> {}", shardId, node.healthy(), isHealthy);
            }
        });
    }
    
    /**
     * 执行健康检查
     */
    private boolean performHealthCheck(ShardNode node) {
        // 实际实现中应该ping节点
        // 这里简化为检查最后更新时间
        return System.currentTimeMillis() - node.lastSeen() < healthCheckIntervalMs * 3;
    }
    
    /**
     * 检测并处理热点key
     */
    private void detectHotKeys() {
        List<String> newHotKeys = new ArrayList<>();
        
        hotKeyCounters.forEach((key, counter) -> {
            long count = counter.sum();
            if (count > hotKeyThreshold && !hotKeyReplicas.containsKey(key)) {
                newHotKeys.add(key);
            }
        });
        
        // 为新热点key创建副本
        for (String hotKey : newHotKeys) {
            createHotKeyReplicas(hotKey);
        }
        
        // 衰减计数器
        hotKeyCounters.forEach((key, counter) -> {
            long current = counter.sum();
            counter.reset();
            counter.add((long) (current * 0.5)); // 衰减50%
        });
        
        // 清理低访问量的key
        hotKeyCounters.entrySet().removeIf(e -> e.getValue().sum() < 10);
    }
    
    /**
     * 为热点key创建副本
     */
    private void createHotKeyReplicas(String key) {
        RoutingResult routing = route(key);
        if (routing.primaryShard() == null) return;
        
        Set<String> replicas = new HashSet<>();
        replicas.add(routing.primaryShard());
        
        // 选择额外的副本节点
        List<String> candidates = new ArrayList<>(shardNodes.keySet());
        candidates.remove(routing.primaryShard());
        Collections.shuffle(candidates);
        
        int replicaCount = Math.min(3, candidates.size());
        for (int i = 0; i < replicaCount; i++) {
            replicas.add(candidates.get(i));
        }
        
        hotKeyReplicas.put(key, replicas);
        hotKeyReplicationCounter.increment();
        
        log.info("Created {} replicas for hot key: {}", replicas.size(), key);
    }
    
    /**
     * 触发再平衡
     */
    private void triggerRebalance() {
        // 计算每个分片应该持有的key范围
        log.info("Triggering data rebalance...");
        // 实际实现中应该进行数据迁移
    }
    
    /**
     * 启动迁移处理器
     */
    private void startMigrationProcessor() {
        migrationExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    MigrationTask task = migrationQueue.poll(1, TimeUnit.SECONDS);
                    if (task != null) {
                        processMigration(task);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Migration error", e);
                }
            }
        });
    }
    
    /**
     * 处理迁移任务
     */
    private void processMigration(MigrationTask task) {
        log.info("Processing migration: {} keys from {} to {}", 
            task.keys().size(), task.sourceShard(), task.targetShard());
        // 实际实现中应该复制数据
    }
    
    /**
     * 计算哈希值
     */
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            
            // 使用前8字节作为哈希值
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return hash;
        } catch (Exception e) {
            return key.hashCode();
        }
    }
    
    /**
     * 获取负载均衡统计
     */
    public LoadBalancerStats getStats() {
        Map<String, ShardStats> shardStatsMap = new HashMap<>();
        
        shardNodes.forEach((shardId, node) -> {
            ShardLoadStats loadStats = shardLoadStats.get(shardId);
            long requestsPerSecond = loadStats != null ? loadStats.getRequestsPerSecond() : 0;
            
            shardStatsMap.put(shardId, new ShardStats(
                node.address(),
                node.weight(),
                node.healthy(),
                requestsPerSecond,
                getShardKeyCount(shardId)
            ));
        });
        
        return new LoadBalancerStats(
            shardNodes.size(),
            hashRing.size(),
            hotKeyCounters.size(),
            hotKeyReplicas.size(),
            shardStatsMap,
            calculateLoadImbalance()
        );
    }
    
    /**
     * 获取分片key数量
     */
    private long getShardKeyCount(String shardId) {
        // 简化实现
        return virtualNodeMapping.values().stream()
            .filter(s -> s.equals(shardId))
            .count();
    }
    
    /**
     * 计算负载不均衡度
     */
    private double calculateLoadImbalance() {
        if (shardNodes.isEmpty()) return 0;
        
        List<Long> loads = new ArrayList<>();
        shardLoadStats.forEach((shardId, stats) -> loads.add(stats.getRequestsPerSecond()));
        
        if (loads.isEmpty()) return 0;
        
        double avg = loads.stream().mapToLong(l -> l).average().orElse(0);
        if (avg == 0) return 0;
        
        double maxDeviation = loads.stream()
            .mapToDouble(l -> Math.abs(l - avg) / avg)
            .max().orElse(0);
        
        return maxDeviation;
    }
    
    // ========== 内部类 ==========
    
    public record ShardNode(
        String shardId,
        String address,
        int weight,
        boolean healthy,
        long lastSeen
    ) {}
    
    public record RoutingResult(
        String primaryShard,
        boolean fromReplica,
        List<String> backupShards
    ) {
        static final RoutingResult EMPTY = new RoutingResult(null, false, Collections.emptyList());
    }
    
    public record MigrationTask(
        String sourceShard,
        String targetShard,
        List<String> keys
    ) {}
    
    private static class ShardLoadStats {
        private final LongAdder requestCount = new LongAdder();
        private volatile long lastResetTime = System.currentTimeMillis();
        private volatile long lastRequestCount = 0;
        
        void recordRequest() {
            requestCount.increment();
        }
        
        long getRequestsPerSecond() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastResetTime;
            
            if (elapsed >= 1000) {
                long count = requestCount.sum();
                long rps = (count - lastRequestCount) * 1000 / elapsed;
                lastRequestCount = count;
                lastResetTime = now;
                return rps;
            }
            
            return (requestCount.sum() - lastRequestCount) * 1000 / Math.max(elapsed, 1);
        }
    }
    
    public record ShardStats(
        String address,
        int weight,
        boolean healthy,
        long requestsPerSecond,
        long keyCount
    ) {}
    
    public record LoadBalancerStats(
        int shardCount,
        int virtualNodeCount,
        int hotKeyCount,
        int replicatedKeyCount,
        Map<String, ShardStats> shardStats,
        double loadImbalance
    ) {}
}
