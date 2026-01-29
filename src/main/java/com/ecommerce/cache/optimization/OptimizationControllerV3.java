package com.ecommerce.cache.optimization;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 优化模块管理API v3.0 - 统一优化功能入口
 * 
 * 提供全面的缓存优化功能管理接口，包括：
 * - 智能预取引擎管理
 * - 自适应TTL管理
 * - 分布式缓存同步
 * - 分片负载均衡
 * - 内存压力监控
 * - 链路追踪分析
 * - 批量写入优化
 */
@RestController
@RequestMapping("/api/v3/optimization")
@Tag(name = "Optimization V3", description = "缓存优化模块管理API v3.0")
public class OptimizationControllerV3 {
    
    private final SmartPrefetchEngine prefetchEngine;
    private final AdaptiveTTLManager ttlManager;
    private final DistributedCacheSyncProtocol syncProtocol;
    private final CacheShardLoadBalancer shardBalancer;
    private final MemoryPressureEvictionPolicy evictionPolicy;
    private final E2ETracingEnhancer tracingEnhancer;
    private final BatchAsyncWriteOptimizer writeOptimizer;
    private final ZeroGCRingBuffer ringBuffer;
    
    public OptimizationControllerV3(
            SmartPrefetchEngine prefetchEngine,
            AdaptiveTTLManager ttlManager,
            DistributedCacheSyncProtocol syncProtocol,
            CacheShardLoadBalancer shardBalancer,
            MemoryPressureEvictionPolicy evictionPolicy,
            E2ETracingEnhancer tracingEnhancer,
            BatchAsyncWriteOptimizer writeOptimizer,
            ZeroGCRingBuffer ringBuffer) {
        this.prefetchEngine = prefetchEngine;
        this.ttlManager = ttlManager;
        this.syncProtocol = syncProtocol;
        this.shardBalancer = shardBalancer;
        this.evictionPolicy = evictionPolicy;
        this.tracingEnhancer = tracingEnhancer;
        this.writeOptimizer = writeOptimizer;
        this.ringBuffer = ringBuffer;
    }
    
    // ==================== 综合概览 ====================
    
    @GetMapping("/dashboard")
    @Operation(summary = "获取优化模块仪表盘", description = "返回所有优化模块的综合状态")
    public ResponseEntity<DashboardResponse> getDashboard() {
        return ResponseEntity.ok(new DashboardResponse(
            prefetchEngine.getStats(),
            ttlManager.getStats(),
            syncProtocol.getStats(),
            shardBalancer.getStats(),
            evictionPolicy.getStats(),
            tracingEnhancer.getStats(),
            writeOptimizer.getStats(),
            ringBuffer.getStats()
        ));
    }
    
    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查所有优化模块健康状态")
    public ResponseEntity<HealthCheckResponse> healthCheck() {
        Map<String, ModuleHealth> moduleHealths = new LinkedHashMap<>();
        
        // 检查各模块状态
        moduleHealths.put("prefetch", checkPrefetchHealth());
        moduleHealths.put("ttl", checkTtlHealth());
        moduleHealths.put("sync", checkSyncHealth());
        moduleHealths.put("shard", checkShardHealth());
        moduleHealths.put("eviction", checkEvictionHealth());
        moduleHealths.put("tracing", checkTracingHealth());
        moduleHealths.put("batchWrite", checkBatchWriteHealth());
        moduleHealths.put("ringBuffer", checkRingBufferHealth());
        
        boolean allHealthy = moduleHealths.values().stream()
            .allMatch(h -> h.status() == HealthStatus.UP);
        
        return ResponseEntity.ok(new HealthCheckResponse(
            allHealthy ? HealthStatus.UP : HealthStatus.DEGRADED,
            moduleHealths,
            System.currentTimeMillis()
        ));
    }
    
    // ==================== 智能预取引擎 ====================
    
