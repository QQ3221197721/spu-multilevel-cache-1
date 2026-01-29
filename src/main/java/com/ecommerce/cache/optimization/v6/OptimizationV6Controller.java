package com.ecommerce.cache.optimization.v6;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * V6 优化模块管理 API
 * 
 * 提供 V6 优化模块的运维管理接口:
 * - 状态监控
 * - 配置调整
 * - 健康检查
 * - 性能诊断
 */
@RestController
@RequestMapping("/api/v6/optimization")
public class OptimizationV6Controller {
    
    private static final Logger log = LoggerFactory.getLogger(OptimizationV6Controller.class);
    
    private final CacheCoordinatorV6 cacheCoordinator;
    private final LockFreeCacheEngine lockFreeEngine;
    private final AdaptiveMemoryManager memoryManager;
    private final PredictivePrefetchEngine prefetchEngine;
    private final DynamicHotspotMigrator hotspotMigrator;
    private final AdaptiveCircuitBreakerV6 circuitBreaker;
    private final IncrementalSyncEngine syncEngine;
    private final CacheHealthMonitorV6 healthMonitor;
    
    public OptimizationV6Controller(
            CacheCoordinatorV6 cacheCoordinator,
            LockFreeCacheEngine lockFreeEngine,
            AdaptiveMemoryManager memoryManager,
            PredictivePrefetchEngine prefetchEngine,
            DynamicHotspotMigrator hotspotMigrator,
            AdaptiveCircuitBreakerV6 circuitBreaker,
            IncrementalSyncEngine syncEngine,
            CacheHealthMonitorV6 healthMonitor) {
        this.cacheCoordinator = cacheCoordinator;
        this.lockFreeEngine = lockFreeEngine;
        this.memoryManager = memoryManager;
        this.prefetchEngine = prefetchEngine;
        this.hotspotMigrator = hotspotMigrator;
        this.circuitBreaker = circuitBreaker;
        this.syncEngine = syncEngine;
        this.healthMonitor = healthMonitor;
    }
    
    // ========== 综合状态 ==========
    
    /**
     * 获取 V6 优化系统整体状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getOverallStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        
        status.put("version", "V6 - Ultimate Performance Edition");
        status.put("health", healthMonitor.getOverallHealth());
        status.put("cacheStats", cacheCoordinator.getStats());
        status.put("memoryStats", memoryManager.getStats());
        status.put("circuitBreakerStats", circuitBreaker.getStats());
        status.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * 获取所有模块统计
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getAllStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        
        stats.put("coordinator", cacheCoordinator.getStats());
        stats.put("lockFree", lockFreeEngine.getStats());
        stats.put("memory", memoryManager.getStats());
        stats.put("prefetch", prefetchEngine.getStats());
        stats.put("hotspot", hotspotMigrator.getStats());
        stats.put("circuitBreaker", circuitBreaker.getStats());
        stats.put("sync", syncEngine.getStats());
        stats.put("health", healthMonitor.getHealthReport());
        
        return ResponseEntity.ok(stats);
    }
    
    // ========== 缓存协调器 ==========
    
    /**
     * 获取缓存协调器统计
     */
    @GetMapping("/coordinator/stats")
    public ResponseEntity<CacheCoordinatorV6.CacheStatsV6> getCoordinatorStats() {
        return ResponseEntity.ok(cacheCoordinator.getStats());
    }
    
    /**
     * 测试缓存读取
     */
    @GetMapping("/coordinator/test/{key}")
    public ResponseEntity<Map<String, Object>> testCacheGet(@PathVariable String key) {
        long start = System.nanoTime();
        String value = cacheCoordinator.get("test:" + key, () -> "test-value-" + key);
        long latency = (System.nanoTime() - start) / 1_000_000;
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("value", value);
        result.put("latencyMs", latency);
        result.put("stats", cacheCoordinator.getStats());
        
        return ResponseEntity.ok(result);
    }
    
    // ========== 无锁缓存 ==========
    
    /**
     * 获取无锁缓存统计
     */
    @GetMapping("/lock-free/stats")
    public ResponseEntity<LockFreeCacheEngine.LockFreeStats> getLockFreeStats() {
        return ResponseEntity.ok(lockFreeEngine.getStats());
    }
    
    /**
     * 清空无锁缓存
     */
    @PostMapping("/lock-free/clear")
    public ResponseEntity<Map<String, Object>> clearLockFreeCache() {
        lockFreeEngine.clear();
        log.info("Lock-free cache cleared");
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Lock-free cache cleared");
        result.put("stats", lockFreeEngine.getStats());
        
        return ResponseEntity.ok(result);
    }
    
    // ========== 内存管理 ==========
    
