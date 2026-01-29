package com.ecommerce.cache.optimization.v11;

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
 * 图计算缓存优化器
 * 
 * 核心特性:
 * 1. 图分区缓存: 分区级缓存优化
 * 2. 邻居预加载: 关联数据预取
 * 3. 路径缓存: 热门路径记忆
 * 4. PageRank优化: 重要节点优先
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphCacheOptimizer {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV11Properties properties;
    
    /** 图结构存储 */
    private final ConcurrentMap<String, GraphNode> nodes = new ConcurrentHashMap<>();
    
    /** 邻接表 */
    private final ConcurrentMap<String, Set<String>> adjacencyList = new ConcurrentHashMap<>();
    
    /** 路径缓存 */
    private final ConcurrentMap<String, List<String>> pathCache = new ConcurrentHashMap<>();
    
    /** PageRank分数 */
    private final ConcurrentMap<String, Double> pageRankScores = new ConcurrentHashMap<>();
    
    /** 分区映射 */
    private final ConcurrentMap<String, Integer> partitionMap = new ConcurrentHashMap<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    private ExecutorService computePool;
    
    /** 统计 */
    private final AtomicLong traversalCount = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong neighborPrefetchCount = new AtomicLong(0);
    
    private Counter traversalCounter;
    private Counter cacheHitCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getGraphCache().isEnabled()) {
            log.info("[图缓存] 已禁用");
            return;
        }
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "graph-cache");
            t.setDaemon(true);
            return t;
        });
        
        computePool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "graph-compute");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 定期计算PageRank
        scheduler.scheduleWithFixedDelay(this::computePageRank, 60, 300, TimeUnit.SECONDS);
        
        // 定期清理路径缓存
        scheduler.scheduleWithFixedDelay(this::cleanupPathCache, 120, 120, TimeUnit.SECONDS);
        
        log.info("[图缓存] 初始化完成 - 最大顶点: {}, 分区数: {}",
            properties.getGraphCache().getMaxVertices(),
            properties.getGraphCache().getPartitionCount());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (computePool != null) computePool.shutdown();
        log.info("[图缓存] 已关闭 - 遍历: {}, 命中: {}", 
            traversalCount.get(), cacheHitCount.get());
    }
    
    private void initMetrics() {
        traversalCounter = Counter.builder("cache.graph.traversal").register(meterRegistry);
        cacheHitCounter = Counter.builder("cache.graph.hit").register(meterRegistry);
    }
    
    // ========== 图操作API ==========
    
    /**
     * 添加节点
     */
    public void addNode(String nodeId, Map<String, Object> properties) {
        if (nodes.size() >= this.properties.getGraphCache().getMaxVertices()) {
            evictLowRankNode();
        }
        
        GraphNode node = new GraphNode(nodeId, properties);
        nodes.put(nodeId, node);
        adjacencyList.computeIfAbsent(nodeId, k -> ConcurrentHashMap.newKeySet());
        
        // 分配分区
        int partition = Math.abs(nodeId.hashCode()) % this.properties.getGraphCache().getPartitionCount();
        partitionMap.put(nodeId, partition);
    }
    
    /**
     * 添加边
     */
    public void addEdge(String fromId, String toId) {
        adjacencyList.computeIfAbsent(fromId, k -> ConcurrentHashMap.newKeySet()).add(toId);
    }
    
    /**
     * 获取节点
     */
    public Optional<GraphNode> getNode(String nodeId) {
        GraphNode node = nodes.get(nodeId);
        if (node != null) {
            node.recordAccess();
        }
        return Optional.ofNullable(node);
    }
    
    /**
     * 获取邻居节点
     */
    public Set<String> getNeighbors(String nodeId) {
        return adjacencyList.getOrDefault(nodeId, Collections.emptySet());
    }
    
    /**
     * 预加载邻居
     */
    public List<GraphNode> prefetchNeighbors(String nodeId, int depth) {
        neighborPrefetchCount.incrementAndGet();
        
        List<GraphNode> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        
        queue.offer(nodeId);
        visited.add(nodeId);
        int currentDepth = 0;
        int levelSize = 1;
        int nextLevelSize = 0;
        
        while (!queue.isEmpty() && currentDepth < depth) {
            String current = queue.poll();
            levelSize--;
            
            GraphNode node = nodes.get(current);
            if (node != null) {
                result.add(node);
            }
            
            for (String neighbor : getNeighbors(current)) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.offer(neighbor);
                    nextLevelSize++;
                }
            }
            
            if (levelSize == 0) {
                levelSize = nextLevelSize;
                nextLevelSize = 0;
                currentDepth++;
            }
        }
        
        return result;
    }
    
    // ========== 图遍历 ==========
    
    /**
     * BFS遍历
     */
    public List<String> bfsTraversal(String startId, int maxDepth) {
        traversalCount.incrementAndGet();
        traversalCounter.increment();
        
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        
        queue.offer(startId);
        visited.add(startId);
        int depth = 0;
        int levelSize = 1;
        int nextLevelSize = 0;
        
        while (!queue.isEmpty() && depth < maxDepth) {
            String current = queue.poll();
            result.add(current);
            levelSize--;
            
            for (String neighbor : getNeighbors(current)) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.offer(neighbor);
                    nextLevelSize++;
                }
            }
            
            if (levelSize == 0) {
                levelSize = nextLevelSize;
                nextLevelSize = 0;
                depth++;
            }
        }
        
        return result;
    }
    
    /**
     * 最短路径(缓存)
     */
    public List<String> shortestPath(String from, String to) {
        String cacheKey = from + "->" + to;
        
        List<String> cached = pathCache.get(cacheKey);
        if (cached != null) {
            cacheHitCount.incrementAndGet();
            cacheHitCounter.increment();
            return cached;
        }
        
        // BFS找最短路径
        Map<String, String> parent = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        
        queue.offer(from);
        visited.add(from);
        parent.put(from, null);
        
        while (!queue.isEmpty()) {
            String current = queue.poll();
            
            if (current.equals(to)) {
                List<String> path = new ArrayList<>();
                String node = to;
                while (node != null) {
                    path.add(0, node);
                    node = parent.get(node);
                }
                pathCache.put(cacheKey, path);
                return path;
            }
            
            for (String neighbor : getNeighbors(current)) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    parent.put(neighbor, current);
                    queue.offer(neighbor);
                }
            }
        }
        
        return Collections.emptyList();
    }
    
    /**
     * 获取高价值节点(PageRank)
     */
    public List<String> getHighValueNodes(int topK) {
        return pageRankScores.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(topK)
            .map(Map.Entry::getKey)
            .toList();
    }
    
    // ========== 内部方法 ==========
    
    private void computePageRank() {
        if (nodes.isEmpty()) return;
        
        double dampingFactor = 0.85;
        int iterations = 20;
        double initialRank = 1.0 / nodes.size();
        
        // 初始化
        Map<String, Double> ranks = new HashMap<>();
        for (String nodeId : nodes.keySet()) {
            ranks.put(nodeId, initialRank);
        }
        
        // 迭代计算
        for (int i = 0; i < iterations; i++) {
            Map<String, Double> newRanks = new HashMap<>();
            
            for (String nodeId : nodes.keySet()) {
                double rank = (1 - dampingFactor) / nodes.size();
                
                // 找入边
                for (var entry : adjacencyList.entrySet()) {
                    if (entry.getValue().contains(nodeId)) {
                        String fromNode = entry.getKey();
                        int outDegree = entry.getValue().size();
                        if (outDegree > 0) {
                            rank += dampingFactor * ranks.get(fromNode) / outDegree;
                        }
                    }
                }
                
                newRanks.put(nodeId, rank);
            }
            
            ranks = newRanks;
        }
        
        pageRankScores.clear();
        pageRankScores.putAll(ranks);
        
        log.debug("[图缓存] PageRank计算完成 - 节点数: {}", nodes.size());
    }
    
    private void evictLowRankNode() {
        if (pageRankScores.isEmpty()) return;
        
        String lowestNode = pageRankScores.entrySet().stream()
            .min(Comparator.comparingDouble(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .orElse(null);
        
        if (lowestNode != null) {
            nodes.remove(lowestNode);
            adjacencyList.remove(lowestNode);
            pageRankScores.remove(lowestNode);
            partitionMap.remove(lowestNode);
        }
    }
    
    private void cleanupPathCache() {
        if (pathCache.size() > 10000) {
            int toRemove = pathCache.size() - 5000;
            Iterator<String> it = pathCache.keySet().iterator();
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodeCount", nodes.size());
        stats.put("edgeCount", adjacencyList.values().stream().mapToInt(Set::size).sum());
        stats.put("traversalCount", traversalCount.get());
        stats.put("cacheHitCount", cacheHitCount.get());
        stats.put("neighborPrefetchCount", neighborPrefetchCount.get());
        stats.put("pathCacheSize", pathCache.size());
        stats.put("partitionCount", properties.getGraphCache().getPartitionCount());
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    @Data
    public static class GraphNode {
        private final String id;
        private final Map<String, Object> properties;
        private long lastAccessTime;
        private long accessCount;
        
        GraphNode(String id, Map<String, Object> properties) {
            this.id = id;
            this.properties = new ConcurrentHashMap<>(properties);
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount = 0;
        }
        
        void recordAccess() {
            this.lastAccessTime = System.currentTimeMillis();
            this.accessCount++;
        }
    }
}
