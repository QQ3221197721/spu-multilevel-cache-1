package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 缓存拓扑感知路由器
 * 
 * 核心特性:
 * 1. 网络拓扑感知: 基于机房、机架、主机位置优化路由
 * 2. 延迟感知: 实时监测并选择低延迟节点
 * 3. 负载均衡: 基于节点负载智能分流
 * 4. 健康检查: 自动剔除不健康节点
 * 5. 亲和性路由: 保持会话和数据亲和性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopologyAwareRouter {
    
    private final OptimizationV7Properties properties;
    private final MeterRegistry meterRegistry;
    
    // ========== 拓扑结构 ==========
    
    /** 节点注册表 */
    private final ConcurrentMap<String, CacheNode> nodeRegistry = new ConcurrentHashMap<>();
    
    /** 机房->机架->节点 映射 */
    private final ConcurrentMap<String, ConcurrentMap<String, Set<String>>> topology = new ConcurrentHashMap<>();
    
    /** 本地节点标识 */
    private volatile String localNodeId;
    private volatile String localDatacenter;
    private volatile String localRack;
    
    /** 路由缓存 */
    private final ConcurrentMap<String, RoutingDecision> routeCache = new ConcurrentHashMap<>();
    
    /** 延迟统计 */
    private final ConcurrentMap<String, LatencyStats> latencyStats = new ConcurrentHashMap<>();
    
    /** 会话亲和性映射 */
    private final ConcurrentMap<String, String> sessionAffinity = new ConcurrentHashMap<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 路由计数器 */
    private final LongAdder totalRoutes = new LongAdder();
    private final LongAdder localRoutes = new LongAdder();
    private final LongAdder crossDcRoutes = new LongAdder();
    
    // ========== 指标 ==========
    
    private Counter routeDecisionCounter;
    private Counter crossDcCounter;
    private Timer routeLatencyTimer;
    
    @PostConstruct
    public void init() {
        if (!properties.isTopologyAwareEnabled()) {
            log.info("[拓扑感知路由] 未启用");
            return;
        }
        
        // 初始化本地标识
        initLocalIdentity();
        
        // 初始化调度器
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "topology-router");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化指标
        initMetrics();
        
        // 启动后台任务
        startBackgroundTasks();
        
        log.info("[拓扑感知路由] 初始化完成 - 本地节点: {}, 机房: {}, 机架: {}",
            localNodeId, localDatacenter, localRack);
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        log.info("[拓扑感知路由] 已关闭");
    }
    
    private void initLocalIdentity() {
        // 从环境变量或配置获取本地标识
        localNodeId = System.getenv().getOrDefault("NODE_ID", UUID.randomUUID().toString().substring(0, 8));
        localDatacenter = System.getenv().getOrDefault("DATACENTER", "dc-default");
        localRack = System.getenv().getOrDefault("RACK", "rack-default");
    }
    
    private void initMetrics() {
        routeDecisionCounter = Counter.builder("cache.topology.route.decision")
            .description("路由决策次数")
            .register(meterRegistry);
        
        crossDcCounter = Counter.builder("cache.topology.route.crossdc")
            .description("跨机房路由次数")
            .register(meterRegistry);
        
        routeLatencyTimer = Timer.builder("cache.topology.route.latency")
            .description("路由决策延迟")
            .register(meterRegistry);
        
        Gauge.builder("cache.topology.nodes.total", nodeRegistry, Map::size)
            .description("注册节点总数")
            .register(meterRegistry);
        
        Gauge.builder("cache.topology.datacenters.total", topology, Map::size)
            .description("机房总数")
            .register(meterRegistry);
    }
    
    private void startBackgroundTasks() {
        var config = properties.getTopologyAware();
        
        // 拓扑刷新任务
        scheduler.scheduleAtFixedRate(
            this::refreshTopology,
            config.getTopologyRefreshSeconds(),
            config.getTopologyRefreshSeconds(),
            TimeUnit.SECONDS
        );
        
        // 健康检查任务
        scheduler.scheduleAtFixedRate(
            this::healthCheck,
            10,
            10,
            TimeUnit.SECONDS
        );
        
        // 路由缓存清理
        scheduler.scheduleAtFixedRate(
            this::cleanupRouteCache,
            60,
            60,
            TimeUnit.SECONDS
        );
    }
    
    // ========== 核心API ==========
    
    /**
     * 注册缓存节点
     */
    public void registerNode(String nodeId, String host, int port, 
                            String datacenter, String rack) {
        if (!properties.isTopologyAwareEnabled()) return;
        
        CacheNode node = new CacheNode(nodeId, host, port, datacenter, rack);
        nodeRegistry.put(nodeId, node);
        
        // 更新拓扑结构
        topology.computeIfAbsent(datacenter, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(rack, k -> ConcurrentHashMap.newKeySet())
                .add(nodeId);
        
        log.info("[拓扑感知路由] 注册节点: {} ({}/{})", nodeId, datacenter, rack);
    }
    
    /**
     * 注销缓存节点
     */
    public void unregisterNode(String nodeId) {
        if (!properties.isTopologyAwareEnabled()) return;
        
        CacheNode node = nodeRegistry.remove(nodeId);
        if (node != null) {
            ConcurrentMap<String, Set<String>> dcTopology = topology.get(node.getDatacenter());
            if (dcTopology != null) {
                Set<String> rackNodes = dcTopology.get(node.getRack());
                if (rackNodes != null) {
                    rackNodes.remove(nodeId);
                }
            }
            
            log.info("[拓扑感知路由] 注销节点: {}", nodeId);
        }
    }
    
    /**
     * 选择最优路由节点
     */
    public String selectNode(String key) {
        return selectNode(key, null);
    }
    
    /**
     * 选择最优路由节点(带会话亲和性)
     */
    public String selectNode(String key, String sessionId) {
        if (!properties.isTopologyAwareEnabled() || nodeRegistry.isEmpty()) {
            return null;
        }
        
        return routeLatencyTimer.record(() -> {
            routeDecisionCounter.increment();
            totalRoutes.increment();
            
            // 检查会话亲和性
            if (sessionId != null) {
                String affinityNode = sessionAffinity.get(sessionId);
                if (affinityNode != null && isNodeHealthy(affinityNode)) {
                    return affinityNode;
                }
            }
            
            // 检查路由缓存
            RoutingDecision cached = routeCache.get(key);
            if (cached != null && !cached.isExpired() && isNodeHealthy(cached.getNodeId())) {
                return cached.getNodeId();
            }
            
            // 计算最优路由
            String selectedNode = calculateBestRoute(key);
            
            // 更新缓存
            if (selectedNode != null) {
                routeCache.put(key, new RoutingDecision(
                    selectedNode,
                    System.currentTimeMillis() + 
                        properties.getTopologyAware().getTopologyRefreshSeconds() * 1000L
                ));
                
                if (sessionId != null) {
                    sessionAffinity.put(sessionId, selectedNode);
                }
                
                // 统计跨机房路由
                CacheNode node = nodeRegistry.get(selectedNode);
                if (node != null && !node.getDatacenter().equals(localDatacenter)) {
                    crossDcRoutes.increment();
                    crossDcCounter.increment();
                } else {
                    localRoutes.increment();
                }
            }
            
            return selectedNode;
        });
    }
    
    /**
     * 选择多个节点(用于副本写入)
     */
    public List<String> selectNodes(String key, int count) {
        if (!properties.isTopologyAwareEnabled() || nodeRegistry.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<NodeScore> scoredNodes = calculateAllScores(key);
        
        return scoredNodes.stream()
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .limit(count)
            .map(ns -> ns.nodeId)
            .collect(Collectors.toList());
    }
    
    /**
     * 记录请求延迟
     */
    public void recordLatency(String nodeId, long latencyMs, boolean success) {
        if (!properties.isTopologyAwareEnabled()) return;
        
        latencyStats.computeIfAbsent(nodeId, k -> new LatencyStats())
                   .recordLatency(latencyMs, success);
        
        CacheNode node = nodeRegistry.get(nodeId);
        if (node != null) {
            node.recordRequest(success);
        }
    }
    
    /**
     * 获取节点信息
     */
    public CacheNode getNode(String nodeId) {
        return nodeRegistry.get(nodeId);
    }
    
    /**
     * 获取同机房节点
     */
    public List<String> getSameDatacenterNodes() {
        return getSameDatacenterNodes(localDatacenter);
    }
    
    public List<String> getSameDatacenterNodes(String datacenter) {
        ConcurrentMap<String, Set<String>> dcTopology = topology.get(datacenter);
        if (dcTopology == null) {
            return Collections.emptyList();
        }
        
        return dcTopology.values().stream()
            .flatMap(Set::stream)
            .filter(this::isNodeHealthy)
            .collect(Collectors.toList());
    }
    
    /**
     * 获取同机架节点
     */
    public List<String> getSameRackNodes() {
        ConcurrentMap<String, Set<String>> dcTopology = topology.get(localDatacenter);
        if (dcTopology == null) {
            return Collections.emptyList();
        }
        
        Set<String> rackNodes = dcTopology.get(localRack);
        if (rackNodes == null) {
            return Collections.emptyList();
        }
        
        return rackNodes.stream()
            .filter(this::isNodeHealthy)
            .collect(Collectors.toList());
    }
    
    // ========== 内部方法 ==========
    
    private String calculateBestRoute(String key) {
        var config = properties.getTopologyAware();
        
        List<NodeScore> scoredNodes = calculateAllScores(key);
        
        if (scoredNodes.isEmpty()) {
            return null;
        }
        
        // 返回最高分节点
        return scoredNodes.stream()
            .max(Comparator.comparingDouble(ns -> ns.score))
            .map(ns -> ns.nodeId)
            .orElse(null);
    }
    
    private List<NodeScore> calculateAllScores(String key) {
        var config = properties.getTopologyAware();
        List<NodeScore> scoredNodes = new ArrayList<>();
        
        for (var entry : nodeRegistry.entrySet()) {
            String nodeId = entry.getKey();
            CacheNode node = entry.getValue();
            
            if (!isNodeHealthy(nodeId)) {
                continue;
            }
            
            double score = 0;
            
            // 1. 位置分数
            double locationScore = calculateLocationScore(node, config);
            score += locationScore * (config.getLatencyWeight() + config.getLoadWeight()) / 2;
            
            // 2. 延迟分数
            LatencyStats stats = latencyStats.get(nodeId);
            double latencyScore = calculateLatencyScore(stats, config);
            score += latencyScore * config.getLatencyWeight();
            
            // 3. 负载分数
            double loadScore = calculateLoadScore(node);
            score += loadScore * config.getLoadWeight();
            
            // 4. 健康分数
            double healthScore = calculateHealthScore(node);
            score += healthScore * config.getHealthWeight();
            
            // 5. 一致性哈希偏好
            int keyHash = key.hashCode();
            int nodeHash = nodeId.hashCode();
            double hashAffinity = 1.0 - Math.abs(keyHash - nodeHash) / (double) Integer.MAX_VALUE;
            score += hashAffinity * 0.1;
            
            scoredNodes.add(new NodeScore(nodeId, score));
        }
        
        return scoredNodes;
    }
    
    private double calculateLocationScore(CacheNode node, OptimizationV7Properties.TopologyAware config) {
        double score = 0;
        
        // 同机房加分
        if (node.getDatacenter().equals(localDatacenter)) {
            score += config.getSameDcBoost();
            
            // 同机架额外加分
            if (config.isRackPreferred() && node.getRack().equals(localRack)) {
                score += 0.5;
            }
        } else {
            // 跨机房惩罚
            score -= config.getCrossDcPenalty();
        }
        
        return Math.max(0, score);
    }
    
    private double calculateLatencyScore(LatencyStats stats, OptimizationV7Properties.TopologyAware config) {
        if (stats == null) {
            return 0.5; // 无数据时给中等分数
        }
        
        double avgLatency = stats.getAverageLatency();
        int maxThreshold = config.getMaxLatencyThresholdMs();
        
        // 延迟越低分数越高
        return Math.max(0, 1 - avgLatency / maxThreshold);
    }
    
    private double calculateLoadScore(CacheNode node) {
        double load = node.getLoad();
        // 负载越低分数越高
        return Math.max(0, 1 - load);
    }
    
    private double calculateHealthScore(CacheNode node) {
        // 基于成功率计算健康分数
        return node.getSuccessRate();
    }
    
    private boolean isNodeHealthy(String nodeId) {
        CacheNode node = nodeRegistry.get(nodeId);
        if (node == null) {
            return false;
        }
        
        return node.isHealthy();
    }
    
    private void refreshTopology() {
        log.debug("[拓扑感知路由] 刷新拓扑结构");
        
        // 清理不健康节点的路由缓存
        Set<String> unhealthyNodes = nodeRegistry.entrySet().stream()
            .filter(e -> !e.getValue().isHealthy())
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        
        routeCache.entrySet().removeIf(e -> unhealthyNodes.contains(e.getValue().getNodeId()));
    }
    
    private void healthCheck() {
        for (var entry : nodeRegistry.entrySet()) {
            CacheNode node = entry.getValue();
            
            // 简单的健康检查逻辑
            // 实际生产中应该发送心跳或执行探测
            if (node.getSuccessRate() < 0.5) {
                node.markUnhealthy();
            } else if (node.getSuccessRate() > 0.8) {
                node.markHealthy();
            }
        }
    }
    
    private void cleanupRouteCache() {
        routeCache.entrySet().removeIf(e -> e.getValue().isExpired());
        
        // 清理过期的会话亲和性
        if (sessionAffinity.size() > 100000) {
            // 简单的LRU策略: 随机删除一半
            int toRemove = sessionAffinity.size() / 2;
            Iterator<String> it = sessionAffinity.keySet().iterator();
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", properties.isTopologyAwareEnabled());
        stats.put("localNodeId", localNodeId);
        stats.put("localDatacenter", localDatacenter);
        stats.put("localRack", localRack);
        stats.put("registeredNodes", nodeRegistry.size());
        stats.put("datacenters", topology.keySet());
        stats.put("routeCacheSize", routeCache.size());
        stats.put("sessionAffinityCount", sessionAffinity.size());
        
        // 路由统计
        long total = totalRoutes.sum();
        stats.put("totalRoutes", total);
        stats.put("localRouteRate", total > 0 ? 
            String.format("%.2f%%", (double) localRoutes.sum() / total * 100) : "0.00%");
        stats.put("crossDcRouteRate", total > 0 ? 
            String.format("%.2f%%", (double) crossDcRoutes.sum() / total * 100) : "0.00%");
        
        // 节点状态
        Map<String, Object> nodeStats = new LinkedHashMap<>();
        for (var entry : nodeRegistry.entrySet()) {
            CacheNode node = entry.getValue();
            Map<String, Object> nodeStat = new LinkedHashMap<>();
            nodeStat.put("datacenter", node.getDatacenter());
            nodeStat.put("rack", node.getRack());
            nodeStat.put("healthy", node.isHealthy());
            nodeStat.put("successRate", String.format("%.2f%%", node.getSuccessRate() * 100));
            nodeStat.put("load", String.format("%.2f%%", node.getLoad() * 100));
            
            LatencyStats latency = latencyStats.get(entry.getKey());
            if (latency != null) {
                nodeStat.put("avgLatency", String.format("%.2fms", latency.getAverageLatency()));
            }
            
            nodeStats.put(entry.getKey(), nodeStat);
        }
        stats.put("nodes", nodeStats);
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    /**
     * 缓存节点
     */
    @Data
    public static class CacheNode {
        private final String nodeId;
        private final String host;
        private final int port;
        private final String datacenter;
        private final String rack;
        
        private volatile boolean healthy = true;
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong successRequests = new AtomicLong(0);
        private volatile double load = 0;
        
        void recordRequest(boolean success) {
            totalRequests.incrementAndGet();
            if (success) {
                successRequests.incrementAndGet();
            }
        }
        
        double getSuccessRate() {
            long total = totalRequests.get();
            return total > 0 ? (double) successRequests.get() / total : 1.0;
        }
        
        void markHealthy() { this.healthy = true; }
        void markUnhealthy() { this.healthy = false; }
        void setLoad(double load) { this.load = load; }
    }
    
    /**
     * 延迟统计
     */
    private static class LatencyStats {
        private final Queue<Long> latencies = new ConcurrentLinkedQueue<>();
        private final AtomicLong totalLatency = new AtomicLong(0);
        private final AtomicLong count = new AtomicLong(0);
        
        void recordLatency(long latencyMs, boolean success) {
            latencies.offer(latencyMs);
            totalLatency.addAndGet(latencyMs);
            count.incrementAndGet();
            
            // 保持最近1000个样本
            while (latencies.size() > 1000) {
                Long removed = latencies.poll();
                if (removed != null) {
                    totalLatency.addAndGet(-removed);
                    count.decrementAndGet();
                }
            }
        }
        
        double getAverageLatency() {
            long c = count.get();
            return c > 0 ? (double) totalLatency.get() / c : 0;
        }
    }
    
    /**
     * 路由决策
     */
    @Data
    private static class RoutingDecision {
        private final String nodeId;
        private final long expirationTime;
        
        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
    
    /**
     * 节点评分
     */
    private record NodeScore(String nodeId, double score) {}
}