    @GetMapping("/prefetch/stats")
    @Operation(summary = "获取预取引擎统计")
    public ResponseEntity<SmartPrefetchEngine.PrefetchStats> getPrefetchStats() {
        return ResponseEntity.ok(prefetchEngine.getStats());
    }
    
    @PostMapping("/prefetch/record/{key}")
    @Operation(summary = "记录访问事件")
    public ResponseEntity<Void> recordPrefetchAccess(@PathVariable String key) {
        prefetchEngine.recordAccess(key);
        return ResponseEntity.ok().build();
    }
    
    @GetMapping("/prefetch/predict/{key}")
    @Operation(summary = "预测下一批访问")
    public ResponseEntity<List<String>> predictNextAccess(
            @PathVariable String key,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(prefetchEngine.predictNextAccess(key, limit));
    }
    
    @GetMapping("/prefetch/hotness/{key}")
    @Operation(summary = "获取key热度评分")
    public ResponseEntity<Map<String, Double>> getHotnessScore(@PathVariable String key) {
        return ResponseEntity.ok(Map.of("key", key, "score", prefetchEngine.getHotnessScore(key)));
    }
    
    // ==================== 自适应TTL管理 ====================
    
    @GetMapping("/ttl/stats")
    @Operation(summary = "获取TTL管理器统计")
    public ResponseEntity<AdaptiveTTLManager.TTLManagerStats> getTtlStats() {
        return ResponseEntity.ok(ttlManager.getStats());
    }
    
    @GetMapping("/ttl/calculate/{key}")
    @Operation(summary = "计算自适应TTL")
    public ResponseEntity<Map<String, Object>> calculateTtl(@PathVariable String key) {
        long ttl = ttlManager.calculateAdaptiveTTL(key);
        return ResponseEntity.ok(Map.of("key", key, "ttlSeconds", ttl));
    }
    
    @PostMapping("/ttl/strategy")
    @Operation(summary = "注册自定义TTL策略")
    public ResponseEntity<Void> registerTtlStrategy(
            @RequestParam String keyPrefix,
            @RequestBody AdaptiveTTLManager.TTLStrategy strategy) {
        ttlManager.registerStrategy(keyPrefix, strategy);
        return ResponseEntity.ok().build();
    }
    
    // ==================== 分布式缓存同步 ====================
    
    @GetMapping("/sync/stats")
    @Operation(summary = "获取同步协议统计")
    public ResponseEntity<DistributedCacheSyncProtocol.SyncStats> getSyncStats() {
        return ResponseEntity.ok(syncProtocol.getStats());
    }
    
