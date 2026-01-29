package com.ecommerce.cache.controller;

import com.ecommerce.cache.dto.ApiResponse;
import com.ecommerce.cache.service.*;
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
    @DeleteMapping("/invalidate/{spuId}")
    public ApiResponse<Void> invalidateCache(@PathVariable Long spuId) {
        log.info("Manual cache invalidation for SPU: {}", spuId);
        
        String key = "spu:detail:" + spuId;
        multiLevelCacheService.invalidate(key);
        
        return ApiResponse.success();
    }
    
    /**
     * 批量清除缓存
     */
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
    @DeleteMapping("/l1/clear")
    public ApiResponse<Void> clearL1Cache() {
        log.warn("Manual L1 cache clear triggered!");
        l1CacheService.invalidateAll();
        return ApiResponse.success();
    }
    
    /**
     * 添加 SPU 到布隆过滤器
     */
    @PostMapping("/bloom/add/{spuId}")
    public ApiResponse<Void> addToBloomFilter(@PathVariable Long spuId) {
        bloomFilterService.add(spuId);
        return ApiResponse.success();
    }
    
    /**
     * 检查 SPU 是否在布隆过滤器中
     */
    @GetMapping("/bloom/check/{spuId}")
    public ApiResponse<Boolean> checkBloomFilter(@PathVariable Long spuId) {
        boolean exists = bloomFilterService.mightContain(spuId);
        return ApiResponse.success(exists);
    }
    
    /**
     * 手动标记热点 Key
     */
    @PostMapping("/hotkey/mark/{spuId}")
    public ApiResponse<Void> markHotKey(@PathVariable Long spuId) {
        String key = "spu:detail:" + spuId;
        hotKeyDetectorService.markAsHotKey(key);
        return ApiResponse.success();
    }
    
    /**
     * 获取热点 Key 列表
     */
    @GetMapping("/hotkey/list")
    public ApiResponse<Set<String>> getHotKeys() {
        return ApiResponse.success(hotKeyDetectorService.getAllHotKeys());
    }
    
    // ==================== 新增诊断接口 ====================
    
    /**
     * 执行全面诊断
     */
    @PostMapping("/diagnostic/run")
    public ApiResponse<CacheInsightService.DiagnosticReport> runDiagnostic() {
        log.info("Manual diagnostic triggered");
        return ApiResponse.success(insightService.runDiagnostic());
    }
    
    /**
     * 获取诊断历史
     */
    @GetMapping("/diagnostic/history")
    public ApiResponse<List<CacheInsightService.DiagnosticReport>> getDiagnosticHistory(
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(insightService.getDiagnosticHistory(limit));
    }
    
    /**
     * 获取慢查询列表
     */
    @GetMapping("/insight/slow-queries")
    public ApiResponse<List<CacheInsightService.SlowQueryRecord>> getSlowQueries(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(insightService.getSlowQueries(limit));
    }
    
    /**
     * 获取异常 Key 列表
     */
    @GetMapping("/insight/anomaly-keys")
    public ApiResponse<List<CacheInsightService.AnomalyRecord>> getAnomalyKeys(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(insightService.getAnomalyKeys(limit));
    }
    
    // ==================== 熔断器控制 ====================
    
    /**
     * 重置熔断器
     */
    @PostMapping("/circuit-breaker/reset/{name}")
    public ApiResponse<Void> resetCircuitBreaker(@PathVariable String name) {
        log.warn("Manual circuit breaker reset: {}", name);
        resilienceService.resetCircuitBreaker(name);
        return ApiResponse.success();
    }
    
    /**
     * 强制开启熔断器
     */
    @PostMapping("/circuit-breaker/force-open/{name}")
    public ApiResponse<Void> forceOpenCircuitBreaker(@PathVariable String name) {
        log.warn("Manual circuit breaker force open: {}", name);
        resilienceService.forceOpenCircuitBreaker(name);
        return ApiResponse.success();
    }
    
    /**
     * 获取熔断器状态
     */
    @GetMapping("/circuit-breaker/status")
    public ApiResponse<CacheResilienceService.ResilienceStats> getCircuitBreakerStatus() {
        return ApiResponse.success(resilienceService.getStats());
    }
    
    // ==================== 预加载控制 ====================
    
    /**
     * 执行手动预热
     */
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
    @GetMapping("/preload/stats")
    public ApiResponse<CachePreloadService.PreloadStats> getPreloadStats() {
        return ApiResponse.success(preloadService.getStats());
    }
}
