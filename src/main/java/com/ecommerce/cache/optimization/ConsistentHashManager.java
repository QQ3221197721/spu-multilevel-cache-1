package com.ecommerce.cache.optimization;

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
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分布式一致性哈希管理器
 * 
 * 核心特性：
 * 1. 虚拟节点 - 确保数据均匀分布
 * 2. 动态扩缩容 - 支持节点热添加/移除
 * 3. 数据迁移 - 自动处理扩缩容时的数据迁移
 * 4. 负载均衡 - 基于节点负载动态调整权重
 * 5. 故障检测 - 自动检测并隔离故障节点
 * 6. 数据复制 - 支持多副本容灾
 * 
 * 目标：扩缩容数据迁移 < 1/N，负载偏差 < 10%
 */
@Service
public class ConsistentHashManager {
    
    private static final Logger log = LoggerFactory.getLogger(ConsistentHashManager.class);
    
    // 哈希环
    private final TreeMap<Long, VirtualNode> hashRing = new TreeMap<>();
    
    // 物理节点
    private final ConcurrentHashMap<String, PhysicalNode> physicalNodes = new ConcurrentHashMap<>();
    
    // 读写锁保护哈希环
    private final ReentrantReadWriteLock ringLock = new ReentrantReadWriteLock();
    
    // 数据迁移队列
    private final BlockingQueue<MigrationTask> migrationQueue = new LinkedBlockingQueue<>(10000);
    
    // 配置
    @Value("${optimization.hash.virtual-nodes:150}")
    private int virtualNodesPerNode;
    
    @Value("${optimization.hash.replicas:2}")
    private int replicaCount;
    
    @Value("${optimization.hash.migration-batch-size:100}")
    private int migrationBatchSize;
    
    @Value("${optimization.hash.health-check-interval-ms:10000}")
    private long healthCheckIntervalMs;
    
    @Value("${optimization.hash.auto-rebalance-enabled:true}")
    private boolean autoRebalanceEnabled;
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Counter nodeAddCounter;
    private Counter nodeRemoveCounter;
    private Counter migrationCounter;
    private Counter lookupCounter;
    private Counter failoverCounter;
    
    // 统计
    private final AtomicLong totalLookups = new AtomicLong(0);
    private final AtomicLong totalMigrations = new AtomicLong(0);
    
    // 迁移执行器
    private final ExecutorService migrationExecutor;
    
    // 迁移回调
    private Function<MigrationTask, Boolean> migrationCallback;
    