    @PostMapping("/sync/node")
    @Operation(summary = "注册同步节点")
    public ResponseEntity<Void> registerSyncNode(
            @RequestParam String nodeId,
            @RequestParam String address) {
        syncProtocol.registerNode(nodeId, address);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/sync/node/{nodeId}")
    @Operation(summary = "注销同步节点")
    public ResponseEntity<Void> unregisterSyncNode(@PathVariable String nodeId) {
        syncProtocol.unregisterNode(nodeId);
        return ResponseEntity.ok().build();
    }
    
    @GetMapping("/sync/version/{key}")
    @Operation(summary = "获取key版本信息")
    public ResponseEntity<?> getVersionInfo(@PathVariable String key) {
        return syncProtocol.getVersionedEntry(key)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
    
    // ==================== 分片负载均衡 ====================
    
    @GetMapping("/shard/stats")
    @Operation(summary = "获取分片负载均衡统计")
    public ResponseEntity<CacheShardLoadBalancer.LoadBalancerStats> getShardStats() {
        return ResponseEntity.ok(shardBalancer.getStats());
    }
    
    @PostMapping("/shard")
    @Operation(summary = "添加分片节点")
    public ResponseEntity<Void> addShard(
            @RequestParam String shardId,
            @RequestParam String address,
            @RequestParam(defaultValue = "1") int weight) {
        shardBalancer.addShard(shardId, address, weight);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/shard/{shardId}")
    @Operation(summary = "移除分片节点")
    public ResponseEntity<Void> removeShard(@PathVariable String shardId) {
        shardBalancer.removeShard(shardId);
        return ResponseEntity.ok().build();
    }
    
    @GetMapping("/shard/route/{key}")
    @Operation(summary = "路由key到分片")
    public ResponseEntity<CacheShardLoadBalancer.RoutingResult> routeKey(@PathVariable String key) {
        return ResponseEntity.ok(shardBalancer.route(key));
    }
    
    @PostMapping("/shard/batch-route")
    @Operation(summary = "批量路由")
    public ResponseEntity<Map<String, List<String>>> batchRoute(@RequestBody List<String> keys) {
        return ResponseEntity.ok(shardBalancer.batchRoute(keys));
    }
    
    // ==================== 内存压力监控 ====================
    
    @GetMapping("/eviction/stats")
    @Operation(summary = "获取淘汰策略统计")
    public ResponseEntity<MemoryPressureEvictionPolicy.EvictionStats> getEvictionStats() {
        return ResponseEntity.ok(evictionPolicy.getStats());
    }
    
    @GetMapping("/eviction/memory")
    @Operation(summary = "获取内存使用情况")
    public ResponseEntity<Map<String, Object>> getMemoryStatus() {
        Runtime runtime = Runtime.getRuntime();
        return ResponseEntity.ok(Map.of(
            "heapUsage", evictionPolicy.getHeapUsageRatio(),
            "pressureLevel", evictionPolicy.getStats().pressureLevel(),
            "totalMemory", runtime.totalMemory(),
            "freeMemory", runtime.freeMemory(),
            "maxMemory", runtime.maxMemory()
        ));
    }
    
    // ==================== 链路追踪 ====================
    
    @GetMapping("/tracing/stats")
    @Operation(summary = "获取追踪统计")
    public ResponseEntity<E2ETracingEnhancer.TracingStats> getTracingStats() {
        return ResponseEntity.ok(tracingEnhancer.getStats());
    }
    
    @GetMapping("/tracing/slow-requests")
    @Operation(summary = "获取慢请求列表")
    public ResponseEntity<List<E2ETracingEnhancer.SlowRequest>> getSlowRequests(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(tracingEnhancer.getRecentSlowRequests(limit));
    }
    
    @GetMapping("/tracing/trace/{traceId}")
    @Operation(summary = "获取追踪详情")
    public ResponseEntity<?> getTraceDetail(@PathVariable String traceId) {
        return tracingEnhancer.getTrace(traceId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
    
    // ==================== 批量写入优化 ====================
    
    @GetMapping("/batch-write/stats")
    @Operation(summary = "获取批量写入统计")
    public ResponseEntity<BatchAsyncWriteOptimizer.BatchWriteStats> getBatchWriteStats() {
        return ResponseEntity.ok(writeOptimizer.getStats());
    }
    
    @PostMapping("/batch-write/flush")
    @Operation(summary = "刷新所有待处理写入")
    public ResponseEntity<Void> flushWrites() {
        writeOptimizer.flushAll();
        return ResponseEntity.ok().build();
    }
    
    // ==================== 零GC数据结构 ====================
    
    @GetMapping("/ring-buffer/stats")
    @Operation(summary = "获取环形缓冲区统计")
    public ResponseEntity<ZeroGCRingBuffer.RingBufferStats> getRingBufferStats() {
        return ResponseEntity.ok(ringBuffer.getStats());
    }
    
    @PostMapping("/ring-buffer/reset")
    @Operation(summary = "重置环形缓冲区")
    public ResponseEntity<Void> resetRingBuffer() {
        ringBuffer.reset();
        return ResponseEntity.ok().build();
    }
    
    // ==================== 性能测试 ====================
    
    @PostMapping("/benchmark/prefetch")
    @Operation(summary = "预取引擎性能测试")
    public ResponseEntity<BenchmarkResult> benchmarkPrefetch(
            @RequestParam(defaultValue = "10000") int iterations) {
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            prefetchEngine.recordAccess("benchmark:key:" + (i % 1000));
        }
        
        long duration = System.nanoTime() - startTime;
        double opsPerSecond = iterations * 1_000_000_000.0 / duration;
        
        return ResponseEntity.ok(new BenchmarkResult(
            "prefetch", iterations, duration / 1_000_000, opsPerSecond
        ));
    }
    
    @PostMapping("/benchmark/ttl")
    @Operation(summary = "TTL计算性能测试")
    public ResponseEntity<BenchmarkResult> benchmarkTtl(
            @RequestParam(defaultValue = "10000") int iterations) {
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            ttlManager.calculateAdaptiveTTL("benchmark:key:" + (i % 1000));
        }
        
        long duration = System.nanoTime() - startTime;
        double opsPerSecond = iterations * 1_000_000_000.0 / duration;
        
        return ResponseEntity.ok(new BenchmarkResult(
            "ttl", iterations, duration / 1_000_000, opsPerSecond
        ));
    }
    
    @PostMapping("/benchmark/routing")
    @Operation(summary = "分片路由性能测试")
    public ResponseEntity<BenchmarkResult> benchmarkRouting(
            @RequestParam(defaultValue = "10000") int iterations) {
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            shardBalancer.route("benchmark:key:" + i);
        }
        
        long duration = System.nanoTime() - startTime;
        double opsPerSecond = iterations * 1_000_000_000.0 / duration;
        
        return ResponseEntity.ok(new BenchmarkResult(
            "routing", iterations, duration / 1_000_000, opsPerSecond
        ));
    }
    
    @PostMapping("/benchmark/ring-buffer")
    @Operation(summary = "环形缓冲区性能测试")
    public ResponseEntity<BenchmarkResult> benchmarkRingBuffer(
            @RequestParam(defaultValue = "100000") int iterations) {
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            ringBuffer.publish("benchmark-event-" + i);
        }
        
        // 消费
        Object[] buffer = new Object[1000];
        while (ringBuffer.getPendingCount() > 0) {
            ringBuffer.consumeBatch(buffer, 1000);
        }
        
        long duration = System.nanoTime() - startTime;
        double opsPerSecond = iterations * 1_000_000_000.0 / duration;
        
        return ResponseEntity.ok(new BenchmarkResult(
            "ring-buffer", iterations, duration / 1_000_000, opsPerSecond
        ));
    }
    
    // ==================== 健康检查辅助方法 ====================
    
    private ModuleHealth checkPrefetchHealth() {
        try {
            var stats = prefetchEngine.getStats();
            return new ModuleHealth(HealthStatus.UP, Map.of(
                "enabled", stats.enabled(),
                "hitRate", stats.hitRate(),
                "queueSize", stats.queueSize()
            ));
        } catch (Exception e) {
            return new ModuleHealth(HealthStatus.DOWN, Map.of("error", e.getMessage()));
        }
    }
    
    private ModuleHealth checkTtlHealth() {
        try {
            var stats = ttlManager.getStats();
            return new ModuleHealth(HealthStatus.UP, Map.of(
                "enabled", stats.enabled(),
                "trackedKeys", stats.trackedKeyCount()
            ));
        } catch (Exception e) {
            return new ModuleHealth(HealthStatus.DOWN, Map.of("error", e.getMessage()));
        }
    }
    
    private ModuleHealth checkSyncHealth() {
        try {
            var stats = syncProtocol.getStats();
            return new ModuleHealth(HealthStatus.UP, Map.of(
                "enabled", stats.enabled(),
                "knownNodes", stats.knownNodesCount(),
                "pendingUpdates", stats.pendingUpdatesCount()
            ));
        } catch (Exception e) {
            return new ModuleHealth(HealthStatus.DOWN, Map.of("error", e.getMessage()));
        }
    }
    
    private ModuleHealth checkShardHealth() {
        try {
            var stats = shardBalancer.getStats();
            HealthStatus status = stats.loadImbalance() > 0.3 ? HealthStatus.DEGRADED : HealthStatus.UP;
            return new ModuleHealth(status, Map.of(
                "shardCount", stats.shardCount(),
                "loadImbalance", stats.loadImbalance()
            ));
        } catch (Exception e) {
            return new ModuleHealth(HealthStatus.DOWN, Map.of("error", e.getMessage()));
        }
    }
    
    private ModuleHealth checkEvictionHealth() {
        try {
            var stats = evictionPolicy.getStats();
            HealthStatus status = stats.pressureLevel() == MemoryPressureEvictionPolicy.PressureLevel.CRITICAL 
                ? HealthStatus.DEGRADED : HealthStatus.UP;
            return new ModuleHealth(status, Map.of(
                "enabled", stats.enabled(),
                "pressureLevel", stats.pressureLevel().name(),
                "heapUsage", stats.heapUsage()
            ));
        } catch (Exception e) {
            return new ModuleHealth(HealthStatus.DOWN, Map.of("error", e.getMessage()));
        }
    }
    
    private ModuleHealth checkTracingHealth() {
        try {
            var stats = tracingEnhancer.getStats();
            return new ModuleHealth(HealthStatus.UP, Map.of(
                "enabled", stats.enabled(),
                "activeTraces", stats.activeTraces(),
                "slowRequests", stats.slowRequests()
            ));
        } catch (Exception e) {
            return new ModuleHealth(HealthStatus.DOWN, Map.of("error", e.getMessage()));
        }
    }
    
    private ModuleHealth checkBatchWriteHealth() {
        try {
            var stats = writeOptimizer.getStats();
            HealthStatus status = stats.backpressureActive() ? HealthStatus.DEGRADED : HealthStatus.UP;
            return new ModuleHealth(status, Map.of(
                "enabled", stats.enabled(),
                "backpressure", stats.backpressureActive(),
                "pendingWrites", stats.pendingCount()
            ));
        } catch (Exception e) {
            return new ModuleHealth(HealthStatus.DOWN, Map.of("error", e.getMessage()));
        }
    }
    
    private ModuleHealth checkRingBufferHealth() {
        try {
            var stats = ringBuffer.getStats();
            HealthStatus status = stats.usageRate() > 0.9 ? HealthStatus.DEGRADED : HealthStatus.UP;
            return new ModuleHealth(status, Map.of(
                "capacity", stats.capacity(),
                "usageRate", stats.usageRate(),
                "overflow", stats.overflowCount()
            ));
        } catch (Exception e) {
            return new ModuleHealth(HealthStatus.DOWN, Map.of("error", e.getMessage()));
        }
    }
    
    // ==================== DTO ====================
    
    public record DashboardResponse(
        SmartPrefetchEngine.PrefetchStats prefetch,
        AdaptiveTTLManager.TTLManagerStats ttl,
        DistributedCacheSyncProtocol.SyncStats sync,
        CacheShardLoadBalancer.LoadBalancerStats shard,
        MemoryPressureEvictionPolicy.EvictionStats eviction,
        E2ETracingEnhancer.TracingStats tracing,
        BatchAsyncWriteOptimizer.BatchWriteStats batchWrite,
        ZeroGCRingBuffer.RingBufferStats ringBuffer
    ) {}
    
    public enum HealthStatus { UP, DEGRADED, DOWN }
    
    public record ModuleHealth(HealthStatus status, Map<String, Object> details) {}
    
    public record HealthCheckResponse(
        HealthStatus overallStatus,
        Map<String, ModuleHealth> modules,
        long timestamp
    ) {}
    
    public record BenchmarkResult(
        String module,
        int iterations,
        long durationMs,
        double opsPerSecond
    ) {}
}
