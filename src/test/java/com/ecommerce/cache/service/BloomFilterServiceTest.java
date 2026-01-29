package com.ecommerce.cache.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 布隆过滤器服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class BloomFilterServiceTest {
    
    @Mock
    private RedissonClient redissonClient;
    
    @Mock
    private RBloomFilter<Long> bloomFilter;
    
    private BloomFilterService bloomFilterService;
    
    @BeforeEach
    void setUp() {
        when(redissonClient.getBloomFilter(anyString())).thenReturn(bloomFilter);
        when(bloomFilter.isExists()).thenReturn(true);
        bloomFilterService = new BloomFilterService(redissonClient);
    }
    
    @Test
    @DisplayName("添加元素到布隆过滤器")
    void testAdd() {
        when(bloomFilter.add(10086L)).thenReturn(true);
        
        boolean result = bloomFilterService.add(10086L);
        
        assertTrue(result);
        verify(bloomFilter).add(10086L);
    }
    
    @Test
    @DisplayName("检查元素是否可能存在")
    void testMightContain_exists() {
        when(bloomFilter.contains(10086L)).thenReturn(true);
        
        boolean result = bloomFilterService.mightContain(10086L);
        
        assertTrue(result);
    }
    
    @Test
    @DisplayName("检查元素不存在")
    void testMightContain_notExists() {
        when(bloomFilter.contains(99999L)).thenReturn(false);
        
        boolean result = bloomFilterService.mightContain(99999L);
        
        assertFalse(result);
    }
    
    @Test
    @DisplayName("获取布隆过滤器元素数量")
    void testCount() {
        when(bloomFilter.count()).thenReturn(100000L);
        
        long count = bloomFilterService.count();
        
        assertEquals(100000L, count);
    }
}
