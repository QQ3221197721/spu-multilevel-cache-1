package com.ecommerce.cache.controller;

import com.ecommerce.cache.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * SPU 详情控制器
 * 提供 SPU 详情查询接口，支持多级缓存
 */
@RestController
@RequestMapping("/api/spu")
public class SpuDetailController {
    
    private static final Logger log = LoggerFactory.getLogger(SpuDetailController.class);
    
    private static final String CACHE_KEY_PREFIX = "spu:detail:";
    
    private final MultiLevelCacheService cacheService;
    private final BloomFilterService bloomFilterService;
    private final HotKeyDetectorService hotKeyDetectorService;
    private final L1CacheService l1CacheService;
    private final SpuRepository spuRepository;

    public SpuDetailController(MultiLevelCacheService cacheService,
                                BloomFilterService bloomFilterService,
                                HotKeyDetectorService hotKeyDetectorService,
                                L1CacheService l1CacheService,
                                SpuRepository spuRepository) {
        this.cacheService = cacheService;
        this.bloomFilterService = bloomFilterService;
        this.hotKeyDetectorService = hotKeyDetectorService;
        this.l1CacheService = l1CacheService;
        this.spuRepository = spuRepository;
    }

    /**
     * 获取 SPU 详情
     * GET /api/spu/detail/{spuId}
     */
    @GetMapping("/detail/{spuId}")
    public ResponseEntity<ApiResponse<String>> getSpuDetail(@PathVariable String spuId) {
        long startTime = System.currentTimeMillis();
        
        String cacheKey = CACHE_KEY_PREFIX + spuId;
        
        // 从多级缓存获取
        String spuDetail = cacheService.get(cacheKey, () -> {
            // 回源 DB
            log.info("Loading SPU from DB: {}", spuId);
            return spuRepository.findById(spuId);
        });
        
        long duration = System.currentTimeMillis() - startTime;
        log.debug("SPU detail request completed, spuId={}, duration={}ms", spuId, duration);
        
        if (spuDetail == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(ApiResponse.success(spuDetail));
    }

    /**
     * 刷新 SPU 缓存
     * POST /api/spu/refresh/{spuId}
     */
    @PostMapping("/refresh/{spuId}")
    public ResponseEntity<ApiResponse<String>> refreshSpuCache(@PathVariable String spuId) {
        String cacheKey = CACHE_KEY_PREFIX + spuId;
        
        String spuDetail = cacheService.refresh(cacheKey, () -> spuRepository.findById(spuId));
        
        log.info("SPU cache refreshed: {}", spuId);
        
        return ResponseEntity.ok(ApiResponse.success(spuDetail));
    }

    /**
     * 删除 SPU 缓存
     * DELETE /api/spu/cache/{spuId}
     */
    @DeleteMapping("/cache/{spuId}")
    public ResponseEntity<ApiResponse<Void>> invalidateSpuCache(@PathVariable String spuId) {
        String cacheKey = CACHE_KEY_PREFIX + spuId;
        
        cacheService.invalidate(cacheKey);
        
        log.info("SPU cache invalidated: {}", spuId);
        
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 获取缓存统计信息
     * GET /api/spu/cache/stats
     */
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
    @GetMapping("/cache/hotkeys")
    public ResponseEntity<ApiResponse<Object>> getHotKeys(
            @RequestParam(defaultValue = "10") int limit) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("hotKeys", hotKeyDetectorService.getTopHotKeys(limit));
        result.put("stats", hotKeyDetectorService.getStats());
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 预热缓存
     * POST /api/spu/cache/preheat
     */
    @PostMapping("/cache/preheat")
    public ResponseEntity<ApiResponse<Integer>> preheatCache(
            @RequestBody PreheatRequest request) {
        
        int count = 0;
        for (String spuId : request.spuIds()) {
            String cacheKey = CACHE_KEY_PREFIX + spuId;
            String spuDetail = spuRepository.findById(spuId);
            if (spuDetail != null) {
                cacheService.preheat(cacheKey, spuDetail, request.ttlSeconds());
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
    public record PreheatRequest(java.util.List<String> spuIds, long ttlSeconds) {}
    
    public record ApiResponse<T>(int code, String message, T data) {
        public static <T> ApiResponse<T> success(T data) {
            return new ApiResponse<>(0, "success", data);
        }
        
        public static <T> ApiResponse<T> error(int code, String message) {
            return new ApiResponse<>(code, message, null);
        }
    }
}

/**
 * SPU 仓储接口（模拟）
 */
interface SpuRepository {
    String findById(String spuId);
}

/**
 * SPU 仓储实现（模拟）
 */
@org.springframework.stereotype.Repository
class SpuRepositoryImpl implements SpuRepository {
    
    @Override
    public String findById(String spuId) {
        // 模拟 DB 查询延迟
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 返回模拟数据
        return String.format("""
            {
                "spuId": "%s",
                "name": "商品_%s",
                "price": %.2f,
                "stock": %d,
                "description": "这是商品 %s 的详细描述",
                "createTime": "%s"
            }
            """, 
            spuId, 
            spuId, 
            Math.random() * 1000,
            (int)(Math.random() * 10000),
            spuId,
            java.time.LocalDateTime.now()
        );
    }
}
