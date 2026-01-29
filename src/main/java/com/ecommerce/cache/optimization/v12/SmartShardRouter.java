package com.ecommerce.cache.optimization.v12;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 智能分片路由引擎
 * 
 * 核心特性:
 * 1. 一致性哈希: 虚拟节点映射
 * 2. 负载均衡: 自动负载迁移
 * 3. 热点检测: 热点分片拆分
 * 4. 弹性伸缩: 动态节点管理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartShardRouter {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV12Properties properties;
    
    /** 哈希环 */
    private final TreeMap<Long, VirtualNode> hashRing = new TreeMap<>();
    
    /** 物理节点 */
    private final ConcurrentMap<String, PhysicalNode> physicalNodes = new ConcurrentHashMap<>();
    
    /** 分片统计 */
    private final ConcurrentMap<String, ShardStats> shardStats = new ConcurrentHashMap<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong routeCount = new AtomicLong(0);
    private final AtomicLong rebalanceCount = new AtomicLong(0);
    private final AtomicLong hotspotCount = new AtomicLong(0);
    
    private Counter routeCounter;
    private Counter rebalanceCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getShardRouter().isEnabled()) {
            log.info("[分片路由] 已禁用");
            return;
        }
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "shard-router");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 定期检查负载均衡
        scheduler.scheduleWithFixedDelay(this::checkRebalance, 30, 30, TimeUnit.SECONDS);
        
        // 定期检测热点
        scheduler.scheduleWithFixedDelay(this::detectHotspots, 60, 60, TimeUnit.SECONDS);
        
        log.info("[分片路由] 初始化完成 - 虚拟节点: {}, 哈希算法: {}",
            properties.getShardRouter().getVirtualNodes(),
            properties.getShardRouter().getHashAlgorithm());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        log.info("[分片路由] 已关闭 - 路由: {}, 重平衡: {}",
            routeCount.get(), rebalanceCount.get());
    }
    
    private void initMetrics() {
        routeCounter = Counter.builder("cache.shard.route").register(meterRegistry);
        rebalanceCounter = Counter.builder("cache.shard.rebalance").register(meterRegistry);
    }
    
    // ========== 节点管理 ==========
    
    /**
     * 添加物理节点
     */
    public void addNode(String nodeId, String address, int weight) {
        PhysicalNode node = new PhysicalNode(nodeId, address, weight);
        physicalNodes.put(nodeId, node);
        
        // 添加虚拟节点
        int virtualCount = properties.getShardRouter().getVirtualNodes() * weight;
        for (int i = 0; i < virtualCount; i++) {
            long hash = hash(nodeId + "#" + i);
            VirtualNode vnode = new VirtualNode(nodeId, i, hash);
            hashRing.put(hash, vnode);
        }
        
        log.info("[分片路由] 添加节点: {} - 虚拟节点: {}", nodeId, virtualCount);
    }
    
    /**
     * 移除物理节点
     */
    public void removeNode(String nodeId) {
        PhysicalNode node = physicalNodes.remove(nodeId);
        if (node == null) return;
        
        // 移除虚拟节点
        hashRing.entrySet().removeIf(e -> e.getValue().getPhysicalNodeId().equals(nodeId));
        
        log.info("[分片路由] 移除节点: {}", nodeId);
    }
    
    /**
     * 获取所有节点
     */
    public List<PhysicalNode> getNodes() {
        return new ArrayList<>(physicalNodes.values());
    }
    
    // ========== 路由 ==========
    
    /**
     * 路由Key到节点
     */
    public String route(String key) {
        routeCount.incrementAndGet();
        routeCounter.increment();
        
        if (hashRing.isEmpty()) {
            throw new IllegalStateException("No nodes available");
        }
        
        long hash = hash(key);
        
        // 顺时针找第一个虚拟节点
        Map.Entry<Long, VirtualNode> entry = hashRing.ceilingEntry(hash);
        if (entry == null) {
            entry = hashRing.firstEntry();
        }
        
        String nodeId = entry.getValue().getPhysicalNodeId();
        
        // 记录统计
        shardStats.computeIfAbsent(nodeId, k -> new ShardStats(k))
            .recordAccess();
        
        return nodeId;
    }
    
    /**
     * 路由Key到多个副本节点
     */
    public List<String> routeWithReplicas(String key, int replicaCount) {
        List<String> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        long hash = hash(key);
        Map.Entry<Long, VirtualNode> entry = hashRing.ceilingEntry(hash);
        
        while (nodes.size() < replicaCount && seen.size() < physicalNodes.size()) {
            if (entry == null) {
                entry = hashRing.firstEntry();
            }
            
            String nodeId = entry.getValue().getPhysicalNodeId();
            if (seen.add(nodeId)) {
                nodes.add(nodeId);
            }
            
            entry = hashRing.higherEntry(entry.getKey());
        }
        
        return nodes;
    }
    
    /**
     * 批量路由
     */
    public Map<String, List<String>> routeBatch(Collection<String> keys) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        
        for (String key : keys) {
            String nodeId = route(key);
            result.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(key);
        }
        
        return result;
    }
    
    // ========== 负载均衡 ==========
    
    private void checkRebalance() {
        if (!properties.getShardRouter().isAutoRebalance()) return;
        if (physicalNodes.size() < 2) return;
        
        // 计算负载方差
        double[] loads = new double[physicalNodes.size()];
        int idx = 0;
        long totalLoad = 0;
        
        for (ShardStats stats : shardStats.values()) {
            if (idx < loads.length) {
                loads[idx++] = stats.getAccessCount();
                totalLoad += stats.getAccessCount();
            }
        }
        
        if (totalLoad == 0) return;
        
        double mean = (double) totalLoad / physicalNodes.size();
        double variance = 0;
        for (double load : loads) {
            variance += Math.pow(load - mean, 2);
        }
        variance /= loads.length;
        
        double threshold = properties.getShardRouter().getRebalanceThreshold();
        double cv = Math.sqrt(variance) / mean; // 变异系数
        
        if (cv > threshold) {
            triggerRebalance();
        }
    }
    
    private void triggerRebalance() {
        rebalanceCount.incrementAndGet();
        rebalanceCounter.increment();
        
        // 找到负载最高和最低的节点
        String maxNode = null, minNode = null;
        long maxLoad = Long.MIN_VALUE, minLoad = Long.MAX_VALUE;
        
        for (var entry : shardStats.entrySet()) {
            long load = entry.getValue().getAccessCount();
            if (load > maxLoad) {
                maxLoad = load;
                maxNode = entry.getKey();
            }
            if (load < minLoad) {
                minLoad = load;
                minNode = entry.getKey();
            }
        }
        
        if (maxNode != null && minNode != null && !maxNode.equals(minNode)) {
            // 调整权重
            PhysicalNode max = physicalNodes.get(maxNode);
            PhysicalNode min = physicalNodes.get(minNode);
            
            if (max != null && min != null) {
                max.setWeight(Math.max(1, max.getWeight() - 1));
                min.setWeight(min.getWeight() + 1);
                
                log.info("[分片路由] 重平衡 - 降低: {}, 提升: {}", maxNode, minNode);
            }
        }
        
        // 重置统计
        shardStats.values().forEach(ShardStats::reset);
    }
    
    private void detectHotspots() {
        long avgLoad = 0;
        int count = 0;
        
        for (ShardStats stats : shardStats.values()) {
            avgLoad += stats.getAccessCount();
            count++;
        }
        
        if (count == 0) return;
        avgLoad /= count;
        
        for (var entry : shardStats.entrySet()) {
            if (entry.getValue().getAccessCount() > avgLoad * 3) {
                hotspotCount.incrementAndGet();
                log.warn("[分片路由] 检测到热点分片: {} - 负载: {}",
                    entry.getKey(), entry.getValue().getAccessCount());
            }
        }
    }
    
    // ========== 哈希函数 ==========
    
    private long hash(String key) {
        // MurmurHash3
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        int seed = 0x9747b28c;
        
        int h1 = seed;
        int c1 = 0xcc9e2d51;
        int c2 = 0x1b873593;
        
        int len = data.length;
        int i = 0;
        
        while (len >= 4) {
            int k1 = (data[i] & 0xff) | ((data[i + 1] & 0xff) << 8) |
                    ((data[i + 2] & 0xff) << 16) | ((data[i + 3] & 0xff) << 24);
            
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
            
            i += 4;
            len -= 4;
        }
        
        h1 ^= data.length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        
        return h1 & 0xffffffffL;
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("physicalNodeCount", physicalNodes.size());
        stats.put("virtualNodeCount", hashRing.size());
        stats.put("routeCount", routeCount.get());
        stats.put("rebalanceCount", rebalanceCount.get());
        stats.put("hotspotCount", hotspotCount.get());
        
        Map<String, Long> nodeLoads = new LinkedHashMap<>();
        for (var entry : shardStats.entrySet()) {
            nodeLoads.put(entry.getKey(), entry.getValue().getAccessCount());
        }
        stats.put("nodeLoads", nodeLoads);
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    @Data
    public static class PhysicalNode {
        private final String nodeId;
        private final String address;
        private int weight;
    }
    
    @Data
    private static class VirtualNode {
        private final String physicalNodeId;
        private final int index;
        private final long hash;
    }
    
    @Data
    private static class ShardStats {
        private final String nodeId;
        private final AtomicLong accessCount = new AtomicLong(0);
        
        void recordAccess() {
            accessCount.incrementAndGet();
        }
        
        long getAccessCount() {
            return accessCount.get();
        }
        
        void reset() {
            accessCount.set(0);
        }
    }
}
