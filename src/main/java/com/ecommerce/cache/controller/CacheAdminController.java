package com.ecommerce.cache.controller;

import com.ecommerce.cache.dto.ApiResponse;
import com.ecommerce.cache.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缓存运维管理 API - 增强版
 * 提供：
 * 1. 缓存统计与诊断
 * 2. 热点 Key 管理
 * 3. 熔断器控制
 * 4. 预加载管理
 * 5. Insight 诊断
 */
@Slf4j
@Tag(name = "缓存管理", description = "缓存运维管理：统计、清除、布隆过滤器、熔断器、诊断")
@RestController
@RequestMapping("/api/cache/admin")
@RequiredArgsConstructor
public class CacheAdminController {
    
    private final L1CacheService l1CacheService;
    private final L2RedisService l2RedisService;
    private final L3MemcachedService l3MemcachedService;
    private final MultiLevelCacheService multiLevelCacheService;
    private final BloomFilterService bloomFilterService;
    private final HotKeyDetectorService hotKeyDetectorService;
    private final CacheResilienceService resilienceService;
    private final CacheInsightService insightService;
    private final CachePreloadService preloadService;
    private final ParallelCacheReader parallelCacheReader;
    
    /**
     * 获取缓存统计信息（增强版）
     */
    @Operation(summary = "获取缓存统计", description = "返回全部缓存层详细统计：L1/多级/布隆过滤器/热点Key/弹性保护/并行读取/预加载")
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // L1 详细统计
        stats.put("l1", l1CacheService.getDetailedStats());
        
        // 多级缓存统计
        stats.put("multilevel", multiLevelCacheService.getStats());
        
        // 布隆过滤器统计
        stats.put("bloomFilter", bloomFilterService.getStats());
        
        // 热点 Key 统计
        stats.put("hotKeys", hotKeyDetectorService.getStats());
        
        // 弹性保护统计
        stats.put("resilience", resilienceService.getStats());
        
        // 并行读取统计
        stats.put("parallelReader", parallelCacheReader.getStats());
        
        // 预加载统计
        stats.put("preload", preloadService.getStats());
        
