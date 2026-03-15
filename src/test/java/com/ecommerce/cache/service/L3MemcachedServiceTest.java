package com.ecommerce.cache.service;

import net.spy.memcached.MemcachedClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L3 Memcached 缓存服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class L3MemcachedServiceTest {

    @Mock
    private MemcachedClient primaryClient;

    @Mock
    private MemcachedClient backupClient;

    private L3MemcachedService l3MemcachedService;

    private static final String TEST_KEY = "spu:detail:10086";
    private static final String TEST_VALUE = "{\"spuId\":10086}";

    @BeforeEach
    void setUp() {
        l3MemcachedService = new L3MemcachedService(primaryClient, backupClient);
        ReflectionTestUtils.setField(l3MemcachedService, "defaultExpiration", 600);
        ReflectionTestUtils.setField(l3MemcachedService, "retryCount", 3);
        ReflectionTestUtils.setField(l3MemcachedService, "retryInterval", 10);
    }

    // ==================== get ====================

    @Test
    @DisplayName("get - 主集群命中")
    void testGet_primaryHit() {
        when(primaryClient.get(TEST_KEY)).thenReturn(TEST_VALUE);

        String result = l3MemcachedService.get(TEST_KEY);

        assertEquals(TEST_VALUE, result);
        verify(backupClient, never()).get(anyString());
    }

    @Test
    @DisplayName("get - 主集群未命中，灾备集群命中")
    void testGet_primaryMiss_backupHit() throws Exception {
        when(primaryClient.get(TEST_KEY)).thenReturn(null);
        when(backupClient.get(TEST_KEY)).thenReturn(TEST_VALUE);
        // mock 回填主集群
        @SuppressWarnings("unchecked")
        Future<Boolean> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any())).thenReturn(true);
        when(primaryClient.set(eq(TEST_KEY), anyInt(), eq(TEST_VALUE))).thenReturn(mockFuture);

        String result = l3MemcachedService.get(TEST_KEY);

        assertEquals(TEST_VALUE, result);
        verify(primaryClient).set(eq(TEST_KEY), anyInt(), eq(TEST_VALUE));
    }

    @Test
    @DisplayName("get - 双集群均未命中")
    void testGet_allMiss() {
        when(primaryClient.get(TEST_KEY)).thenReturn(null);
        when(backupClient.get(TEST_KEY)).thenReturn(null);

        String result = l3MemcachedService.get(TEST_KEY);

        assertNull(result);
    }

    @Test
    @DisplayName("get - 主集群异常，重试后灾备命中")
    void testGet_primaryException_backupHit() throws Exception {
        when(primaryClient.get(TEST_KEY))
                .thenThrow(new RuntimeException("Connection failed"))
                .thenThrow(new RuntimeException("Connection failed"))
                .thenThrow(new RuntimeException("Connection failed"));
        when(backupClient.get(TEST_KEY)).thenReturn(TEST_VALUE);
        @SuppressWarnings("unchecked")
        Future<Boolean> mockFuture = mock(Future.class);
        when(mockFuture.get(anyLong(), any())).thenReturn(true);
        when(primaryClient.set(eq(TEST_KEY), anyInt(), eq(TEST_VALUE))).thenReturn(mockFuture);

        String result = l3MemcachedService.get(TEST_KEY);

        assertEquals(TEST_VALUE, result);
    }

    // ==================== set ====================

    @Test
    @DisplayName("set - 写入双集群")
    @SuppressWarnings("unchecked")
    void testSet_dualWrite() throws Exception {
        Future<Boolean> primaryFuture = mock(Future.class);
        Future<Boolean> backupFuture = mock(Future.class);
        when(primaryFuture.get(anyLong(), any())).thenReturn(true);
        when(backupFuture.get(anyLong(), any())).thenReturn(true);
        when(primaryClient.set(eq(TEST_KEY), anyInt(), eq(TEST_VALUE))).thenReturn(primaryFuture);
        when(backupClient.set(eq(TEST_KEY), anyInt(), eq(TEST_VALUE))).thenReturn(backupFuture);

        l3MemcachedService.set(TEST_KEY, TEST_VALUE, 600);

        verify(primaryClient).set(eq(TEST_KEY), anyInt(), eq(TEST_VALUE));
        verify(backupClient).set(eq(TEST_KEY), anyInt(), eq(TEST_VALUE));
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete - 删除双集群")
    @SuppressWarnings("unchecked")
    void testDelete_dualDelete() {
        Future<Boolean> primaryFuture = mock(Future.class);
        Future<Boolean> backupFuture = mock(Future.class);
        when(primaryClient.delete(TEST_KEY)).thenReturn(primaryFuture);
        when(backupClient.delete(TEST_KEY)).thenReturn(backupFuture);

        l3MemcachedService.delete(TEST_KEY);

        verify(primaryClient).delete(TEST_KEY);
        verify(backupClient).delete(TEST_KEY);
    }

    @Test
    @DisplayName("delete - 主集群异常不影响灾备删除")
    @SuppressWarnings("unchecked")
    void testDelete_primaryException() {
        when(primaryClient.delete(TEST_KEY)).thenThrow(new RuntimeException("Error"));
        Future<Boolean> backupFuture = mock(Future.class);
        when(backupClient.delete(TEST_KEY)).thenReturn(backupFuture);

        assertDoesNotThrow(() -> l3MemcachedService.delete(TEST_KEY));
        verify(backupClient).delete(TEST_KEY);
    }

    // ==================== getOrLoad ====================

    @Test
    @DisplayName("getOrLoad - 缓存命中直接返回")
    void testGetOrLoad_cacheHit() {
        when(primaryClient.get(TEST_KEY)).thenReturn(TEST_VALUE);

        String result = l3MemcachedService.getOrLoad(TEST_KEY, () -> "db_value");

        assertEquals(TEST_VALUE, result);
    }

    @Test
    @DisplayName("getOrLoad - 缓存未命中回源 DB")
    @SuppressWarnings("unchecked")
    void testGetOrLoad_cacheMiss_loadFromDb() throws Exception {
        when(primaryClient.get(TEST_KEY)).thenReturn(null);
        when(backupClient.get(TEST_KEY)).thenReturn(null);
        Future<Boolean> future = mock(Future.class);
        when(future.get(anyLong(), any())).thenReturn(true);
        when(primaryClient.set(eq(TEST_KEY), anyInt(), eq("db_value"))).thenReturn(future);
        when(backupClient.set(eq(TEST_KEY), anyInt(), eq("db_value"))).thenReturn(future);

        String result = l3MemcachedService.getOrLoad(TEST_KEY, () -> "db_value");

        assertEquals("db_value", result);
    }

    // ==================== getBulk ====================

    @Test
    @DisplayName("getBulk - 批量获取")
    void testGetBulk_success() {
        List<String> keys = Arrays.asList("key1", "key2", "key3");
        Map<String, Object> values = Map.of("key1", "v1", "key3", "v3");
        when(primaryClient.getBulk(keys)).thenReturn(values);

        Map<String, Object> result = l3MemcachedService.getBulk(keys);

        assertEquals(2, result.size());
        assertEquals("v1", result.get("key1"));
    }

    @Test
    @DisplayName("getBulk - 异常返回空 Map")
    void testGetBulk_exception() {
        List<String> keys = Arrays.asList("key1", "key2");
        when(primaryClient.getBulk(keys)).thenThrow(new RuntimeException("Error"));

        Map<String, Object> result = l3MemcachedService.getBulk(keys);

        assertTrue(result.isEmpty());
    }

    // ==================== incr / decr ====================

    @Test
    @DisplayName("incr - 原子递增")
    void testIncr_success() {
        when(primaryClient.incr(TEST_KEY, 1, 0L, 600)).thenReturn(42L);

        long result = l3MemcachedService.incr(TEST_KEY, 1, 0L, 600);

        assertEquals(42L, result);
    }

    @Test
    @DisplayName("decr - 原子递减")
    void testDecr_success() {
        when(primaryClient.decr(TEST_KEY, 1, 100L, 600)).thenReturn(99L);

        long result = l3MemcachedService.decr(TEST_KEY, 1, 100L, 600);

        assertEquals(99L, result);
    }
}
