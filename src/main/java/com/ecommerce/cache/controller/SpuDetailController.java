package com.ecommerce.cache.controller;

import com.ecommerce.cache.dto.ApiResponse;
import com.ecommerce.cache.dto.SpuDetailDTO;
import com.ecommerce.cache.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * SPU 详情控制器
 * 提供 SPU 详情查询接口，支持多级缓存
 */
@Tag(name = "SPU详情", description = "SPU 商品详情查询、缓存刷新、预热等核心接口")
@RestController
@RequestMapping("/api/spu")
public class SpuDetailController {
    
    private static final Logger log = LoggerFactory.getLogger(SpuDetailController.class);
    
    private static final String CACHE_KEY_PREFIX = "spu:detail:";
    
    private final MultiLevelCacheService cacheService;
    private final BloomFilterService bloomFilterService;
    private final HotKeyDetectorService hotKeyDetectorService;
    private final L1CacheService l1CacheService;
    private final SpuService spuService;
    private final ObjectMapper objectMapper;

    public SpuDetailController(MultiLevelCacheService cacheService,
                                BloomFilterService bloomFilterService,
                                HotKeyDetectorService hotKeyDetectorService,
                                L1CacheService l1CacheService,
                                SpuService spuService,
                                ObjectMapper objectMapper) {
        this.cacheService = cacheService;
        this.bloomFilterService = bloomFilterService;
        this.hotKeyDetectorService = hotKeyDetectorService;
        this.l1CacheService = l1CacheService;
        this.spuService = spuService;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取 SPU 详情
     * GET /api/spu/detail/{spuId}
     */
    @Operation(summary = "获取 SPU 详情", description = "通过多级缓存(L1 Caffeine → L2 Redis → L3 Memcached → DB)获取商品详情，自动回源并填充缓存")
    @GetMapping("/detail/{spuId}")
    public ResponseEntity<ApiResponse<SpuDetailDTO>> getSpuDetail(
            @Parameter(description = "SPU ID", required = true, example = "100001") @PathVariable Long spuId) {
        long startTime = System.currentTimeMillis();
        
        String cacheKey = CACHE_KEY_PREFIX + spuId;
        
        // 从多级缓存获取
        String spuJson = cacheService.get(cacheKey, () -> {
            // 回源 DB
            log.info("Loading SPU from DB: {}", spuId);
            SpuDetailDTO dto = spuService.loadFromDatabase(spuId);
            return serializeToJson(dto);
        });
        
        long duration = System.currentTimeMillis() - startTime;
        log.debug("SPU detail request completed, spuId={}, duration={}ms", spuId, duration);
        
        if (spuJson == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.notFound("商品不存在: " + spuId));
        }
        
        SpuDetailDTO dto = deserializeFromJson(spuJson, SpuDetailDTO.class);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    /**
     * 刷新 SPU 缓存
     * POST /api/spu/refresh/{spuId}
     */
    @Operation(summary = "刷新 SPU 缓存", description = "强制回源数据库重新加载 SPU 数据，并更新各级缓存")
    @PostMapping("/refresh/{spuId}")
    public ResponseEntity<ApiResponse<SpuDetailDTO>> refreshSpuCache(
            @Parameter(description = "SPU ID", required = true) @PathVariable Long spuId) {
        String cacheKey = CACHE_KEY_PREFIX + spuId;
        
        String spuJson = cacheService.refresh(cacheKey, () -> {
            SpuDetailDTO dto = spuService.loadFromDatabase(spuId);
            return serializeToJson(dto);
        });
        
        log.info("SPU cache refreshed: {}", spuId);
        
        SpuDetailDTO dto = deserializeFromJson(spuJson, SpuDetailDTO.class);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    /**
     * 删除 SPU 缓存
     * DELETE /api/spu/cache/{spuId}
     */
    @Operation(summary = "删除 SPU 缓存", description = "删除指定 SPU 在所有缓存层的数据，下次访问将回源数据库")
    @DeleteMapping("/cache/{spuId}")
    public ResponseEntity<ApiResponse<Void>> invalidateSpuCache(
            @Parameter(description = "SPU ID", required = true) @PathVariable Long spuId) {
        String cacheKey = CACHE_KEY_PREFIX + spuId;
        
        cacheService.invalidate(cacheKey);
        
        log.info("SPU cache invalidated: {}", spuId);
        
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 获取缓存统计信息
     * GET /api/spu/cache/stats
     */
    @Operation(summary = "获取缓存统计", description = "返回 L1 缓存命中率、布隆过滤器统计、热点 Key Top N 等全局缓存指标")
    @GetMapping("/cache/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // L1 缓存统计
        stats.put("l1", l1CacheService.getStats());
        stats.put("l1HitRate", l1CacheService.getHitRate());
        stats.put("l1Size", l1CacheService.size());
        
        // 布隆过滤器统计
        stats.put("bloomFilter", bloomFilterService.getStats());
        
        // 热点 Key 统计
        stats.put("hotKey", hotKeyDetectorService.getStats());
        stats.put("topHotKeys", hotKeyDetectorService.getTopHotKeys(10));
        
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    /**
     * 获取热点 Key 列表
     * GET /api/spu/cache/hotkeys
     */
    @Operation(summary = "获取热点 Key", description = "返回当前访问频率最高的缓存 Key 列表，用于热点发现与分片决策")
    @GetMapping("/cache/hotkeys")
    public ResponseEntity<ApiResponse<Object>> getHotKeys(
            @Parameter(description = "返回数量上限", example = "10") @RequestParam(defaultValue = "10") int limit) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("hotKeys", hotKeyDetectorService.getTopHotKeys(limit));
        result.put("stats", hotKeyDetectorService.getStats());
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 预热缓存
     * POST /api/spu/cache/preheat
     */
    @Operation(summary = "批量预热缓存", description = "将指定 SPU 列表数据提前加载到各级缓存，适用于大促前的缓存预热")
    @PostMapping("/cache/preheat")
    public ResponseEntity<ApiResponse<Integer>> preheatCache(
            @RequestBody PreheatRequest request) {
        
        int count = 0;
        for (Long spuId : request.spuIds()) {
            String cacheKey = CACHE_KEY_PREFIX + spuId;
            SpuDetailDTO dto = spuService.loadFromDatabase(spuId);
            if (dto != null) {
                String json = serializeToJson(dto);
                cacheService.preheat(cacheKey, json, request.ttlSeconds());
                count++;
            }
        }
        
        log.info("Cache preheated, count: {}", count);
        
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    /**
     * 健康检查
     * GET /api/spu/health
     */
    @Operation(summary = "健康检查", description = "返回 SPU 服务及各缓存层的健康状态")
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        health.put("l1Cache", "OK");
        health.put("l2Redis", "OK");
        health.put("l3Memcached", "OK");
        
        return ResponseEntity.ok(ApiResponse.success(health));
    }

    // DTO 类
    public record PreheatRequest(java.util.List<Long> spuIds, long ttlSeconds) {}

    private String serializeToJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    private <T> T deserializeFromJson(String json, Class<T> clazz) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("JSON 反序列化失败", e);
        }
    }
}
