package com.ecommerce.cache.optimization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 优化模块增强版 API 控制器 v2.0
 * 
 * 提供所有新增优化模块的管理和监控 API
 * 
 * 新增模块 API：
 * - L0 超热点缓存
 * - 缓存协调器
 * - 零拷贝序列化器
 * - 访问预测器
 * - 一致性哈希管理器
 * - 自适应弹性保护器
 * - 实时性能分析器
 */
@RestController
@RequestMapping("/api/v2/optimization")
public class OptimizationControllerV2 {
    
    private static final Logger log = LoggerFactory.getLogger(OptimizationControllerV2.class);
    
    private final UltraHotCacheLayer ultraHotCache;
    private final CacheCoordinator cacheCoordinator;
    private final ZeroCopySerializer zeroCopySerializer;
    private final AccessPredictor accessPredictor;
    private final ConsistentHashManager hashManager;
    private final AdaptiveResilienceGuard resilienceGuard;
    private final RealTimePerformanceAnalyzer performanceAnalyzer;
    private final AdvancedCacheStrategy advancedCacheStrategy;
    private final DatabaseOptimizer databaseOptimizer;
    
    public OptimizationControllerV2(
            UltraHotCacheLayer ultraHotCache,
            CacheCoordinator cacheCoordinator,
            ZeroCopySerializer zeroCopySerializer,
            AccessPredictor accessPredictor,
            ConsistentHashManager hashManager,
            AdaptiveResilienceGuard resilienceGuard,
            RealTimePerformanceAnalyzer performanceAnalyzer,
            AdvancedCacheStrategy advancedCacheStrategy,
            DatabaseOptimizer databaseOptimizer) {
        this.ultraHotCache = ultraHotCache;
        this.cacheCoordinator = cacheCoordinator;
        this.zeroCopySerializer = zeroCopySerializer;
        this.accessPredictor = accessPredictor;
        this.hashManager = hashManager;
        this.resilienceGuard = resilienceGuard;
        this.performanceAnalyzer = performanceAnalyzer;
        this.advancedCacheStrategy = advancedCacheStrategy;
        this.databaseOptimizer = databaseOptimizer;
    }
    
    // ========== 综合仪表盘 ==========
    
