package com.ecommerce.cache.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L2 Redis 分布式缓存服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class L2RedisServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HotKeyShardService hotKeyShardService;

    private L2RedisService l2RedisService;

    private static final String TEST_KEY = "spu:detail:10086";
    private static final String TEST_VALUE = "{\"spuId\":10086,\"name\":\"Test\"}";

    @BeforeEach
    void setUp() {
        l2RedisService = new L2RedisService(redisTemplate, hotKeyShardService);
    }

    // ==================== get ====================

    @Test
    @DisplayName("get - 正常获取缓存")
    void testGet_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TEST_KEY)).thenReturn(TEST_VALUE);

        String result = l2RedisService.get(TEST_KEY);

        assertEquals(TEST_VALUE, result);
        verify(valueOperations).get(TEST_KEY);
    }

    @Test
    @DisplayName("get - Key 不存在返回 null")
    void testGet_keyNotFound() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TEST_KEY)).thenReturn(null);

        String result = l2RedisService.get(TEST_KEY);

        assertNull(result);
    }

    @Test
    @DisplayName("get - Redis 异常返回 null（不抛出）")
    void testGet_exception() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Connection refused"));

        String result = l2RedisService.get(TEST_KEY);

        assertNull(result);
    }

    // ==================== getHotKey ====================

    @Test
    @DisplayName("getHotKey - 通过 HotKeyShardService 获取")
    void testGetHotKey_success() {
        when(hotKeyShardService.getHotKey(TEST_KEY)).thenReturn(TEST_VALUE);

        String result = l2RedisService.getHotKey(TEST_KEY);

        assertEquals(TEST_VALUE, result);
        verify(hotKeyShardService).getHotKey(TEST_KEY);
    }

    @Test
    @DisplayName("getHotKey - 异常返回 null")
    void testGetHotKey_exception() {
        when(hotKeyShardService.getHotKey(TEST_KEY)).thenThrow(new RuntimeException("Shard error"));

        String result = l2RedisService.getHotKey(TEST_KEY);

        assertNull(result);
    }

    // ==================== set ====================

    @Test
    @DisplayName("set - 默认 TTL 写入")
    void testSet_defaultTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        l2RedisService.set(TEST_KEY, TEST_VALUE);

        verify(valueOperations).set(eq(TEST_KEY), eq(TEST_VALUE), longThat(ttl -> ttl >= 600 && ttl <= 900), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("set - 自定义 TTL + 随机扰动")
    void testSet_customTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        long baseTtl = 300L;

        l2RedisService.set(TEST_KEY, TEST_VALUE, baseTtl);

        verify(valueOperations).set(eq(TEST_KEY), eq(TEST_VALUE), longThat(ttl -> ttl >= baseTtl && ttl <= baseTtl + 300), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("set - 写入异常不抛出")
    void testSet_exception() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Connection refused"));

        assertDoesNotThrow(() -> l2RedisService.set(TEST_KEY, TEST_VALUE));
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete - 正常删除")
    void testDelete_success() {
        when(redisTemplate.delete(TEST_KEY)).thenReturn(true);

        l2RedisService.delete(TEST_KEY);

        verify(redisTemplate).delete(TEST_KEY);
    }

    @Test
    @DisplayName("delete - 异常不抛出")
    void testDelete_exception() {
        when(redisTemplate.delete(TEST_KEY)).thenThrow(new RuntimeException("Error"));

        assertDoesNotThrow(() -> l2RedisService.delete(TEST_KEY));
    }

    // ==================== deleteHotKey ====================

    @Test
    @DisplayName("deleteHotKey - 通过 HotKeyShardService 删除")
    void testDeleteHotKey_success() {
        l2RedisService.deleteHotKey(TEST_KEY);

        verify(hotKeyShardService).deleteHotKey(TEST_KEY);
    }

    // ==================== multiGet ====================

    @Test
    @DisplayName("multiGet - 批量获取")
    void testMultiGet_success() {
        List<String> keys = Arrays.asList("key1", "key2", "key3");
        List<String> values = Arrays.asList("v1", "v2", null);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(keys)).thenReturn(values);

        List<String> result = l2RedisService.multiGet(keys);

        assertEquals(3, result.size());
        assertEquals("v1", result.get(0));
        assertNull(result.get(2));
    }

    @Test
    @DisplayName("multiGet - 异常返回空列表")
    void testMultiGet_exception() {
        List<String> keys = Arrays.asList("key1", "key2");
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Error"));

        List<String> result = l2RedisService.multiGet(keys);

        assertTrue(result.isEmpty());
    }

    // ==================== exists ====================

    @Test
    @DisplayName("exists - Key 存在")
    void testExists_true() {
        when(redisTemplate.hasKey(TEST_KEY)).thenReturn(true);

        assertTrue(l2RedisService.exists(TEST_KEY));
    }

    @Test
    @DisplayName("exists - Key 不存在")
    void testExists_false() {
        when(redisTemplate.hasKey(TEST_KEY)).thenReturn(false);

        assertFalse(l2RedisService.exists(TEST_KEY));
    }

    // ==================== setIfAbsent ====================

    @Test
    @DisplayName("setIfAbsent - 成功设置")
    void testSetIfAbsent_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(TEST_KEY), eq(TEST_VALUE), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        assertTrue(l2RedisService.setIfAbsent(TEST_KEY, TEST_VALUE, 60));
    }

    @Test
    @DisplayName("setIfAbsent - Key 已存在")
    void testSetIfAbsent_alreadyExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(TEST_KEY), eq(TEST_VALUE), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertFalse(l2RedisService.setIfAbsent(TEST_KEY, TEST_VALUE, 60));
    }

    // ==================== getAsync ====================

    @Test
    @DisplayName("getAsync - 异步获取")
    void testGetAsync() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(TEST_KEY)).thenReturn(TEST_VALUE);

        CompletableFuture<String> future = l2RedisService.getAsync(TEST_KEY);
        String result = future.get(2, TimeUnit.SECONDS);

        assertEquals(TEST_VALUE, result);
    }

    // ==================== increment ====================

    @Test
    @DisplayName("increment - 原子递增")
    void testIncrement_success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(TEST_KEY)).thenReturn(42L);

        Long result = l2RedisService.increment(TEST_KEY);

        assertEquals(42L, result);
    }

    @Test
    @DisplayName("increment - 异常返回 null")
    void testIncrement_exception() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Error"));

        Long result = l2RedisService.increment(TEST_KEY);

        assertNull(result);
    }
}