    /**
     * 获取内存统计
     */
    @GetMapping("/memory/stats")
    public ResponseEntity<AdaptiveMemoryManager.MemoryStats> getMemoryStats() {
        return ResponseEntity.ok(memoryManager.getStats());
    }
    
    /**
     * 获取内存压力级别
     */
    @GetMapping("/memory/pressure")
    public ResponseEntity<Map<String, Object>> getMemoryPressure() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("level", memoryManager.getCurrentLevel());
        result.put("utilization", memoryManager.getCurrentUtilization());
        result.put("trend", memoryManager.getMemoryTrend());
        result.put("recommendedEviction", memoryManager.getRecommendedEvictionCount());
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 强制GC
     */
    @PostMapping("/memory/gc")
    public ResponseEntity<Map<String, Object>> forceGc() {
        memoryManager.forceGcIfNeeded();
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "GC triggered if needed");
        result.put("stats", memoryManager.getStats());
        
        return ResponseEntity.ok(result);
    }
    
    // ========== 预取引擎 ==========
    
    /**
     * 获取预取统计
     */
    @GetMapping("/prefetch/stats")
    public ResponseEntity<PredictivePrefetchEngine.PrefetchStats> getPrefetchStats() {
        return ResponseEntity.ok(prefetchEngine.getStats());
    }
    
    // ========== 热点迁移 ==========
    
    /**
     * 获取热点统计
     */
    @GetMapping("/hotspot/stats")
    public ResponseEntity<DynamicHotspotMigrator.HotspotStats> getHotspotStats() {
        return ResponseEntity.ok(hotspotMigrator.getStats());
    }
    
    /**
     * 获取热点列表
     */
    @GetMapping("/hotspot/list")
    public ResponseEntity<List<DynamicHotspotMigrator.HotspotInfo>> getHotspotList() {
        return ResponseEntity.ok(hotspotMigrator.getHotspotList());
    }
    
    // ========== 熔断器 ==========
    
    /**
     * 获取熔断器统计
     */
    @GetMapping("/circuit-breaker/stats")
    public ResponseEntity<AdaptiveCircuitBreakerV6.CircuitBreakerStats> getCircuitBreakerStats() {
        return ResponseEntity.ok(circuitBreaker.getStats());
    }
    
    /**
     * 获取所有熔断器详情
     */
    @GetMapping("/circuit-breaker/details")
    public ResponseEntity<List<AdaptiveCircuitBreakerV6.BreakerDetail>> getCircuitBreakerDetails() {
        return ResponseEntity.ok(circuitBreaker.getAllBreakerDetails());
    }
    
    /**
     * 强制打开熔断器
     */
    @PostMapping("/circuit-breaker/{name}/open")
    public ResponseEntity<Map<String, Object>> forceOpenCircuitBreaker(@PathVariable String name) {
        circuitBreaker.forceOpen(name);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("name", name);
        result.put("state", circuitBreaker.getState(name));
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 强制关闭熔断器
     */
    @PostMapping("/circuit-breaker/{name}/close")
    public ResponseEntity<Map<String, Object>> forceCloseCircuitBreaker(@PathVariable String name) {
        circuitBreaker.forceClose(name);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("name", name);
        result.put("state", circuitBreaker.getState(name));
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 重置熔断器
     */
    @PostMapping("/circuit-breaker/{name}/reset")
    public ResponseEntity<Map<String, Object>> resetCircuitBreaker(@PathVariable String name) {
        circuitBreaker.reset(name);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("name", name);
        result.put("message", "Circuit breaker reset");
        
        return ResponseEntity.ok(result);
    }
    
    // ========== 同步引擎 ==========
    
    /**
     * 获取同步统计
     */
    @GetMapping("/sync/stats")
    public ResponseEntity<IncrementalSyncEngine.SyncStats> getSyncStats() {
        return ResponseEntity.ok(syncEngine.getStats());
    }
    
    /**
     * 获取同步状态
     */
    @GetMapping("/sync/status")
    public ResponseEntity<IncrementalSyncEngine.SyncStatus> getSyncStatus() {
        return ResponseEntity.ok(syncEngine.getSyncStatus());
    }
    
    /**
     * 强制同步
     */
    @PostMapping("/sync/force")
    public ResponseEntity<Map<String, Object>> forceSync() {
        syncEngine.forceSync();
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Force sync triggered");
        result.put("status", syncEngine.getSyncStatus());
        
        return ResponseEntity.ok(result);
    }
    
    // ========== 健康监测 ==========
    
    /**
     * 获取健康报告
     */
    @GetMapping("/health/report")
    public ResponseEntity<CacheHealthMonitorV6.HealthReport> getHealthReport() {
        return ResponseEntity.ok(healthMonitor.getHealthReport());
    }
    
    /**
     * 获取组件健康状态
     */
    @GetMapping("/health/components")
    public ResponseEntity<Map<String, CacheHealthMonitorV6.HealthStatus>> getComponentHealth() {
        return ResponseEntity.ok(healthMonitor.getComponentStatus());
    }
    
    /**
     * 获取最近告警
     */
    @GetMapping("/health/alerts")
    public ResponseEntity<List<CacheHealthMonitorV6.AlertRecord>> getRecentAlerts(
            @RequestParam(defaultValue = "20") int count) {
        return ResponseEntity.ok(healthMonitor.getRecentAlerts(count));
    }
    
    // ========== 性能诊断 ==========
    
    /**
     * 性能诊断
     */
    @GetMapping("/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose() {
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        
        // 整体健康
        diagnosis.put("overallHealth", healthMonitor.getOverallHealth());
        
        // 缓存命中率
        var cacheStats = cacheCoordinator.getStats();
        diagnosis.put("hitRate", cacheStats.totalHitRate());
        diagnosis.put("smartRouteRate", cacheStats.smartRouteRate());
        
        // 内存状况
        var memStats = memoryManager.getStats();
        diagnosis.put("memoryUtilization", memStats.utilization());
        diagnosis.put("memoryTrend", memStats.trend());
        
        // 熔断器状态
        var cbStats = circuitBreaker.getStats();
        diagnosis.put("openCircuitBreakers", cbStats.openBreakers());
        diagnosis.put("callSuccessRate", cbStats.successRate());
        
        // 预取效果
        var prefetchStats = prefetchEngine.getStats();
        diagnosis.put("prefetchAccuracy", prefetchStats.accuracy());
        
        // 同步状态
        var syncStatus = syncEngine.getSyncStatus();
        diagnosis.put("syncPending", syncStatus.pendingCount());
        diagnosis.put("syncHealthy", syncStatus.synced());
        
        // 诊断建议
        List<String> recommendations = generateRecommendations(
            cacheStats, memStats, cbStats, prefetchStats, syncStatus);
        diagnosis.put("recommendations", recommendations);
        
        return ResponseEntity.ok(diagnosis);
    }
    
    private List<String> generateRecommendations(
            CacheCoordinatorV6.CacheStatsV6 cacheStats,
            AdaptiveMemoryManager.MemoryStats memStats,
            AdaptiveCircuitBreakerV6.CircuitBreakerStats cbStats,
            PredictivePrefetchEngine.PrefetchStats prefetchStats,
            IncrementalSyncEngine.SyncStatus syncStatus) {
        
        List<String> recommendations = new ArrayList<>();
        
        if (cacheStats.totalHitRate() < 0.95) {
            recommendations.add("缓存命中率较低，建议检查预热策略或增加缓存容量");
        }
        
        if (memStats.utilization() > 0.85) {
            recommendations.add("内存使用率较高，建议增加内存或优化淘汰策略");
        }
        
        if (cbStats.openBreakers() > 0) {
            recommendations.add("存在开启的熔断器，建议检查下游服务健康状态");
        }
        
        if (prefetchStats.accuracy() < 0.6) {
            recommendations.add("预取准确率较低，建议调整预取阈值或学习窗口");
        }
        
        if (!syncStatus.synced()) {
            recommendations.add("同步队列积压，建议检查Redis连接或增加同步并发");
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("系统运行良好，无需特别调整");
        }
        
        return recommendations;
    }
    
    // ========== 基准测试 ==========
    
    /**
     * 运行简单基准测试
     */
    @PostMapping("/benchmark")
    public ResponseEntity<Map<String, Object>> runBenchmark(
            @RequestParam(defaultValue = "1000") int iterations) {
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iterations", iterations);
        
        // 写入测试
        long writeStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            cacheCoordinator.put("benchmark:key:" + i, "value:" + i);
        }
        long writeTime = (System.nanoTime() - writeStart) / 1_000_000;
        result.put("writeTimeMs", writeTime);
        result.put("writeOpsPerSec", iterations * 1000.0 / writeTime);
        
        // 读取测试
        long readStart = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < iterations; i++) {
            String value = cacheCoordinator.get("benchmark:key:" + i, () -> null);
            if (value != null) hits++;
        }
        long readTime = (System.nanoTime() - readStart) / 1_000_000;
        result.put("readTimeMs", readTime);
        result.put("readOpsPerSec", iterations * 1000.0 / readTime);
        result.put("hitRate", (double) hits / iterations);
        
        // 清理
        for (int i = 0; i < iterations; i++) {
            cacheCoordinator.invalidate("benchmark:key:" + i);
        }
        
        result.put("stats", cacheCoordinator.getStats());
        
        return ResponseEntity.ok(result);
    }
}
