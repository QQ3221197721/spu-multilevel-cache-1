package com.ecommerce.cache.optimization.v9;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 边缘计算缓存网关
 * 
 * 核心特性:
 * 1. 就近访问: 基于地理位置选择最近节点
 * 2. 边缘缓存: 热点数据下沉到边缘
 * 3. 智能路由: 延迟感知的请求路由
 * 4. 数据同步: 边缘与中心的增量同步
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeComputeGateway {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV9Properties properties;
    
    /** 边缘节点注册表 */
    private final ConcurrentMap<String, EdgeNode> edgeNodes = new ConcurrentHashMap<>();
    
    /** 边缘缓存 */
    private final ConcurrentMap<String, EdgeCacheEntry> edgeCache = new ConcurrentHashMap<>();
    
    /** 路由表 */
    private final ConcurrentMap<String, String> routingTable = new ConcurrentHashMap<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong edgeHits = new AtomicLong(0);
    private final AtomicLong edgeMisses = new AtomicLong(0);
    private final AtomicLong routeCount = new AtomicLong(0);
    
    private Counter hitCounter;
    private Counter missCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getEdgeCompute().isEnabled()) {
            log.info("[边缘网关] 已禁用");
            return;
        }
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "edge-gateway");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 定期同步
        int interval = properties.getEdgeCompute().getSyncIntervalMs();
        scheduler.scheduleWithFixedDelay(this::syncWithCenter, interval, interval, TimeUnit.MILLISECONDS);
        
        // 定期健康检查
        scheduler.scheduleWithFixedDelay(this::healthCheck, 5000, 5000, TimeUnit.MILLISECONDS);
        
        // 定期清理过期缓存
        scheduler.scheduleWithFixedDelay(this::cleanExpired, 30000, 30000, TimeUnit.MILLISECONDS);
        
        log.info("[边缘网关] 初始化完成");
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        log.info("[边缘网关] 已关闭 - 命中: {}, 未命中: {}", edgeHits.get(), edgeMisses.get());
    }
    
    private void initMetrics() {
        hitCounter = Counter.builder("cache.edge.hit").register(meterRegistry);
        missCounter = Counter.builder("cache.edge.miss").register(meterRegistry);
    }
    
    // ========== 边缘节点管理 ==========
    
    public void registerEdgeNode(String nodeId, String region, String address, int port) {
        EdgeNode node = new EdgeNode(
            nodeId,
            region,
            address,
            port,
            System.currentTimeMillis(),
            EdgeNodeState.ONLINE,
            new ConcurrentHashMap<>()
        );
        edgeNodes.put(nodeId, node);
        log.info("[边缘网关] 注册节点: {} @ {}", nodeId, region);
    }
    
    public void deregisterEdgeNode(String nodeId) {
        edgeNodes.remove(nodeId);
        routingTable.entrySet().removeIf(e -> e.getValue().equals(nodeId));
        log.info("[边缘网关] 注销节点: {}", nodeId);
    }
    
    public List<EdgeNode> getEdgeNodes() {
        return new ArrayList<>(edgeNodes.values());
    }
    
    public List<EdgeNode> getNodesByRegion(String region) {
        return edgeNodes.values().stream()
            .filter(n -> n.region.equals(region) && n.state == EdgeNodeState.ONLINE)
            .toList();
    }
    
    // ========== 边缘缓存操作 ==========
    
    public void putToEdge(String key, Object value, String targetRegion) {
        int ttl = properties.getEdgeCompute().getTtlSec();
        EdgeCacheEntry entry = new EdgeCacheEntry(
            key,
            value,
            targetRegion,
            System.currentTimeMillis(),
            System.currentTimeMillis() + ttl * 1000L
        );
        edgeCache.put(key, entry);
        
        // 更新路由
        routingTable.put(key, selectBestNode(targetRegion));
    }
    
    public Object getFromEdge(String key, String requestRegion) {
        EdgeCacheEntry entry = edgeCache.get(key);
        
        if (entry != null && entry.expireTime > System.currentTimeMillis()) {
            // 检查区域匹配
            if (entry.region.equals(requestRegion) || entry.region.equals("*")) {
                edgeHits.incrementAndGet();
                hitCounter.increment();
                return entry.value;
            }
        }
        
        edgeMisses.incrementAndGet();
        missCounter.increment();
        return null;
    }
    
    public void invalidateEdge(String key) {
        edgeCache.remove(key);
        routingTable.remove(key);
    }
    
    public void invalidateByRegion(String region) {
        edgeCache.entrySet().removeIf(e -> e.getValue().region.equals(region));
    }
    
    // ========== 智能路由 ==========
    
    public String route(String key, String clientRegion) {
        routeCount.incrementAndGet();
        
        // 优先检查路由表
        String cached = routingTable.get(key);
        if (cached != null) {
            EdgeNode node = edgeNodes.get(cached);
            if (node != null && node.state == EdgeNodeState.ONLINE) {
                return cached;
            }
        }
        
        // 选择最佳节点
        String best = selectBestNode(clientRegion);
        if (best != null) {
            routingTable.put(key, best);
        }
        return best;
    }
    
    private String selectBestNode(String targetRegion) {
        // 优先同区域
        List<EdgeNode> sameRegion = getNodesByRegion(targetRegion);
        if (!sameRegion.isEmpty()) {
            return selectByLatency(sameRegion);
        }
        
        // 否则选择最低延迟
        List<EdgeNode> all = edgeNodes.values().stream()
            .filter(n -> n.state == EdgeNodeState.ONLINE)
            .toList();
        
        return selectByLatency(all);
    }
    
    private String selectByLatency(List<EdgeNode> nodes) {
        if (nodes.isEmpty()) return null;
        
        return nodes.stream()
            .min(Comparator.comparingDouble(n -> n.metrics.getOrDefault("latency", 999.0)))
            .map(n -> n.nodeId)
            .orElse(nodes.get(0).nodeId);
    }
    
    // ========== 数据同步 ==========
    
    private void syncWithCenter() {
        // 模拟边缘到中心的同步
        int synced = 0;
        for (EdgeCacheEntry entry : edgeCache.values()) {
            if (System.currentTimeMillis() - entry.updateTime > 60000) {
                // 需要同步的数据
                synced++;
            }
        }
        
        if (synced > 0) {
            log.debug("[边缘网关] 同步数据: {}", synced);
        }
    }
    
    private void healthCheck() {
        long now = System.currentTimeMillis();
        
        for (EdgeNode node : edgeNodes.values()) {
            // 模拟健康检查
            if (now - node.lastHeartbeat > 30000) {
                if (node.state == EdgeNodeState.ONLINE) {
                    node.state = EdgeNodeState.SUSPECT;
                    log.warn("[边缘网关] 节点可疑: {}", node.nodeId);
                }
            } else if (now - node.lastHeartbeat > 60000) {
                node.state = EdgeNodeState.OFFLINE;
                log.warn("[边缘网关] 节点离线: {}", node.nodeId);
            }
        }
    }
    
    private void cleanExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        
        Iterator<Map.Entry<String, EdgeCacheEntry>> it = edgeCache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expireTime < now) {
                it.remove();
                removed++;
            }
        }
        
        if (removed > 0) {
            log.debug("[边缘网关] 清理过期: {}", removed);
        }
    }
    
    public void heartbeat(String nodeId) {
        EdgeNode node = edgeNodes.get(nodeId);
        if (node != null) {
            node.lastHeartbeat = System.currentTimeMillis();
            node.state = EdgeNodeState.ONLINE;
        }
    }
    
    public void updateMetrics(String nodeId, String metric, double value) {
        EdgeNode node = edgeNodes.get(nodeId);
        if (node != null) {
            node.metrics.put(metric, value);
        }
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("edgeNodeCount", edgeNodes.size());
        stats.put("onlineNodes", edgeNodes.values().stream().filter(n -> n.state == EdgeNodeState.ONLINE).count());
        stats.put("edgeCacheSize", edgeCache.size());
        stats.put("routingTableSize", routingTable.size());
        stats.put("edgeHits", edgeHits.get());
        stats.put("edgeMisses", edgeMisses.get());
        stats.put("hitRate", calculateHitRate());
        stats.put("routeCount", routeCount.get());
        
        // 按区域统计
        Map<String, Long> regionStats = new LinkedHashMap<>();
        for (EdgeNode node : edgeNodes.values()) {
            regionStats.merge(node.region, 1L, Long::sum);
        }
        stats.put("nodesByRegion", regionStats);
        
        return stats;
    }
    
    private String calculateHitRate() {
        long total = edgeHits.get() + edgeMisses.get();
        if (total == 0) return "N/A";
        return String.format("%.2f%%", (double) edgeHits.get() / total * 100);
    }
    
    // ========== 内部类 ==========
    
    public enum EdgeNodeState {
        ONLINE, SUSPECT, OFFLINE
    }
    
    @Data
    public static class EdgeNode {
        private final String nodeId;
        private final String region;
        private final String address;
        private final int port;
        private long lastHeartbeat;
        private EdgeNodeState state;
        private final Map<String, Double> metrics;
    }
    
    @Data
    private static class EdgeCacheEntry {
        private final String key;
        private final Object value;
        private final String region;
        private final long updateTime;
        private final long expireTime;
    }
}
