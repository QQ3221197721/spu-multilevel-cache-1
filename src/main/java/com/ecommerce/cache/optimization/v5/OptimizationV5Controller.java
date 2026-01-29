package com.ecommerce.cache.optimization.v5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V5 优化模块监控与管理 API
 * 
 * 提供：
 * 1. 实时监控数据
 * 2. 热点 Key 管理
 * 3. 分片管理
 * 4. 流量控制
 * 5. 动态配置调整
 */
@RestController
@RequestMapping("/api/v5/optimization")
public class OptimizationV5Controller {
    
    private static final Logger log = LoggerFactory.getLogger(OptimizationV5Controller.class);
    
    private final EnhancedMultiLevelCacheV5 cacheService;
    private final HeavyKeeperHotKeyDetector hotKeyDetector;
    private final SmartShardRouter shardRouter;
    private final TrafficShapingService trafficShaping;
    private final SmartCacheWarmer cacheWarmer;
    private final DatabaseAccessOptimizer databaseOptimizer;
    
    public OptimizationV5Controller(
            EnhancedMultiLevelCacheV5 cacheService,
            HeavyKeeperHotKeyDetector hotKeyDetector,
            SmartShardRouter shardRouter,
            TrafficShapingService trafficShaping,
            SmartCacheWarmer cacheWarmer,
            DatabaseAccessOptimizer databaseOptimizer) {
        this.cacheService = cacheService;
        this.hotKeyDetector = hotKeyDetector;
        this.shardRouter = shardRouter;
        this.trafficShaping = trafficShaping;
        this.cacheWarmer = cacheWarmer;
        this.databaseOptimizer = databaseOptimizer;
    }
    
    // ==================== 综合统计 ====================
    
    /**
     * 获取完整的 V5 统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<EnhancedMultiLevelCacheV5.CacheStatsV5> getStats() {
        return ResponseEntity.ok(cacheService.getStats());
    }
    
    /**
     * 获取系统健康摘要
     */
    @GetMapping("/health/summary")
    public ResponseEntity<Map<String, Object>> getHealthSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        var cacheStats = cacheService.getStats();
        var hotKeyStats = hotKeyDetector.getStats();
        var trafficStatus = trafficShaping.getStatus();
        var warmupStats = cacheWarmer.getStats();
        var dbStats = databaseOptimizer.getStats();
        
        // 综合健康分数（0-100）
        int healthScore = calculateHealthScore(cacheStats, trafficStatus, dbStats);
        
        summary.put("healthScore", healthScore);
        summary.put("status", healthScore >= 80 ? "HEALTHY" : healthScore >= 60 ? "WARNING" : "CRITICAL");
        summary.put("cacheHitRate", String.format("%.2f%%", cacheStats.totalHitRate() * 100));
        summary.put("l1HitRate", String.format("%.2f%%", cacheStats.l1HitRate() * 100));
        summary.put("hotKeyCount", hotKeyStats.confirmedHotKeyCount());
        summary.put("trafficAcceptanceRate", String.format("%.2f%%", trafficStatus.acceptanceRate() * 100));
        summary.put("prefetchHitRate", String.format("%.2f%%", warmupStats.prefetchHitRate() * 100));
        summary.put("dbAvgLatencyMs", dbStats.avgQueryTimeMs());
        summary.put("activeConnections", dbStats.poolStatus().activeConnections());
        