        return ApiResponse.success(stats);
    }
    
    /**
     * 手动清除指定 SPU 缓存
     */
    @Operation(summary = "清除单个 SPU 缓存", description = "清除指定 SPU ID 在所有缓存层的数据")
    @DeleteMapping("/invalidate/{spuId}")
    public ApiResponse<Void> invalidateCache(
            @Parameter(description = "SPU ID") @PathVariable Long spuId) {
        log.info("Manual cache invalidation for SPU: {}", spuId);
        
        String key = "spu:detail:" + spuId;
        multiLevelCacheService.invalidate(key);
        
        return ApiResponse.success();
    }
    
    /**
     * 批量清除缓存
     */
    @Operation(summary = "批量清除缓存", description = "批量清除多个 SPU ID 的缓存数据，返回成功清除数量")
    @PostMapping("/invalidate/batch")
    public ApiResponse<Integer> batchInvalidateCache(@RequestBody Set<Long> spuIds) {
        log.info("Manual batch cache invalidation for {} SPUs", spuIds.size());
        
        int count = 0;
        for (Long spuId : spuIds) {
            try {
                String key = "spu:detail:" + spuId;
                multiLevelCacheService.invalidate(key);
                count++;
            } catch (Exception e) {
                log.error("Failed to invalidate cache for SPU: {}", spuId, e);
            }
        }
        
        return ApiResponse.success(count);
    }
    
    /**
     * 清空 L1 本地缓存
     */
    @Operation(summary = "清空 L1 本地缓存", description = "清空当前实例的 Caffeine L1 本地缓存，影响当前节点")
    @DeleteMapping("/l1/clear")
    public ApiResponse<Void> clearL1Cache() {
        log.warn("Manual L1 cache clear triggered!");
        l1CacheService.invalidateAll();
        return ApiResponse.success();
    }
    
    /**
     * 添加 SPU 到布隆过滤器
     */
    @Operation(summary = "添加到布隆过滤器", description = "将指定 SPU ID 添加到布隆过滤器，用于穿透防护")
    @PostMapping("/bloom/add/{spuId}")
    public ApiResponse<Void> addToBloomFilter(
            @Parameter(description = "SPU ID") @PathVariable Long spuId) {
        bloomFilterService.add(spuId);
        return ApiResponse.success();
    }
    
    /**
     * 检查 SPU 是否在布隆过滤器中
     */
    @Operation(summary = "检查布隆过滤器", description = "检查 SPU ID 是否可能存在于布隆过滤器中（可能有假阳性）")
    @GetMapping("/bloom/check/{spuId}")
    public ApiResponse<Boolean> checkBloomFilter(
            @Parameter(description = "SPU ID") @PathVariable Long spuId) {
        boolean exists = bloomFilterService.mightContain(spuId);
        return ApiResponse.success(exists);
    }
    
    /**
     * 手动标记热点 Key
     */
    @Operation(summary = "标记热点 Key", description = "手动将 SPU 标记为热点 Key，触发热点分片保护")
    @PostMapping("/hotkey/mark/{spuId}")
    public ApiResponse<Void> markHotKey(
            @Parameter(description = "SPU ID") @PathVariable Long spuId) {
        String key = "spu:detail:" + spuId;
        hotKeyDetectorService.markAsHotKey(key);
        return ApiResponse.success();
    }
    
    /**
     * 获取热点 Key 列表
     */
    @Operation(summary = "获取热点 Key 列表", description = "返回当前所有被标记为热点的缓存 Key 集合")
    @GetMapping("/hotkey/list")
    public ApiResponse<Set<String>> getHotKeys() {
        return ApiResponse.success(hotKeyDetectorService.getAllHotKeys());
    }
    
    // ==================== 新增诊断接口 ====================
    
    /**
     * 执行全面诊断
     */
    @Operation(summary = "执行全面诊断", description = "触发缓存系统全面诊断，检测潜在问题并生成报告")
    @PostMapping("/diagnostic/run")
    public ApiResponse<CacheInsightService.DiagnosticReport> runDiagnostic() {
        log.info("Manual diagnostic triggered");
        return ApiResponse.success(insightService.runDiagnostic());
    }
    
    /**
     * 获取诊断历史
     */
    @Operation(summary = "获取诊断历史", description = "返回历史诊断报告列表")
    @GetMapping("/diagnostic/history")
    public ApiResponse<List<CacheInsightService.DiagnosticReport>> getDiagnosticHistory(
            @Parameter(description = "返回数量上限") @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(insightService.getDiagnosticHistory(limit));
    }
    
    /**
     * 获取慢查询列表
     */
    @Operation(summary = "获取慢查询列表", description = "返回缓存慢查询记录，用于性能分析与优化")
    @GetMapping("/insight/slow-queries")
    public ApiResponse<List<CacheInsightService.SlowQueryRecord>> getSlowQueries(
            @Parameter(description = "返回数量上限") @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(insightService.getSlowQueries(limit));
    }
    
    /**
     * 获取异常 Key 列表
     */
    @Operation(summary = "获取异常 Key", description = "返回访问模式异常的缓存 Key 列表")
    @GetMapping("/insight/anomaly-keys")
    public ApiResponse<List<CacheInsightService.AnomalyRecord>> getAnomalyKeys(
            @Parameter(description = "返回数量上限") @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(insightService.getAnomalyKeys(limit));
    }
    
    // ==================== 熔断器控制 ====================
    
    /**
     * 重置熔断器
     */
    @Operation(summary = "重置熔断器", description = "手动重置指定名称的熔断器，恢复服务访问")
    @PostMapping("/circuit-breaker/reset/{name}")
    public ApiResponse<Void> resetCircuitBreaker(
            @Parameter(description = "熔断器名称") @PathVariable String name) {
        log.warn("Manual circuit breaker reset: {}", name);
        resilienceService.resetCircuitBreaker(name);
        return ApiResponse.success();
    }
    
    /**
     * 强制开启熔断器
     */
    @Operation(summary = "强制开启熔断器", description = "手动强制开启指定熔断器，立即切断对应服务的访问")
    @PostMapping("/circuit-breaker/force-open/{name}")
    public ApiResponse<Void> forceOpenCircuitBreaker(
            @Parameter(description = "熔断器名称") @PathVariable String name) {
        log.warn("Manual circuit breaker force open: {}", name);
        resilienceService.forceOpenCircuitBreaker(name);
        return ApiResponse.success();
    }
    
    /**
     * 获取熔断器状态
     */
    @Operation(summary = "获取熔断器状态", description = "查看当前全部熔断器的状态、失败率、调用次数等详细信息")
    @GetMapping("/circuit-breaker/status")
    public ApiResponse<CacheResilienceService.ResilienceStats> getCircuitBreakerStatus() {
        return ApiResponse.success(resilienceService.getStats());
    }
    
    // ==================== 预加载控制 ====================
    
    /**
     * 执行手动预热
     */
    @Operation(summary = "执行手动预热", description = "对指定 SPU ID 集合执行缓存预热，返回成功预热数量")
    @PostMapping("/preload/execute")
    public ApiResponse<Integer> executePreload(@RequestBody Set<Long> spuIds) {
        log.info("Manual preload triggered for {} SPUs", spuIds.size());
        
        List<String> keys = spuIds.stream()
            .map(id -> "spu:detail:" + id)
            .toList();
        
        // 这里需要一个数据加载函数，实际应用中应调用 SpuService
        int count = preloadService.batchPreload(keys, k -> null, 600);
        
        return ApiResponse.success(count);
    }
    
    /**
     * 获取预加载统计
     */
    @Operation(summary = "获取预加载统计", description = "返回缓存预加载的历史统计数据")
    @GetMapping("/preload/stats")
    public ApiResponse<CachePreloadService.PreloadStats> getPreloadStats() {
        return ApiResponse.success(preloadService.getStats());
    }
}
