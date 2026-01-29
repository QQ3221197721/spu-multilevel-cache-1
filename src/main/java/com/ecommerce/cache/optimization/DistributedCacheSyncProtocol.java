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
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 分布式缓存同步协议 - 跨节点缓存一致性保证
 * 
 * 核心特性:
 * 1. 版本向量 (Vector Clock) - 精确追踪数据因果关系
 * 2. 最终一致性 - 异步复制提高可用性
 * 3. 冲突检测与解决 - Last-Writer-Wins + 自定义合并策略
 * 4. 反熵协议 (Anti-Entropy) - 定期同步修复不一致
 * 5. Gossip协议 - 高效传播更新信息
 * 6. 增量同步 - 只传输差异数据
 * 
 * 目标: 跨节点同步延迟 < 100ms, 一致性收敛时间 < 1s
 */
@Service
public class DistributedCacheSyncProtocol {
    
    private static final Logger log = LoggerFactory.getLogger(DistributedCacheSyncProtocol.class);
    
    // ========== 配置参数 ==========
    @Value("${optimization.sync.enabled:true}")
    private boolean syncEnabled;
    
    @Value("${optimization.sync.node-id:#{T(java.util.UUID).randomUUID().toString().substring(0,8)}}")
    private String nodeId;
    
    @Value("${optimization.sync.gossip-interval-ms:1000}")
    private long gossipIntervalMs;
    
    @Value("${optimization.sync.anti-entropy-interval-ms:30000}")
    private long antiEntropyIntervalMs;
    
    @Value("${optimization.sync.max-pending-updates:10000}")
    private int maxPendingUpdates;
    
    @Value("${optimization.sync.conflict-resolution:LAST_WRITER_WINS}")
    private ConflictResolution conflictResolution;
    
    // ========== 数据结构 ==========
    
    // 本地版本向量
    private final ConcurrentHashMap<String, Long> localVectorClock = new ConcurrentHashMap<>();
    
    // 远程节点版本向量
    private final ConcurrentHashMap<String, Map<String, Long>> remoteVectorClocks = new ConcurrentHashMap<>();
    
    // 缓存数据版本
    private final ConcurrentHashMap<String, VersionedEntry> versionedData = new ConcurrentHashMap<>();
    
    // 待同步更新队列
    private final BlockingQueue<SyncUpdate> pendingUpdates = new LinkedBlockingQueue<>();
    
    // 同步批次缓冲
    private final ConcurrentHashMap<String, List<SyncUpdate>> syncBatches = new ConcurrentHashMap<>();
    
    // 已知节点集合
    private final ConcurrentHashMap<String, NodeInfo> knownNodes = new ConcurrentHashMap<>();
    
    // 同步执行器
    private final ExecutorService syncExecutor;
    private final ScheduledExecutorService scheduledExecutor;
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Counter syncSentCounter;
    private Counter syncReceivedCounter;
    private Counter conflictCounter;
    private Counter antiEntropyCounter;
    
    // 统计
    private final LongAdder totalSyncs = new LongAdder();
    private final LongAdder totalConflicts = new LongAdder();
    private final AtomicLong lastSyncTime = new AtomicLong(0);
    