    /**
     * 获取综合仪表盘数据
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        
        // 系统概览
        dashboard.put("systemOverview", Map.of(
            "version", "2.0.0",
            "status", "HEALTHY",
            "uptime", performanceAnalyzer.getUptime() + "s"
        ));
        
        // L0 缓存统计
        dashboard.put("l0Cache", ultraHotCache.getStats());
        
        // 协调器统计
        dashboard.put("coordinator", cacheCoordinator.getStats());
        
        // 序列化器统计
        dashboard.put("serializer", zeroCopySerializer.getStats());
        
        // 预测器统计
        dashboard.put("predictor", accessPredictor.getStats());
        
        // 哈希管理器统计
        dashboard.put("hashManager", hashManager.getStats());
        
        // 弹性保护统计
        dashboard.put("resilience", resilienceGuard.getStats());
        
        // 性能分析器统计
        dashboard.put("analyzer", performanceAnalyzer.getStats());
        
        return ResponseEntity.ok(dashboard);
    }
    
    // ========== L0 超热点缓存 API ==========
    
    @GetMapping("/l0-cache/stats")
    public ResponseEntity<UltraHotCacheLayer.L0CacheStats> getL0CacheStats() {
        return ResponseEntity.ok(ultraHotCache.getStats());
    }
    
    @PostMapping("/l0-cache/clear")
    public ResponseEntity<String> clearL0Cache() {
        ultraHotCache.clear();
        return ResponseEntity.ok("L0 cache cleared");
    }
    
    @GetMapping("/l0-cache/get")
    public ResponseEntity<Map<String, Object>> l0CacheGet(@RequestParam String key) {
        String value = ultraHotCache.get(key);
        return ResponseEntity.ok(Map.of(
            "key", key,
            "value", value != null ? value : "",
            "found", value != null
        ));
    }
    
    @PostMapping("/l0-cache/put")
    public ResponseEntity<String> l0CachePut(
            @RequestParam String key,
            @RequestParam String value,
            @RequestParam(defaultValue = "5000") long ttlMs) {
        ultraHotCache.put(key, value, ttlMs);
        return ResponseEntity.ok("Key cached in L0: " + key);
    }
    
    // ========== 缓存协调器 API ==========
    
    @GetMapping("/coordinator/stats")
    public ResponseEntity<CacheCoordinator.CoordinatorStats> getCoordinatorStats() {
        return ResponseEntity.ok(cacheCoordinator.getStats());
    }
    
    @PostMapping("/coordinator/invalidate")
    public ResponseEntity<String> invalidateAllLayers(@RequestParam String key) {
        cacheCoordinator.invalidateAll(key);
        return ResponseEntity.ok("Key invalidated from all layers: " + key);
    }
    
    @PostMapping("/coordinator/delayed-delete")
    public ResponseEntity<String> delayedDoubleDelete(
            @RequestParam String key,
            @RequestParam(defaultValue = "1000") long delayMs) {
        cacheCoordinator.delayedDoubleDelete(key, delayMs);
        return ResponseEntity.ok("Delayed double delete scheduled for: " + key);
    }
    
    // ========== 序列化器 API ==========
    
    @GetMapping("/serializer/stats")
    public ResponseEntity<ZeroCopySerializer.SerializerStats> getSerializerStats() {
        return ResponseEntity.ok(zeroCopySerializer.getStats());
    }
    
    @GetMapping("/serializer/compression-ratio")
    public ResponseEntity<Map<String, Object>> getCompressionRatio() {
        return ResponseEntity.ok(Map.of(
            "compressionRatio", zeroCopySerializer.getCompressionRatio(),
            "stats", zeroCopySerializer.getStats()
        ));
    }
    
    // ========== 访问预测器 API ==========
    
    @GetMapping("/predictor/stats")
    public ResponseEntity<AccessPredictor.PredictorStats> getPredictorStats() {
        return ResponseEntity.ok(accessPredictor.getStats());
    }
    
    @GetMapping("/predictor/hot-keys")
    public ResponseEntity<List<AccessPredictor.HotKeyInfo>> getHotKeys(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(accessPredictor.getHotKeys(limit));
    }
    
    @GetMapping("/predictor/cold-keys")
    public ResponseEntity<List<String>> getColdKeys(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "3600000") long maxLastAccessMs) {
        return ResponseEntity.ok(accessPredictor.getColdKeys(limit, maxLastAccessMs));
    }
    
    @GetMapping("/predictor/prefetch-candidates")
    public ResponseEntity<List<String>> getPrefetchCandidates(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(accessPredictor.getPrefetchCandidates(limit));
    }
    
    @GetMapping("/predictor/adaptive-ttl")
    public ResponseEntity<Map<String, Object>> getAdaptiveTtl(@RequestParam String key) {
        long ttl = accessPredictor.calculateAdaptiveTtl(key, 600);
        double probability = accessPredictor.predictAccessProbability(key);
        return ResponseEntity.ok(Map.of(
            "key", key,
            "recommendedTtl", ttl,
            "accessProbability", probability
        ));
    }
    
    @GetMapping("/predictor/associated-keys")
    public ResponseEntity<List<String>> getAssociatedKeys(@RequestParam String key) {
        return ResponseEntity.ok(accessPredictor.getAssociatedKeys(key));
    }
    
    // ========== 一致性哈希管理器 API ==========
    
    @GetMapping("/hash/stats")
    public ResponseEntity<ConsistentHashManager.HashManagerStats> getHashStats() {
        return ResponseEntity.ok(hashManager.getStats());
    }
    
    @GetMapping("/hash/nodes")
    public ResponseEntity<List<ConsistentHashManager.NodeInfo>> getNodes() {
        return ResponseEntity.ok(hashManager.getNodeInfos());
    }
    
    @GetMapping("/hash/load-distribution")
    public ResponseEntity<Map<String, Double>> getLoadDistribution() {
        return ResponseEntity.ok(hashManager.getLoadDistribution());
    }
    
    @PostMapping("/hash/add-node")
    public ResponseEntity<String> addNode(
            @RequestParam String nodeId,
            @RequestParam String address,
            @RequestParam(defaultValue = "1") int weight) {
        hashManager.addNode(nodeId, address, weight);
        return ResponseEntity.ok("Node added: " + nodeId);
    }
    
    @DeleteMapping("/hash/remove-node")
    public ResponseEntity<String> removeNode(@RequestParam String nodeId) {
        hashManager.removeNode(nodeId);
        return ResponseEntity.ok("Node removed: " + nodeId);
    }
    
    @PostMapping("/hash/rebalance")
    public ResponseEntity<String> rebalance() {
        hashManager.rebalance();
        return ResponseEntity.ok("Rebalance triggered");
    }
    
    @GetMapping("/hash/lookup")
    public ResponseEntity<Map<String, Object>> lookup(@RequestParam String key) {
        String node = hashManager.getNode(key);
        List<String> replicas = hashManager.getReplicaNodes(key);
        return ResponseEntity.ok(Map.of(
            "key", key,
            "primaryNode", node != null ? node : "N/A",
            "replicaNodes", replicas
        ));
    }
    
    @PostMapping("/hash/mark-unhealthy")
    public ResponseEntity<String> markNodeUnhealthy(@RequestParam String nodeId) {
        hashManager.markNodeUnhealthy(nodeId);
        return ResponseEntity.ok("Node marked unhealthy: " + nodeId);
    }
    
    @PostMapping("/hash/mark-healthy")
    public ResponseEntity<String> markNodeHealthy(@RequestParam String nodeId) {
        hashManager.markNodeHealthy(nodeId);
        return ResponseEntity.ok("Node marked healthy: " + nodeId);
    }
    
    // ========== 弹性保护器 API ==========
    
    @GetMapping("/resilience/stats")
    public ResponseEntity<AdaptiveResilienceGuard.ResilienceStats> getResilienceStats() {
        return ResponseEntity.ok(resilienceGuard.getStats());
    }
    
    @GetMapping("/resilience/circuit-breakers")
    public ResponseEntity<Map<String, AdaptiveResilienceGuard.CircuitBreakerStatus>> getCircuitBreakers() {
        return ResponseEntity.ok(resilienceGuard.getCircuitBreakerStatuses());
    }
    
    @GetMapping("/resilience/rate-limiters")
    public ResponseEntity<Map<String, AdaptiveResilienceGuard.RateLimiterStatus>> getRateLimiters() {
        return ResponseEntity.ok(resilienceGuard.getRateLimiterStatuses());
    }
    
    @GetMapping("/resilience/bulkheads")
    public ResponseEntity<Map<String, AdaptiveResilienceGuard.BulkheadStatus>> getBulkheads() {
        return ResponseEntity.ok(resilienceGuard.getBulkheadStatuses());
    }
    
    @PostMapping("/resilience/circuit-breaker/force-open")
    public ResponseEntity<String> forceOpenCircuitBreaker(@RequestParam String name) {
        resilienceGuard.forceOpen(name);
        return ResponseEntity.ok("Circuit breaker force opened: " + name);
    }
    
    @PostMapping("/resilience/circuit-breaker/force-close")
    public ResponseEntity<String> forceCloseCircuitBreaker(@RequestParam String name) {
        resilienceGuard.forceClose(name);
        return ResponseEntity.ok("Circuit breaker force closed: " + name);
    }
    
    @PostMapping("/resilience/configure/circuit-breaker")
    public ResponseEntity<String> configureCircuitBreaker(
            @RequestParam String name,
            @RequestParam int failureThreshold,
            @RequestParam int slowCallThreshold,
            @RequestParam long slowCallDurationMs) {
        resilienceGuard.configureCircuitBreaker(name, failureThreshold, slowCallThreshold, slowCallDurationMs);
        return ResponseEntity.ok("Circuit breaker configured: " + name);
    }
    
    @PostMapping("/resilience/configure/rate-limiter")
    public ResponseEntity<String> configureRateLimiter(
            @RequestParam String name,
            @RequestParam int qps) {
        resilienceGuard.configureRateLimiter(name, qps);
        return ResponseEntity.ok("Rate limiter configured: " + name);
    }
    
    // ========== 性能分析器 API ==========
    
    @GetMapping("/analyzer/stats")
    public ResponseEntity<RealTimePerformanceAnalyzer.AnalyzerStats> getAnalyzerStats() {
        return ResponseEntity.ok(performanceAnalyzer.getStats());
    }
    
    @GetMapping("/analyzer/snapshot")
    public ResponseEntity<RealTimePerformanceAnalyzer.PerformanceSnapshot> getPerformanceSnapshot() {
        return ResponseEntity.ok(performanceAnalyzer.getLatestSnapshot());
    }
    
    @GetMapping("/analyzer/anomalies")
    public ResponseEntity<List<RealTimePerformanceAnalyzer.AnomalyEvent>> getRecentAnomalies(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(performanceAnalyzer.getRecentAnomalies(limit));
    }
    
    @GetMapping("/analyzer/alerts")
    public ResponseEntity<List<RealTimePerformanceAnalyzer.AlertState>> getActiveAlerts() {
        return ResponseEntity.ok(performanceAnalyzer.getActiveAlerts());
    }
    
    @GetMapping("/analyzer/bottlenecks")
    public ResponseEntity<List<RealTimePerformanceAnalyzer.BottleneckInfo>> getBottlenecks() {
        return ResponseEntity.ok(performanceAnalyzer.analyzeBottlenecks());
    }
    
    @GetMapping("/analyzer/trend")
    public ResponseEntity<?> getTrend(@RequestParam String metric) {
        RealTimePerformanceAnalyzer.TrendAnalysis trend = performanceAnalyzer.analyzeTrend(metric);
        if (trend == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(trend);
    }
    
    @GetMapping("/analyzer/jvm")
    public ResponseEntity<Map<String, Object>> getJvmMetrics() {
        return ResponseEntity.ok(Map.of(
            "cpuUsage", performanceAnalyzer.getCpuUsage(),
            "heapUsageRatio", performanceAnalyzer.getHeapUsageRatio(),
            "gcPauseTotal", performanceAnalyzer.getTotalGcPauseTime(),
            "uptime", performanceAnalyzer.getUptime()
        ));
    }
    
    // ========== 综合诊断 ==========
    
    @GetMapping("/diagnosis")
    public ResponseEntity<Map<String, Object>> runDiagnosis() {
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        
        // 系统健康
        diagnosis.put("systemHealth", Map.of(
            "cpuUsage", performanceAnalyzer.getCpuUsage(),
            "memoryUsage", performanceAnalyzer.getHeapUsageRatio() * 100,
            "gcPauseTotal", performanceAnalyzer.getTotalGcPauseTime(),
            "uptime", performanceAnalyzer.getUptime()
        ));
        
        // 缓存效率
        diagnosis.put("cacheEfficiency", Map.of(
            "l0HitRate", ultraHotCache.getHitRate(),
            "serializerCompressionRatio", zeroCopySerializer.getCompressionRatio(),
            "predictorAccuracy", accessPredictor.getPredictionAccuracy(),
            "prefetchHitRate", accessPredictor.getPrefetchHitRate()
        ));
        
        // 弹性状态
        diagnosis.put("resilienceHealth", Map.of(
            "successRate", resilienceGuard.getSuccessRate(),
            "openCircuitBreakers", resilienceGuard.getCircuitBreakerStatuses().values().stream()
                .filter(s -> "OPEN".equals(s.state())).count()
        ));
        
        // 集群状态
        diagnosis.put("clusterHealth", Map.of(
            "nodeCount", hashManager.getStats().physicalNodeCount(),
            "isBalanced", hashManager.isBalanced(10.0)
        ));
        
        // 异常概况
        diagnosis.put("anomalies", Map.of(
            "recentCount", performanceAnalyzer.getRecentAnomalies(100).size(),
            "activeAlerts", performanceAnalyzer.getActiveAlerts().size(),
            "bottlenecks", performanceAnalyzer.analyzeBottlenecks()
        ));
        
        // 优化建议
        List<String> recommendations = generateRecommendations();
        diagnosis.put("recommendations", recommendations);
        
        return ResponseEntity.ok(diagnosis);
    }
    
    private List<String> generateRecommendations() {
        List<String> recommendations = new ArrayList<>();
        
        // 基于 L0 命中率
        if (ultraHotCache.getHitRate() < 0.1) {
            recommendations.add("考虑降低 L0 缓存升级阈值，提高超热点数据命中率");
        }
        
        // 基于预测准确率
        if (accessPredictor.getPredictionAccuracy() < 0.6) {
            recommendations.add("考虑增加访问历史窗口，提升预测准确率");
        }
        
        // 基于压缩比率
        if (zeroCopySerializer.getCompressionRatio() < 0.2) {
            recommendations.add("当前压缩效果较低，可考虑降低压缩阈值");
        }
        
        // 基于成功率
        if (resilienceGuard.getSuccessRate() < 0.95) {
            recommendations.add("系统成功率较低，检查依赖服务健康状态");
        }
        
        // 基于集群平衡
        if (!hashManager.isBalanced(15.0)) {
            recommendations.add("集群负载不均衡，建议执行 rebalance 操作");
        }
        
        // 基于内存使用
        if (performanceAnalyzer.getHeapUsageRatio() > 0.8) {
            recommendations.add("内存使用率较高，考虑增加堆内存或优化对象创建");
        }
        
        // 基于 CPU 使用
        if (performanceAnalyzer.getCpuUsage() > 80) {
            recommendations.add("CPU 使用率较高，考虑扩容或优化计算密集型代码");
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("系统运行正常，无特别优化建议");
        }
        
        return recommendations;
    }
    
    // ========== 高级缓存策略 API ==========
    
    @GetMapping("/cache-strategy/access-stats")
    public ResponseEntity<Map<String, AdvancedCacheStrategy.AccessStats>> getAccessStats(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(advancedCacheStrategy.getAccessStats(limit));
    }
    
    @GetMapping("/cache-strategy/smart-ttl")
    public ResponseEntity<Map<String, Object>> getSmartTtl(@RequestParam String key) {
        long ttl = advancedCacheStrategy.calculateSmartTtl(key);
        return ResponseEntity.ok(Map.of("key", key, "smartTtl", ttl));
    }
    
    // ========== 数据库优化器 API ==========
    
    @GetMapping("/database/report")
    public ResponseEntity<DatabaseOptimizer.OptimizationReport> getDatabaseReport() {
        return ResponseEntity.ok(databaseOptimizer.getOptimizationReport());
    }
}
