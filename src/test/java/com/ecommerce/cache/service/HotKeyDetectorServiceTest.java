package com.ecommerce.cache.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 热点 Key 检测服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class HotKeyDetectorServiceTest {
    
    @Mock
    private StringRedisTemplate redisTemplate;
    
    @Mock
    private RedissonClient redissonClient;
    
    private HotKeyDetectorService hotKeyDetectorService;
    
    private static final String TEST_KEY = "spu:detail:10086";
    
    @BeforeEach
    void setUp() {
        hotKeyDetectorService = new HotKeyDetectorService(redisTemplate, redissonClient);
    }
    
    @Test
    @DisplayName("记录访问 - 非热点 Key")
    void testRecordAccess_notHotKey() {
        // 模拟 Lua 脚本返回：QPS=100, isHot=0
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
            .thenReturn(Arrays.asList(100L, 0L));
        
        boolean isHot = hotKeyDetectorService.recordAccess(TEST_KEY);
        
        assertFalse(isHot);
    }
    
    @Test
    @DisplayName("记录访问 - 热点 Key")
    void testRecordAccess_isHotKey() {
        // 模拟 Lua 脚本返回：QPS=6000, isHot=1
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
            .thenReturn(Arrays.asList(6000L, 1L));
        
        boolean isHot = hotKeyDetectorService.recordAccess(TEST_KEY);
        
        assertTrue(isHot);
    }
    
    @Test
    @DisplayName("手动标记热点 Key")
    void testMarkAsHotKey() {
        hotKeyDetectorService.markAsHotKey(TEST_KEY);
        
        Set<String> hotKeys = hotKeyDetectorService.getHotKeys();
        assertTrue(hotKeys.contains(TEST_KEY));
    }
    
    @Test
    @DisplayName("获取热点 Key 列表")
    void testGetHotKeys() {
        hotKeyDetectorService.markAsHotKey("key1");
        hotKeyDetectorService.markAsHotKey("key2");
        hotKeyDetectorService.markAsHotKey("key3");
        
        Set<String> hotKeys = hotKeyDetectorService.getHotKeys();
        
        assertEquals(3, hotKeys.size());
        assertTrue(hotKeys.containsAll(Set.of("key1", "key2", "key3")));
    }
    
    @Test
    @DisplayName("移除热点 Key")
    void testRemoveHotKey() {
        hotKeyDetectorService.markAsHotKey(TEST_KEY);
        assertTrue(hotKeyDetectorService.getHotKeys().contains(TEST_KEY));
        
        hotKeyDetectorService.removeHotKey(TEST_KEY);
        assertFalse(hotKeyDetectorService.getHotKeys().contains(TEST_KEY));
    }
}
