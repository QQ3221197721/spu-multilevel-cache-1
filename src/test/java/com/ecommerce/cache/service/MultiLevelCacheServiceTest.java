package com.ecommerce.cache.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 多级缓存服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class MultiLevelCacheServiceTest {
    
    @Mock
    private L1CacheService l1CacheService;
    
    @Mock
    private L2RedisService l2RedisService;
    
    @Mock
    private L3MemcachedService l3MemcachedService;
    
    @Mock
    private BloomFilterService bloomFilterService;
    
    @Mock
    private HotKeyDetectorService hotKeyDetectorService;
    
    @Mock
    private CacheBreakdownProtector cacheBreakdownProtector;
    
    @Mock
    private CacheAvalancheProtector cacheAvalancheProtector;
    
    private MultiLevelCacheService multiLevelCacheService;
    
    private static final String TEST_KEY = "spu:detail:10086";
    private static final String TEST_VALUE = "{\"spuId\":10086,\"name\":\"Test Product\"}";
    
    @BeforeEach
    void setUp() {
        multiLevelCacheService = new MultiLevelCacheService(
            l1CacheService, l2RedisService, l3MemcachedService,
            bloomFilterService, hotKeyDetectorService,
            cacheBreakdownProtector, cacheAvalancheProtector
        );
    }
    
    @Test
    @DisplayName("L1 缓存命中 - 直接返回")
    void testGet_L1Hit() {
        when(bloomFilterService.mightContain(10086L)).thenReturn(true);
        when(hotKeyDetectorService.recordAccess(TEST_KEY)).thenReturn(false);
        when(l1CacheService.get(TEST_KEY)).thenReturn(TEST_VALUE);
        
        Supplier<String> dbLoader = () -> TEST_VALUE;
        String result = multiLevelCacheService.get(TEST_KEY, dbLoader, 600);
        
        assertEquals(TEST_VALUE, result);
        verify(l2RedisService, never()).get(anyString());
        verify(l3MemcachedService, never()).get(anyString());
    }
    
    @Test
    @DisplayName("L1 未命中，L2 命中 - 回填 L1")
    void testGet_L1MissL2Hit() {
        when(bloomFilterService.mightContain(10086L)).thenReturn(true);
        when(hotKeyDetectorService.recordAccess(TEST_KEY)).thenReturn(false);
        when(l1CacheService.get(TEST_KEY)).thenReturn(null);
        when(l2RedisService.get(TEST_KEY)).thenReturn(TEST_VALUE);
        
        Supplier<String> dbLoader = () -> TEST_VALUE;
        String result = multiLevelCacheService.get(TEST_KEY, dbLoader, 600);
        
        assertEquals(TEST_VALUE, result);
        verify(l1CacheService).put(eq(TEST_KEY), eq(TEST_VALUE), anyLong());
        verify(l3MemcachedService, never()).get(anyString());
    }
    
    @Test
    @DisplayName("布隆过滤器拦截 - 防穿透")
    void testGet_BloomFilterBlock() {
        when(bloomFilterService.mightContain(99999L)).thenReturn(false);
        
        String key = "spu:detail:99999";
        Supplier<String> dbLoader = () -> null;
        String result = multiLevelCacheService.get(key, dbLoader, 600);
        
        assertNull(result);
        verify(l1CacheService, never()).get(anyString());
        verify(l2RedisService, never()).get(anyString());
    }
    
    @Test
    @DisplayName("热点 Key 检测 - 走分片读取")
    void testGet_HotKey() {
        when(bloomFilterService.mightContain(10086L)).thenReturn(true);
        when(hotKeyDetectorService.recordAccess(TEST_KEY)).thenReturn(true);
        when(l1CacheService.get(TEST_KEY)).thenReturn(null);
        when(l2RedisService.getHotKey(TEST_KEY)).thenReturn(TEST_VALUE);
        
        Supplier<String> dbLoader = () -> TEST_VALUE;
        String result = multiLevelCacheService.get(TEST_KEY, dbLoader, 600);
        
        assertEquals(TEST_VALUE, result);
        verify(l2RedisService).getHotKey(TEST_KEY);
        verify(l2RedisService, never()).get(TEST_KEY);
    }
    
    @Test
    @DisplayName("缓存失效 - 清除所有层")
    void testInvalidate() {
        multiLevelCacheService.invalidate(TEST_KEY);
        
        verify(l1CacheService).invalidate(TEST_KEY);
        verify(l2RedisService).delete(TEST_KEY);
        verify(l3MemcachedService).delete(TEST_KEY);
    }
}