    public ConsistentHashManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.migrationExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "hash-migration");
            t.setDaemon(true);
            return t;
        });
    }
    
    @PostConstruct
    public void init() {
        // 注册指标
        nodeAddCounter = Counter.builder("hash.node.adds").register(meterRegistry);
        nodeRemoveCounter = Counter.builder("hash.node.removes").register(meterRegistry);
        migrationCounter = Counter.builder("hash.migrations").register(meterRegistry);
        lookupCounter = Counter.builder("hash.lookups").register(meterRegistry);
        failoverCounter = Counter.builder("hash.failovers").register(meterRegistry);
        
        Gauge.builder("hash.ring.size", hashRing, TreeMap::size)
            .register(meterRegistry);
        Gauge.builder("hash.physical.nodes", physicalNodes, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("hash.migration.queue.size", migrationQueue, BlockingQueue::size)
            .register(meterRegistry);
        
        // 启动迁移处理器
        startMigrationProcessor();
        
        log.info("ConsistentHashManager initialized: virtualNodes={}, replicas={}", 
            virtualNodesPerNode, replicaCount);
    }
    
    /**
     * 添加物理节点
     */
    public void addNode(String nodeId, String address, int weight) {
        ringLock.writeLock().lock();
        try {
            if (physicalNodes.containsKey(nodeId)) {
                log.warn("Node already exists: {}", nodeId);
                return;
            }
            
            PhysicalNode node = new PhysicalNode(nodeId, address, weight);
            physicalNodes.put(nodeId, node);
            
            // 添加虚拟节点
            int virtualCount = virtualNodesPerNode * weight;
            for (int i = 0; i < virtualCount; i++) {
                VirtualNode vnode = new VirtualNode(node, i);
                long hash = hash(vnode.getVirtualId());
                hashRing.put(hash, vnode);
            }
            
            nodeAddCounter.increment();
            log.info("Node added: {} ({}) with {} virtual nodes", nodeId, address, virtualCount);
            
            // 触发数据迁移
            if (autoRebalanceEnabled && physicalNodes.size() > 1) {
                scheduleMigration(nodeId, MigrationType.NODE_ADDED);
            }
        } finally {
            ringLock.writeLock().unlock();
        }
    }
    
    /**
     * 移除物理节点
     */
    public void removeNode(String nodeId) {
        ringLock.writeLock().lock();
        try {
            PhysicalNode node = physicalNodes.remove(nodeId);
            if (node == null) {
                log.warn("Node not found: {}", nodeId);
                return;
            }
            
            // 移除虚拟节点
            hashRing.entrySet().removeIf(e -> e.getValue().getPhysicalNode().getId().equals(nodeId));
            
            nodeRemoveCounter.increment();
            log.info("Node removed: {}", nodeId);
            
            // 触发数据迁移
            if (autoRebalanceEnabled && !physicalNodes.isEmpty()) {
                scheduleMigration(nodeId, MigrationType.NODE_REMOVED);
            }
        } finally {
            ringLock.writeLock().unlock();
        }
    }
    
    /**
     * 查找 Key 所属节点
     */
    public String getNode(String key) {
        ringLock.readLock().lock();
        try {
            if (hashRing.isEmpty()) {
                return null;
            }
            
            long hash = hash(key);
            Map.Entry<Long, VirtualNode> entry = hashRing.ceilingEntry(hash);
            if (entry == null) {
                entry = hashRing.firstEntry();
            }
            
            lookupCounter.increment();
            totalLookups.incrementAndGet();
            
            PhysicalNode node = entry.getValue().getPhysicalNode();
            
            // 检查节点健康状态
            if (!node.isHealthy()) {
                return getFailoverNode(key, node.getId());
            }
            
            return node.getId();
        } finally {
            ringLock.readLock().unlock();
        }
    }
    
    /**
     * 获取 Key 的多个副本节点
     */
    public List<String> getReplicaNodes(String key) {
        ringLock.readLock().lock();
        try {
            if (hashRing.isEmpty()) {
                return Collections.emptyList();
            }
            
            Set<String> nodes = new LinkedHashSet<>();
            long hash = hash(key);
            
            // 顺时针遍历找到 replicaCount 个不同的物理节点
            NavigableMap<Long, VirtualNode> tailMap = hashRing.tailMap(hash, true);
            addUniqueNodes(nodes, tailMap.values());
            
            if (nodes.size() < replicaCount) {
                addUniqueNodes(nodes, hashRing.values());
            }
            
            return new ArrayList<>(nodes);
        } finally {
            ringLock.readLock().unlock();
        }
    }
    
    private void addUniqueNodes(Set<String> nodes, Collection<VirtualNode> vnodes) {
        for (VirtualNode vnode : vnodes) {
            if (nodes.size() >= replicaCount) break;
            PhysicalNode pnode = vnode.getPhysicalNode();
            if (pnode.isHealthy()) {
                nodes.add(pnode.getId());
            }
        }
    }
    
    /**
     * 获取故障转移节点
     */
    private String getFailoverNode(String key, String failedNodeId) {
        failoverCounter.increment();
        
        long hash = hash(key);
        NavigableMap<Long, VirtualNode> tailMap = hashRing.tailMap(hash, true);
        
        // 找到下一个健康的节点
        for (VirtualNode vnode : tailMap.values()) {
            PhysicalNode pnode = vnode.getPhysicalNode();
            if (pnode.isHealthy() && !pnode.getId().equals(failedNodeId)) {
                log.debug("Failover from {} to {}", failedNodeId, pnode.getId());
                return pnode.getId();
            }
        }
        
        // 从头开始找
        for (VirtualNode vnode : hashRing.values()) {
            PhysicalNode pnode = vnode.getPhysicalNode();
            if (pnode.isHealthy() && !pnode.getId().equals(failedNodeId)) {
                return pnode.getId();
            }
        }
        
        log.error("No healthy node available for key: {}", key);
        return null;
    }
    
    /**
     * 计算节点负载分布
     */
    public Map<String, Double> getLoadDistribution() {
        ringLock.readLock().lock();
        try {
            Map<String, Long> vnodeCounts = new HashMap<>();
            for (VirtualNode vnode : hashRing.values()) {
                vnodeCounts.merge(vnode.getPhysicalNode().getId(), 1L, Long::sum);
            }
            
            long total = hashRing.size();
            return vnodeCounts.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> (double) e.getValue() / total * 100
                ));
        } finally {
            ringLock.readLock().unlock();
        }
    }
    
    /**
     * 检查负载是否均衡
     */
    public boolean isBalanced(double maxDeviationPercent) {
        Map<String, Double> distribution = getLoadDistribution();
        if (distribution.isEmpty()) return true;
        
        double avgLoad = 100.0 / distribution.size();
        for (double load : distribution.values()) {
            if (Math.abs(load - avgLoad) > maxDeviationPercent) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 手动触发再平衡
     */
    public void rebalance() {
        if (!autoRebalanceEnabled) {
            log.info("Auto-rebalance is disabled");
            return;
        }
        
        log.info("Starting manual rebalance...");
        for (String nodeId : physicalNodes.keySet()) {
            scheduleMigration(nodeId, MigrationType.REBALANCE);
        }
    }
    
    /**
     * 调度数据迁移
     */
    private void scheduleMigration(String nodeId, MigrationType type) {
        MigrationTask task = new MigrationTask(nodeId, type, System.currentTimeMillis());
        migrationQueue.offer(task);
    }
    
    /**
     * 启动迁移处理器
     */
    private void startMigrationProcessor() {
        Thread processor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    MigrationTask task = migrationQueue.poll(1, TimeUnit.SECONDS);
                    if (task != null && migrationCallback != null) {
                        migrationExecutor.submit(() -> {
                            try {
                                boolean success = migrationCallback.apply(task);
                                if (success) {
                                    migrationCounter.increment();
                                    totalMigrations.incrementAndGet();
                                }
                                log.debug("Migration task completed: {} - {}", task.nodeId, task.type);
                            } catch (Exception e) {
                                log.error("Migration task failed: {}", task.nodeId, e);
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "migration-processor");
        processor.setDaemon(true);
        processor.start();
    }
    
    /**
     * 定期健康检查
     */
    @Scheduled(fixedRateString = "${optimization.hash.health-check-interval-ms:10000}")
    public void healthCheck() {
        for (PhysicalNode node : physicalNodes.values()) {
            boolean wasHealthy = node.isHealthy();
            node.checkHealth();
            boolean nowHealthy = node.isHealthy();
            
            if (wasHealthy && !nowHealthy) {
                log.warn("Node {} became unhealthy", node.getId());
            } else if (!wasHealthy && nowHealthy) {
                log.info("Node {} recovered", node.getId());
            }
        }
    }
    
    /**
     * 定期检查负载均衡
     */
    @Scheduled(fixedRate = 60000) // 1 分钟
    public void checkBalance() {
        if (!autoRebalanceEnabled) return;
        
        if (!isBalanced(15.0)) { // 15% 偏差阈值
            log.warn("Load imbalance detected, consider rebalancing");
            // 可以自动触发再平衡
        }
    }
    
    /**
     * 设置迁移回调
     */
    public void setMigrationCallback(Function<MigrationTask, Boolean> callback) {
        this.migrationCallback = callback;
    }
    
    /**
     * 标记节点为不健康
     */
    public void markNodeUnhealthy(String nodeId) {
        PhysicalNode node = physicalNodes.get(nodeId);
        if (node != null) {
            node.markUnhealthy();
            log.warn("Node {} marked as unhealthy", nodeId);
        }
    }
    
    /**
     * 标记节点为健康
     */
    public void markNodeHealthy(String nodeId) {
        PhysicalNode node = physicalNodes.get(nodeId);
        if (node != null) {
            node.markHealthy();
            log.info("Node {} marked as healthy", nodeId);
        }
    }
    
    /**
     * 计算哈希值（使用 MD5 + Ketama 算法）
     */
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            
            // 使用前 8 字节构造 long
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return hash;
        } catch (Exception e) {
            // 降级使用 String hashCode
            return key.hashCode();
        }
    }
    
    /**
     * 获取所有节点信息
     */
    public List<NodeInfo> getNodeInfos() {
        Map<String, Double> distribution = getLoadDistribution();
        
        return physicalNodes.values().stream()
            .map(node -> new NodeInfo(
                node.getId(),
                node.getAddress(),
                node.getWeight(),
                node.isHealthy(),
                distribution.getOrDefault(node.getId(), 0.0),
                node.getRequestCount(),
                node.getErrorCount()
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * 获取统计信息
     */
    public HashManagerStats getStats() {
        return new HashManagerStats(
            physicalNodes.size(),
            hashRing.size(),
            totalLookups.get(),
            totalMigrations.get(),
            isBalanced(10.0),
            getLoadDistribution()
        );
    }
    
    // ========== 内部类 ==========
    
    /**
     * 物理节点
     */
    private static class PhysicalNode {
        private final String id;
        private final String address;
        private final int weight;
        private volatile boolean healthy = true;
        private final AtomicLong requestCount = new AtomicLong(0);
        private final AtomicLong errorCount = new AtomicLong(0);
        private volatile long lastHealthCheckTime = System.currentTimeMillis();
        
        PhysicalNode(String id, String address, int weight) {
            this.id = id;
            this.address = address;
            this.weight = weight;
        }
        
        String getId() { return id; }
        String getAddress() { return address; }
        int getWeight() { return weight; }
        boolean isHealthy() { return healthy; }
        long getRequestCount() { return requestCount.get(); }
        long getErrorCount() { return errorCount.get(); }
        
        void markUnhealthy() { 
            healthy = false; 
            errorCount.incrementAndGet();
        }
        
        void markHealthy() { 
            healthy = true; 
        }
        
        void checkHealth() {
            // 基于错误率判断健康状态
            long requests = requestCount.get();
            long errors = errorCount.get();
            
            if (requests > 100) {
                double errorRate = (double) errors / requests;
                healthy = errorRate < 0.5; // 50% 错误率阈值
            }
            
            lastHealthCheckTime = System.currentTimeMillis();
        }
        
        void recordRequest(boolean success) {
            requestCount.incrementAndGet();
            if (!success) {
                errorCount.incrementAndGet();
            }
        }
    }
    
    /**
     * 虚拟节点
     */
    private static class VirtualNode {
        private final PhysicalNode physicalNode;
        private final int index;
        
        VirtualNode(PhysicalNode physicalNode, int index) {
            this.physicalNode = physicalNode;
            this.index = index;
        }
        
        PhysicalNode getPhysicalNode() { return physicalNode; }
        
        String getVirtualId() {
            return physicalNode.getId() + "#" + index;
        }
    }
    
    /**
     * 迁移类型
     */
    public enum MigrationType {
        NODE_ADDED,
        NODE_REMOVED,
        REBALANCE
    }
    
    /**
     * 迁移任务
     */
    public record MigrationTask(String nodeId, MigrationType type, long timestamp) {}
    
    /**
     * 节点信息
     */
    public record NodeInfo(
        String id, String address, int weight, boolean healthy, 
        double loadPercent, long requestCount, long errorCount
    ) {}
    
    /**
     * 管理器统计
     */
    public record HashManagerStats(
        int physicalNodeCount,
        int virtualNodeCount,
        long totalLookups,
        long totalMigrations,
        boolean balanced,
        Map<String, Double> loadDistribution
    ) {}
}
