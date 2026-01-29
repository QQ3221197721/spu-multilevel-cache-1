package com.ecommerce.cache.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 本地缓存服务单元测试
 */
class L1CacheServiceTest {
    
    private L1CacheService l1CacheService;
    private Cache<String, String> cache;
    
    @BeforeEach
    void setUp() {
        cache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(10, TimeUnit.SECONDS)
            .recordStats()
            .build();
        l1CacheService = new L1CacheService(cache);
    }
    
    @Test
    @DisplayName("缓存写入和读取")
    void testPutAndGet() {
        String key = "test:key:1";
        String value = "test_value";
        
        l1CacheService.put(key, value, 60);
        String result = l1CacheService.get(key);
        
        assertEquals(value, result);
    }
    
    @Test
    @DisplayName("缓存未命中返回 null")
    void testGetMiss() {
        String result = l1CacheService.get("non_existent_key");
        assertNull(result);
    }
    
    @Test
    @DisplayName("缓存失效")
    void testInvalidate() {
        String key = "test:key:2";
        l1CacheService.put(key, "value", 60);
        
        assertNotNull(l1CacheService.get(key));
        
        l1CacheService.invalidate(key);
        
        assertNull(l1CacheService.get(key));
    }
    
    @Test
    @DisplayName("清空所有缓存")
    void testInvalidateAll() {
        l1CacheService.put("key1", "value1", 60);
        l1CacheService.put("key2", "value2", 60);
        l1CacheService.put("key3", "value3", 60);
        
        assertEquals(3, l1CacheService.size());
        
        l1CacheService.invalidateAll();
        
        assertEquals(0, l1CacheService.size());
    }
    
    @Test
    @DisplayName("缓存大小统计")
    void testSize() {
        assertEquals(0, l1CacheService.size());
        
        l1CacheService.put("key1", "value1", 60);
        assertEquals(1, l1CacheService.size());
        
        l1CacheService.put("key2", "value2", 60);
        assertEquals(2, l1CacheService.size());
    }
    
    @Test
    @DisplayName("命中率统计")
    void testHitRate() {
        l1CacheService.put("key1", "value1", 60);
        
        // 命中
        l1CacheService.get("key1");
        l1CacheService.get("key1");
        
        // 未命中
        l1CacheService.get("key2");
        
        double hitRate = l1CacheService.hitRate();
        // 2 hits / 3 requests ≈ 0.67
        assertTrue(hitRate > 0.6 && hitRate < 0.7);
    }
}