        return ResponseEntity.ok(summary);
    }
    
    // ==================== 热点 Key 管理 ====================
    
    /**
     * 获取热点 Key 统计
     */
    @GetMapping("/hotkey/stats")
    public ResponseEntity<HeavyKeeperHotKeyDetector.HeavyKeeperStats> getHotKeyStats() {
        return ResponseEntity.ok(hotKeyDetector.getStats());
    }
    
    /**
     * 获取 Top-K 热点 Key
     */
    @GetMapping("/hotkey/top")
    public ResponseEntity<List<HeavyKeeperHotKeyDetector.HotKeyEntry>> getTopHotKeys(
            @RequestParam(defaultValue = "20") int k) {
        return ResponseEntity.ok(hotKeyDetector.getTopKHotKeys(k));
    }
    
    /**
     * 获取所有热点 Key
     */
    @GetMapping("/hotkey/all")
    public ResponseEntity<Set<String>> getAllHotKeys() {
        return ResponseEntity.ok(hotKeyDetector.getHotKeys());
    }
    
    /**
     * 查询 Key 热度
     */
    @GetMapping("/hotkey/query")
    public ResponseEntity<Map<String, Object>> queryKeyHotness(@RequestParam String key) {
        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        result.put("count", hotKeyDetector.query(key));
        result.put("isHotKey", hotKeyDetector.isHotKey(key));
        return ResponseEntity.ok(result);
    }
    
    /**
     * 手动标记热点 Key
     */
    @PostMapping("/hotkey/mark")
    public ResponseEntity<String> markHotKey(@RequestParam String key) {
        hotKeyDetector.markAsHotKey(key);
        log.info("Manually marked hot key: {}", key);
        return ResponseEntity.ok("Hot key marked: " + key);
    }
    
    /**
     * 手动取消热点标记
     */
    @DeleteMapping("/hotkey/unmark")
    public ResponseEntity<String> unmarkHotKey(@RequestParam String key) {
        hotKeyDetector.unmarkHotKey(key);
        log.info("Manually unmarked hot key: {}", key);
        return ResponseEntity.ok("Hot key unmarked: " + key);
    }
    
    /**
     * 重置 HeavyKeeper
     */
    @PostMapping("/hotkey/reset")
    public ResponseEntity<String> resetHotKeyDetector() {
        hotKeyDetector.reset();
        log.info("HeavyKeeper detector reset");
        return ResponseEntity.ok("HeavyKeeper reset completed");
    }
    
    // ==================== 分片管理 ====================
    
    /**
     * 获取分片统计
     */
    @GetMapping("/shard/stats")
    public ResponseEntity<SmartShardRouter.ShardGroupStats> getShardStats(@RequestParam String key) {
        return ResponseEntity.ok(shardRouter.getShardStats(key));
    }
    
    /**
     * 设置分片数
     */
    @PostMapping("/shard/set")
    public ResponseEntity<String> setShardCount(
            @RequestParam String key,
            @RequestParam int count) {
        shardRouter.setShardCount(key, count);
        log.info("Shard count set: key={}, count={}", key, count);
        return ResponseEntity.ok("Shard count set to " + count + " for key: " + key);
    }
    
    /**
     * 标记分片不健康
     */
    @PostMapping("/shard/mark-unhealthy")
    public ResponseEntity<String> markShardUnhealthy(
            @RequestParam String key,
            @RequestParam int shardIndex) {
        shardRouter.markShardUnhealthy(key, shardIndex);
        log.info("Shard marked unhealthy: key={}, shard={}", key, shardIndex);
        return ResponseEntity.ok("Shard " + shardIndex + " marked unhealthy for key: " + key);
    }
    
    // ==================== 流量控制 ====================
    
    /**
     * 获取流量状态
     */
    @GetMapping("/traffic/status")
    public ResponseEntity<TrafficShapingService.TrafficStatus> getTrafficStatus() {
        return ResponseEntity.ok(trafficShaping.getStatus());
    }
    
    /**
     * 获取流量预测
     */
    @GetMapping("/traffic/prediction")
    public ResponseEntity<TrafficShapingService.TrafficPrediction> getTrafficPrediction() {
        return ResponseEntity.ok(trafficShaping.getPrediction());
    }
    
    /**
     * 调整流量限制
     */
    @PostMapping("/traffic/adjust")
    public ResponseEntity<String> adjustTrafficLimits(
            @RequestParam int leakyRate,
            @RequestParam int tokenCapacity) {
        trafficShaping.adjustLimits(leakyRate, tokenCapacity);
        log.info("Traffic limits adjusted: leakyRate={}, tokenCapacity={}", leakyRate, tokenCapacity);
        return ResponseEntity.ok("Traffic limits adjusted");
    }
    
    // ==================== 缓存预热 ====================
    
    /**
     * 获取预热统计
     */
    @GetMapping("/warmer/stats")
    public ResponseEntity<SmartCacheWarmer.WarmupStats> getWarmerStats() {
        return ResponseEntity.ok(cacheWarmer.getStats());
    }
    
    /**
     * 手动触发预热任务
     */
    @PostMapping("/warmer/trigger")
    public ResponseEntity<String> triggerWarmup(@RequestParam String key) {
        cacheWarmer.submitWarmupTask(key, 1.0, () -> {
            log.info("Manual warmup triggered for key: {}", key);
        });
        return ResponseEntity.ok("Warmup task submitted for key: " + key);
    }
    
    // ==================== 数据库优化 ====================
    
    /**
     * 获取数据库统计
     */
    @GetMapping("/database/stats")
    public ResponseEntity<DatabaseAccessOptimizer.DatabaseStats> getDatabaseStats() {
        return ResponseEntity.ok(databaseOptimizer.getStats());
    }
    
    /**
     * 获取连接池状态
     */
    @GetMapping("/database/pool")
    public ResponseEntity<DatabaseAccessOptimizer.ConnectionPoolStatus> getPoolStatus() {
        return ResponseEntity.ok(databaseOptimizer.getPoolStatus());
    }
    
    /**
     * 获取慢查询日志
     */
    @GetMapping("/database/slow-queries")
    public ResponseEntity<List<DatabaseAccessOptimizer.SlowQuery>> getSlowQueries(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(databaseOptimizer.getSlowQueryLog(limit));
    }
    
    /**
     * 获取查询统计
     */
    @GetMapping("/database/query-stats")
    public ResponseEntity<Map<String, DatabaseAccessOptimizer.QueryStats>> getQueryStats() {
        return ResponseEntity.ok(databaseOptimizer.getQueryStats());
    }
    
    // ==================== 缓存操作 ====================
    
    /**
     * 获取缓存值
     */
    @GetMapping("/cache/get")
    public ResponseEntity<Map<String, Object>> getCacheValue(@RequestParam String key) {
        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        
        String value = cacheService.get(key, () -> null);
        result.put("value", value);
        result.put("exists", value != null);
        result.put("isHotKey", hotKeyDetector.isHotKey(key));
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 写入缓存
     */
    @PostMapping("/cache/put")
    public ResponseEntity<String> putCacheValue(
            @RequestParam String key,
            @RequestParam String value,
            @RequestParam(defaultValue = "600") long ttlSeconds) {
        cacheService.put(key, value, ttlSeconds);
        return ResponseEntity.ok("Cache put: key=" + key + ", ttl=" + ttlSeconds + "s");
    }
    
    /**
     * 删除缓存
     */
    @DeleteMapping("/cache/invalidate")
    public ResponseEntity<String> invalidateCache(@RequestParam String key) {
        cacheService.invalidate(key);
        return ResponseEntity.ok("Cache invalidated: " + key);
    }
    
    // ==================== 私有方法 ====================
    
    private int calculateHealthScore(
            EnhancedMultiLevelCacheV5.CacheStatsV5 cacheStats,
            TrafficShapingService.TrafficStatus trafficStatus,
            DatabaseAccessOptimizer.DatabaseStats dbStats) {
        
        int score = 100;
        
        // 缓存命中率权重 40%
        if (cacheStats.totalHitRate() < 0.95) score -= 10;
        if (cacheStats.totalHitRate() < 0.90) score -= 10;
        if (cacheStats.totalHitRate() < 0.80) score -= 20;
        
        // 流量接受率权重 30%
        if (trafficStatus.acceptanceRate() < 0.99) score -= 5;
        if (trafficStatus.acceptanceRate() < 0.95) score -= 10;
        if (trafficStatus.acceptanceRate() < 0.90) score -= 15;
        
        // 数据库性能权重 30%
        if (dbStats.avgQueryTimeMs() > 50) score -= 5;
        if (dbStats.avgQueryTimeMs() > 100) score -= 10;
        if (dbStats.avgQueryTimeMs() > 200) score -= 15;
        
        // 连接池使用率
        var pool = dbStats.poolStatus();
        double poolUsage = (double) pool.activeConnections() / pool.maxPoolSize();
        if (poolUsage > 0.8) score -= 5;
        if (poolUsage > 0.9) score -= 10;
        
        return Math.max(0, score);
    }
}