    public DistributedCacheSyncProtocol(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.syncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "cache-sync-scheduler");
            t.setDaemon(true);
            return t;
        });
    }
    
    @PostConstruct
    public void init() {
        // 初始化本节点向量时钟
        localVectorClock.put(nodeId, 0L);
        
        // 注册指标
        syncSentCounter = Counter.builder("cache.sync.sent").register(meterRegistry);
        syncReceivedCounter = Counter.builder("cache.sync.received").register(meterRegistry);
        conflictCounter = Counter.builder("cache.sync.conflicts").register(meterRegistry);
        antiEntropyCounter = Counter.builder("cache.sync.anti.entropy").register(meterRegistry);
        
        Gauge.builder("cache.sync.pending.updates", pendingUpdates, BlockingQueue::size)
            .register(meterRegistry);
        Gauge.builder("cache.sync.known.nodes", knownNodes, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("cache.sync.versioned.entries", versionedData, ConcurrentHashMap::size)
            .register(meterRegistry);
        
        // 启动Gossip处理器
        startGossipProcessor();
        
        // 启动反熵协议
        startAntiEntropyProtocol();
        
        log.info("DistributedCacheSyncProtocol initialized: nodeId={}, gossipInterval={}ms",
            nodeId, gossipIntervalMs);
    }
    
    @PreDestroy
    public void shutdown() {
        syncExecutor.shutdown();
        scheduledExecutor.shutdown();
    }
    
    /**
     * 记录本地更新并触发同步
     */
    public void recordUpdate(String key, String value, long ttlSeconds) {
        if (!syncEnabled) return;
        
        // 递增本地时钟
        long newVersion = localVectorClock.compute(nodeId, (k, v) -> v == null ? 1 : v + 1);
        
        // 创建版本化条目
        Map<String, Long> vectorClock = new HashMap<>(localVectorClock);
        VersionedEntry entry = new VersionedEntry(
            key, value, vectorClock, nodeId, System.currentTimeMillis(), ttlSeconds
        );
        
        // 更新本地数据
        VersionedEntry existing = versionedData.get(key);
        if (existing == null || shouldAcceptUpdate(existing, entry)) {
            versionedData.put(key, entry);
            
            // 加入待同步队列
            SyncUpdate update = new SyncUpdate(
                UpdateType.PUT, key, value, vectorClock, nodeId, ttlSeconds
            );
            enqueueSyncUpdate(update);
        }
    }
    
    /**
     * 记录本地删除并触发同步
     */
    public void recordDelete(String key) {
        if (!syncEnabled) return;
        
        long newVersion = localVectorClock.compute(nodeId, (k, v) -> v == null ? 1 : v + 1);
        
        Map<String, Long> vectorClock = new HashMap<>(localVectorClock);
        
        // 标记为删除（墓碑）
        VersionedEntry tombstone = new VersionedEntry(
            key, null, vectorClock, nodeId, System.currentTimeMillis(), 0
        );
        versionedData.put(key, tombstone);
        
        SyncUpdate update = new SyncUpdate(
            UpdateType.DELETE, key, null, vectorClock, nodeId, 0
        );
        enqueueSyncUpdate(update);
    }
    
    /**
     * 接收远程同步更新
     */
    public void receiveSyncUpdate(SyncUpdate update) {
        if (!syncEnabled) return;
        
        syncReceivedCounter.increment();
        totalSyncs.increment();
        
        // 更新远程节点的向量时钟
        remoteVectorClocks.put(update.sourceNode(), new HashMap<>(update.vectorClock()));
        
        // 合并向量时钟
        mergeVectorClock(update.vectorClock());
        
        // 获取现有条目
        VersionedEntry existing = versionedData.get(update.key());
        
        // 创建新条目
        VersionedEntry newEntry = new VersionedEntry(
            update.key(), update.value(), update.vectorClock(),
            update.sourceNode(), System.currentTimeMillis(), update.ttlSeconds()
        );
        
        // 检查是否应该接受更新
        if (existing == null) {
            versionedData.put(update.key(), newEntry);
            applyUpdate(update);
        } else if (shouldAcceptUpdate(existing, newEntry)) {
            versionedData.put(update.key(), newEntry);
            applyUpdate(update);
        } else {
            // 检测到冲突
            handleConflict(existing, newEntry);
        }
    }
    
    /**
     * 批量接收同步更新
     */
    public void receiveSyncBatch(List<SyncUpdate> updates) {
        updates.forEach(this::receiveSyncUpdate);
    }
    
    /**
     * 判断是否应该接受更新（向量时钟比较）
     */
    private boolean shouldAcceptUpdate(VersionedEntry existing, VersionedEntry incoming) {
        Map<String, Long> existingClock = existing.vectorClock();
        Map<String, Long> incomingClock = incoming.vectorClock();
        
        // 检查因果关系
        VectorClockRelation relation = compareVectorClocks(existingClock, incomingClock);
        
        switch (relation) {
            case BEFORE:
                // 现有版本在前，接受新版本
                return true;
            case AFTER:
                // 现有版本更新，拒绝
                return false;
            case CONCURRENT:
                // 并发冲突，使用冲突解决策略
                return resolveConflict(existing, incoming);
            case EQUAL:
                // 相同版本，忽略
                return false;
            default:
                return false;
        }
    }
    
    /**
     * 比较向量时钟
     */
    private VectorClockRelation compareVectorClocks(Map<String, Long> clock1, Map<String, Long> clock2) {
        Set<String> allNodes = new HashSet<>();
        allNodes.addAll(clock1.keySet());
        allNodes.addAll(clock2.keySet());
        
        boolean clock1Greater = false;
        boolean clock2Greater = false;
        
        for (String node : allNodes) {
            long v1 = clock1.getOrDefault(node, 0L);
            long v2 = clock2.getOrDefault(node, 0L);
            
            if (v1 > v2) clock1Greater = true;
            if (v2 > v1) clock2Greater = true;
        }
        
        if (!clock1Greater && !clock2Greater) return VectorClockRelation.EQUAL;
        if (clock1Greater && !clock2Greater) return VectorClockRelation.AFTER;
        if (!clock1Greater && clock2Greater) return VectorClockRelation.BEFORE;
        return VectorClockRelation.CONCURRENT;
    }
    
    /**
     * 解决并发冲突
     */
    private boolean resolveConflict(VersionedEntry existing, VersionedEntry incoming) {
        conflictCounter.increment();
        totalConflicts.increment();
        
        switch (conflictResolution) {
            case LAST_WRITER_WINS:
                // 时间戳较新的获胜
                return incoming.timestamp() > existing.timestamp();
                
            case FIRST_WRITER_WINS:
                // 时间戳较早的获胜
                return incoming.timestamp() < existing.timestamp();
                
            case HIGHER_NODE_WINS:
                // 节点ID字典序较大的获胜
                return incoming.sourceNode().compareTo(existing.sourceNode()) > 0;
                
            case MERGE:
                // 合并值（需要自定义实现）
                return handleMergeConflict(existing, incoming);
                
            default:
                return incoming.timestamp() > existing.timestamp();
        }
    }
    
    /**
     * 处理冲突
     */
    private void handleConflict(VersionedEntry existing, VersionedEntry incoming) {
        log.debug("Conflict detected: key={}, existing={}, incoming={}",
            existing.key(), existing.sourceNode(), incoming.sourceNode());
        
        // 记录冲突事件
        // 可以在这里发送冲突通知或记录到冲突日志
    }
    
    /**
     * 合并冲突值
     */
    private boolean handleMergeConflict(VersionedEntry existing, VersionedEntry incoming) {
        // 默认实现：时间戳较新的获胜
        // 子类可以覆盖此方法实现自定义合并逻辑
        return incoming.timestamp() > existing.timestamp();
    }
    
    /**
     * 合并向量时钟
     */
    private void mergeVectorClock(Map<String, Long> remoteClock) {
        remoteClock.forEach((node, version) -> {
            localVectorClock.compute(node, (k, v) -> 
                v == null ? version : Math.max(v, version));
        });
    }
    
    /**
     * 应用更新到本地缓存
     */
    private void applyUpdate(SyncUpdate update) {
        // 这里应该调用实际的缓存服务
        // 由外部监听器实现
        log.debug("Applying sync update: key={}, type={}", update.key(), update.type());
    }
    
    /**
     * 加入同步队列
     */
    private void enqueueSyncUpdate(SyncUpdate update) {
        if (pendingUpdates.size() >= maxPendingUpdates) {
            log.warn("Sync queue full, dropping oldest updates");
            pendingUpdates.poll();
        }
        pendingUpdates.offer(update);
        syncSentCounter.increment();
    }
    
    /**
     * 启动Gossip处理器
     */
    private void startGossipProcessor() {
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                processGossip();
            } catch (Exception e) {
                log.error("Gossip processing error", e);
            }
        }, gossipIntervalMs, gossipIntervalMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 处理Gossip传播
     */
    private void processGossip() {
        if (pendingUpdates.isEmpty()) return;
        
        // 收集待发送的更新
        List<SyncUpdate> updates = new ArrayList<>();
        pendingUpdates.drainTo(updates, 100);
        
        if (updates.isEmpty()) return;
        
        // 随机选择几个节点传播
        List<String> targetNodes = selectGossipTargets(3);
        
        for (String targetNode : targetNodes) {
            syncExecutor.submit(() -> {
                try {
                    sendUpdatesToNode(targetNode, updates);
                } catch (Exception e) {
                    log.warn("Failed to send gossip to node {}: {}", targetNode, e.getMessage());
                }
            });
        }
        
        lastSyncTime.set(System.currentTimeMillis());
    }
    
    /**
     * 选择Gossip目标节点
     */
    private List<String> selectGossipTargets(int count) {
        List<String> nodes = new ArrayList<>(knownNodes.keySet());
        nodes.remove(nodeId);
        
        if (nodes.isEmpty()) return Collections.emptyList();
        
        Collections.shuffle(nodes);
        return nodes.subList(0, Math.min(count, nodes.size()));
    }
    
    /**
     * 发送更新到指定节点
     */
    private void sendUpdatesToNode(String targetNode, List<SyncUpdate> updates) {
        // 实际实现中通过RPC或消息队列发送
        log.debug("Sending {} updates to node {}", updates.size(), targetNode);
    }
    
    /**
     * 启动反熵协议
     */
    private void startAntiEntropyProtocol() {
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                runAntiEntropy();
            } catch (Exception e) {
                log.error("Anti-entropy error", e);
            }
        }, antiEntropyIntervalMs, antiEntropyIntervalMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 执行反熵同步
     */
    private void runAntiEntropy() {
        if (knownNodes.isEmpty()) return;
        
        antiEntropyCounter.increment();
        
        // 随机选择一个节点进行完整同步
        List<String> nodes = new ArrayList<>(knownNodes.keySet());
        nodes.remove(nodeId);
        
        if (nodes.isEmpty()) return;
        
        String targetNode = nodes.get(ThreadLocalRandom.current().nextInt(nodes.size()));
        
        // 计算本地数据摘要
        String localDigest = calculateDataDigest();
        
        // 发送摘要请求同步
        log.debug("Anti-entropy sync with node {}, local digest: {}", targetNode, localDigest);
        
        // 实际实现中应该发送摘要并比较差异
    }
    
    /**
     * 计算数据摘要
     */
    private String calculateDataDigest() {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            versionedData.forEach((key, entry) -> {
                md.update(key.getBytes(StandardCharsets.UTF_8));
                md.update(entry.vectorClock().toString().getBytes(StandardCharsets.UTF_8));
            });
            byte[] digest = md.digest();
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 注册节点
     */
    public void registerNode(String nodeId, String address) {
        knownNodes.put(nodeId, new NodeInfo(nodeId, address, System.currentTimeMillis(), true));
        log.info("Registered node: {} at {}", nodeId, address);
    }
    
    /**
     * 注销节点
     */
    public void unregisterNode(String nodeId) {
        knownNodes.remove(nodeId);
        remoteVectorClocks.remove(nodeId);
        log.info("Unregistered node: {}", nodeId);
    }
    
    /**
     * 定期清理墓碑
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void cleanupTombstones() {
        long threshold = System.currentTimeMillis() - 3600000; // 1小时前的墓碑
        
        versionedData.entrySet().removeIf(entry -> 
            entry.getValue().value() == null && 
            entry.getValue().timestamp() < threshold);
        
        log.debug("Cleaned up tombstones, remaining entries: {}", versionedData.size());
    }
    
    /**
     * 获取协议统计
     */
    public SyncStats getStats() {
        return new SyncStats(
            nodeId,
            syncEnabled,
            knownNodes.size(),
            versionedData.size(),
            pendingUpdates.size(),
            totalSyncs.sum(),
            totalConflicts.sum(),
            lastSyncTime.get(),
            new HashMap<>(localVectorClock)
        );
    }
    
    /**
     * 获取指定key的版本信息
     */
    public Optional<VersionedEntry> getVersionedEntry(String key) {
        return Optional.ofNullable(versionedData.get(key));
    }
    
    // ========== 内部类型 ==========
    
    public enum UpdateType { PUT, DELETE }
    
    public enum ConflictResolution { 
        LAST_WRITER_WINS, 
        FIRST_WRITER_WINS, 
        HIGHER_NODE_WINS, 
        MERGE 
    }
    
    private enum VectorClockRelation { 
        BEFORE, AFTER, CONCURRENT, EQUAL 
    }
    
    public record SyncUpdate(
        UpdateType type,
        String key,
        String value,
        Map<String, Long> vectorClock,
        String sourceNode,
        long ttlSeconds
    ) {}
    
    public record VersionedEntry(
        String key,
        String value,
        Map<String, Long> vectorClock,
        String sourceNode,
        long timestamp,
        long ttlSeconds
    ) {}
    
    public record NodeInfo(
        String nodeId,
        String address,
        long lastSeen,
        boolean alive
    ) {}
    
    public record SyncStats(
        String nodeId,
        boolean enabled,
        int knownNodesCount,
        int versionedEntriesCount,
        int pendingUpdatesCount,
        long totalSyncs,
        long totalConflicts,
        long lastSyncTime,
        Map<String, Long> vectorClock
    ) {}
}
