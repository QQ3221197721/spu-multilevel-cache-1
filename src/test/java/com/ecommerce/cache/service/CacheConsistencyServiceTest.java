package com.ecommerce.cache.service;

import com.ecommerce.cache.config.CanalProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缓存一致性保障服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class CacheConsistencyServiceTest {

    @Mock
    private L1CacheService l1CacheService;

    @Mock
    private L2RedisService l2RedisService;

    @Mock
    private L3MemcachedService l3MemcachedService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private CachePublisher cachePublisher;

    @Mock
    private RBucket<Long> versionBucket;

    @Mock
    private RLock writeLock;

    private CanalProperties canalProperties;
    private MeterRegistry meterRegistry;
    private CacheConsistencyService service;

    private static final String TEST_KEY = "spu:detail:10086";
    private static final List<String> TEST_KEYS = Arrays.asList("spu:detail:10086", "spu:ext:10086");

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        canalProperties = buildTestProperties();

        service = new CacheConsistencyService(
                canalProperties, l1CacheService, l2RedisService,
                l3MemcachedService, redissonClient, cachePublisher, meterRegistry);
        service.init();
    }

    // ==================== onBinlogEvent ====================

    @Test
    @DisplayName("onBinlogEvent - 立即删除所有层级缓存")
    void testOnBinlogEvent_invalidatesAllLayers() {
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(null);

        service.onBinlogEvent("t_spu", "UPDATE", TEST_KEYS, System.currentTimeMillis() / 1000);

        verify(l1CacheService, times(2)).invalidate(anyString());
        verify(l2RedisService, times(2)).deleteHotKey(anyString());
        verify(l3MemcachedService, times(2)).delete(anyString());
    }

    @Test
    @DisplayName("onBinlogEvent - 广播本地缓存失效")
    void testOnBinlogEvent_publishesBroadcast() {
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(null);

        service.onBinlogEvent("t_spu", "INSERT", TEST_KEYS, System.currentTimeMillis() / 1000);

        verify(cachePublisher).publishLocalInvalidate(TEST_KEYS);
    }

    @Test
    @DisplayName("onBinlogEvent - 版本号递增")
    void testOnBinlogEvent_incrementsVersion() {
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(5L);

        service.onBinlogEvent("t_spu", "UPDATE", List.of(TEST_KEY), 1000L);

        verify(versionBucket).set(eq(6L), any(Duration.class));
    }

    @Test
    @DisplayName("onBinlogEvent - 延迟双删（通过 Awaitility 验证异步执行）")
    void testOnBinlogEvent_delayedDoubleDelete() {
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(null);

        service.onBinlogEvent("t_spu", "DELETE", List.of(TEST_KEY), 1000L);

        // 首次立即删除
        verify(l1CacheService, atLeast(1)).invalidate(TEST_KEY);

        // 延迟双删应在 delayedDeleteMs 后再次执行
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                verify(l1CacheService, atLeast(2)).invalidate(TEST_KEY));
    }

    @Test
    @DisplayName("onBinlogEvent - 禁用延迟双删时不调度")
    void testOnBinlogEvent_delayedDeleteDisabled() {
        canalProperties.getConsistency().setDelayedDoubleDeleteEnabled(false);
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(null);

        service.onBinlogEvent("t_spu", "UPDATE", List.of(TEST_KEY), 1000L);

        // 仅首次删除，不会有延迟双删
        verify(l1CacheService, times(1)).invalidate(TEST_KEY);
    }

    @Test
    @DisplayName("onBinlogEvent - 禁用版本校验时不递增版本号")
    void testOnBinlogEvent_versionCheckDisabled() {
        canalProperties.getConsistency().setVersionCheckEnabled(false);

        service.onBinlogEvent("t_spu", "UPDATE", List.of(TEST_KEY), 1000L);

        verify(redissonClient, never()).getBucket(anyString());
    }

    // ==================== incrementVersion ====================

    @Test
    @DisplayName("incrementVersion - 首次递增从 0 → 1")
    void testIncrementVersion_firstTime() {
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(null);

        long newVersion = service.incrementVersion(TEST_KEY);

        assertEquals(1L, newVersion);
        verify(versionBucket).set(eq(1L), any(Duration.class));
    }

    @Test
    @DisplayName("incrementVersion - 递增已有版本 5 → 6")
    void testIncrementVersion_existing() {
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(5L);

        long newVersion = service.incrementVersion(TEST_KEY);

        assertEquals(6L, newVersion);
    }

    // ==================== validateVersion ====================

    @Test
    @DisplayName("validateVersion - 版本一致返回 true")
    void testValidateVersion_consistent() {
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(5L);

        assertTrue(service.validateVersion(TEST_KEY, 5L));
    }

    @Test
    @DisplayName("validateVersion - 版本不一致返回 false")
    void testValidateVersion_inconsistent() {
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(10L);

        assertFalse(service.validateVersion(TEST_KEY, 5L));
    }

    @Test
    @DisplayName("validateVersion - 禁用时始终返回 true")
    void testValidateVersion_disabled() {
        canalProperties.getConsistency().setVersionCheckEnabled(false);

        assertTrue(service.validateVersion(TEST_KEY, 0L));
    }

    // ==================== beforeWrite ====================

    @Test
    @DisplayName("beforeWrite - 获取锁成功后删除缓存")
    void testBeforeWrite_lockSuccess() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(writeLock);
        when(writeLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        service.beforeWrite(TEST_KEY);

        verify(l1CacheService).invalidate(TEST_KEY);
        verify(l2RedisService).deleteHotKey(TEST_KEY);
        verify(l3MemcachedService).delete(TEST_KEY);
        verify(writeLock).unlock();
    }

    @Test
    @DisplayName("beforeWrite - 获取锁失败仍删除缓存")
    void testBeforeWrite_lockFailed() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(writeLock);
        when(writeLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        service.beforeWrite(TEST_KEY);

        // 即使锁获取失败，仍应尝试删除缓存
        verify(l1CacheService).invalidate(TEST_KEY);
    }

    // ==================== afterWrite ====================

    @Test
    @DisplayName("afterWrite - 发布失效消息 + 调度延迟双删")
    void testAfterWrite() {
        service.afterWrite("t_spu", "10086", TEST_KEYS);

        verify(cachePublisher).publishInvalidate(eq("t_spu"), eq("UPDATE"), eq("10086"), eq(TEST_KEYS));
    }

    // ==================== getStats ====================

    @Test
    @DisplayName("getStats - 返回统计信息")
    void testGetStats() {
        CacheConsistencyService.ConsistencyStats stats = service.getStats();

        assertNotNull(stats);
        assertEquals(0, stats.totalBinlogInvalidations());
        assertEquals(0, stats.totalDelayedDeletes());
        assertEquals(0, stats.totalVersionMismatches());
    }

    @Test
    @DisplayName("getStats - binlog 事件后统计递增")
    void testGetStats_afterEvents() {
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(null);

        service.onBinlogEvent("t_spu", "UPDATE", List.of(TEST_KEY), 1000L);

        CacheConsistencyService.ConsistencyStats stats = service.getStats();
        assertEquals(1, stats.totalBinlogInvalidations());
        assertEquals(1000L, stats.lastBinlogEventTimestamp());
    }

    // ==================== L1/L2/L3 异常容错 ====================

    @Test
    @DisplayName("invalidateAllLayers - L1 异常不影响 L2/L3 删除")
    void testInvalidateAllLayers_l1Exception() {
        doThrow(new RuntimeException("L1 Error")).when(l1CacheService).invalidate(anyString());
        when(redissonClient.<Long>getBucket(anyString())).thenReturn(versionBucket);
        when(versionBucket.get()).thenReturn(null);

        assertDoesNotThrow(() ->
                service.onBinlogEvent("t_spu", "UPDATE", List.of(TEST_KEY), 1000L));

        verify(l2RedisService).deleteHotKey(TEST_KEY);
        verify(l3MemcachedService).delete(TEST_KEY);
    }

    // ==================== 工具方法 ====================

    private CanalProperties buildTestProperties() {
        CanalProperties props = new CanalProperties();
        props.setEnabled(true);
        props.setBinlogTopic("CANAL_BINLOG_TOPIC");
        props.setConsumerGroup("CG_CANAL_BINLOG");

        CanalProperties.ConsistencyConfig consistency = new CanalProperties.ConsistencyConfig();
        consistency.setDelayedDoubleDeleteEnabled(true);
        consistency.setDelayedDeleteMs(500); // 缩短延迟便于测试
        consistency.setVersionCheckEnabled(true);
        consistency.setVersionTtlSeconds(3600);
        consistency.setVersionKeyPrefix("cache:version:");
        consistency.setAuditEnabled(true);
        consistency.setAuditSampleRate(1.0); // 测试中全量采样
        consistency.setAuditWindowSeconds(60);
        consistency.setAuditAutoFix(true);
        consistency.setMaxBinlogDelayAlertSeconds(10);
        props.setConsistency(consistency);

        return props;
    }
}
