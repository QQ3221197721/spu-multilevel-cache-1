package com.ecommerce.cache.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缓存击穿保护器单元测试
 */
@ExtendWith(MockitoExtension.class)
class CacheBreakdownProtectorTest {
    
    @Mock
    private RedissonClient redissonClient;
    
    @Mock
    private L2RedisService l2RedisService;
    
    @Mock
    private RLock fairLock;
    
    private CacheBreakdownProtector protector;
    
    private static final String TEST_KEY = "spu:detail:10086";
    private static final String TEST_VALUE = "{\"spuId\":10086}";
    
    @BeforeEach
    void setUp() {
        protector = new CacheBreakdownProtector(redissonClient, l2RedisService);
    }
    
    @Test
    @DisplayName("缓存命中 - 直接返回，不加锁")
    void testGetWithBreakdownProtection_cacheHit() throws Exception {
        when(l2RedisService.get(TEST_KEY)).thenReturn(TEST_VALUE);
        
        Supplier<String> dbLoader = () -> TEST_VALUE;
        String result = protector.getWithBreakdownProtection(TEST_KEY, dbLoader, 600);
        
        assertEquals(TEST_VALUE, result);
        verify(redissonClient, never()).getFairLock(anyString());
    }
    
    @Test
    @DisplayName("缓存未命中 - 加锁回源")
    void testGetWithBreakdownProtection_cacheMiss() throws Exception {
        // 第一次检查：未命中
        // 加锁后第二次检查：仍未命中
        // 回源 DB
        when(l2RedisService.get(TEST_KEY)).thenReturn(null, null, TEST_VALUE);
        when(redissonClient.getFairLock(anyString())).thenReturn(fairLock);
        when(fairLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        
        Supplier<String> dbLoader = () -> TEST_VALUE;
        String result = protector.getWithBreakdownProtection(TEST_KEY, dbLoader, 600);
        
        assertEquals(TEST_VALUE, result);
        verify(fairLock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        verify(fairLock).unlock();
    }
    
    @Test
    @DisplayName("DCL 第二次检查命中 - 不回源")
    void testGetWithBreakdownProtection_dclSecondCheckHit() throws Exception {
        // 第一次检查：未命中
        // 加锁后第二次检查：命中（被其他线程填充）
        when(l2RedisService.get(TEST_KEY)).thenReturn(null, TEST_VALUE);
        when(redissonClient.getFairLock(anyString())).thenReturn(fairLock);
        when(fairLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        
        Supplier<String> dbLoader = mock(Supplier.class);
        String result = protector.getWithBreakdownProtection(TEST_KEY, dbLoader, 600);
        
        assertEquals(TEST_VALUE, result);
        // dbLoader 不应被调用
        verify(dbLoader, never()).get();
        verify(fairLock).unlock();
    }
    
    @Test
    @DisplayName("获取锁失败 - 短暂等待后重试")
    void testGetWithBreakdownProtection_lockFailed() throws Exception {
        when(l2RedisService.get(TEST_KEY)).thenReturn(null, TEST_VALUE);
        when(redissonClient.getFairLock(anyString())).thenReturn(fairLock);
        when(fairLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        
        Supplier<String> dbLoader = () -> TEST_VALUE;
        String result = protector.getWithBreakdownProtection(TEST_KEY, dbLoader, 600);
        
        // 获取锁失败后会再次尝试从缓存读取
        assertEquals(TEST_VALUE, result);
        verify(fairLock, never()).unlock();
    }
}
