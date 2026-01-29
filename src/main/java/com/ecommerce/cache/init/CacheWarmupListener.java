package com.ecommerce.cache.init;

import com.ecommerce.cache.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 缓存预热启动监听器
 * 应用启动完成后自动执行缓存预热
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmupListener {
    
    private final SpuService spuService;
    private final BloomFilterService bloomFilterService;
    private final MultiLevelCacheService multiLevelCacheService;
    
    // 预热 Top 商品数量
    private static final int PREHEAT_TOP_COUNT = 1000;
    
    /**
     * 应用启动完成后执行缓存预热
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmupCache() {
        log.info(">>> Starting cache warmup...");
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 初始化布隆过滤器
            initBloomFilter();
            
            // 2. 预热 Top 销量商品
            preheatTopProducts();
            
            long duration = System.currentTimeMillis() - startTime;
            log.info(">>> Cache warmup completed in {} ms", duration);
            
        } catch (Exception e) {
            log.error(">>> Cache warmup failed", e);
        }
    }
    
    /**
     * 初始化布隆过滤器
     */
    private void initBloomFilter() {
        log.info("Initializing Bloom Filter...");
        
        List<Long> allSpuIds = spuService.getAllActiveSpuIds();
        
        int count = 0;
        for (Long spuId : allSpuIds) {
            bloomFilterService.add(spuId);
            count++;
        }
        
        log.info("Bloom Filter initialized with {} SPU IDs", count);
    }
    
    /**
     * 预热 Top 销量商品
     */
    private void preheatTopProducts() {
        log.info("Preheating top {} products...", PREHEAT_TOP_COUNT);
        
        List<Long> hotSpuIds = spuService.getHotSpuIds(PREHEAT_TOP_COUNT);
        
        int success = 0;
        int failed = 0;
        
        for (Long spuId : hotSpuIds) {
            try {
                String key = "spu:detail:" + spuId;
                // 触发一次缓存加载
                multiLevelCacheService.get(key, 
                    () -> serializeToJson(spuService.loadFromDatabase(spuId)), 
                    600);
                success++;
            } catch (Exception e) {
                log.warn("Failed to preheat SPU: {}", spuId, e);
                failed++;
            }
            
            // 避免启动时给数据库太大压力，每 100 个休眠 100ms
            if (success % 100 == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        log.info("Preheated {} products successfully, {} failed", success, failed);
    }
    
    private String serializeToJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }
}
