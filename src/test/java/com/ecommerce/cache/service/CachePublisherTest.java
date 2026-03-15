package com.ecommerce.cache.service;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缓存发布服务单元测试（Kafka 版）
 */
@ExtendWith(MockitoExtension.class)
class CachePublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private CachePublisher cachePublisher;

    @BeforeEach
    void setUp() {
        cachePublisher = new CachePublisher(kafkaTemplate);
        ReflectionTestUtils.setField(cachePublisher, "applicationName", "spu-detail-service");
    }

    // ==================== publishInvalidate ====================

    @Test
    @DisplayName("publishInvalidate - 发送缓存失效消息")
    void testPublishInvalidate_success() {
        List<String> cacheKeys = Arrays.asList("spu:detail:10086", "spu:ext:10086");
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.complete(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

        cachePublisher.publishInvalidate("t_spu", "UPDATE", "10086", cacheKeys);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), payloadCaptor.capture());

        assertEquals("CACHE_INVALIDATE_TOPIC", topicCaptor.getValue());
        assertEquals("10086", keyCaptor.getValue());
        assertNotNull(payloadCaptor.getValue());
        assertTrue(payloadCaptor.getValue().contains("t_spu"));
        assertTrue(payloadCaptor.getValue().contains("UPDATE"));
        assertTrue(payloadCaptor.getValue().contains("10086"));
    }

    @Test
    @DisplayName("publishInvalidate - Kafka 异常抛出 RuntimeException")
    void testPublishInvalidate_exception() {
        doThrow(new RuntimeException("Kafka Error"))
                .when(kafkaTemplate).send(anyString(), anyString(), anyString());

        assertThrows(RuntimeException.class, () ->
                cachePublisher.publishInvalidate("t_spu", "DELETE", "10086",
                        List.of("spu:detail:10086")));
    }

    // ==================== publishLocalInvalidate ====================

    @Test
    @DisplayName("publishLocalInvalidate - 发送本地缓存失效广播")
    void testPublishLocalInvalidate_success() {
        List<String> keys = Arrays.asList("spu:detail:10086", "spu:detail:10087");
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.complete(null);
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(future);

        cachePublisher.publishLocalInvalidate(keys);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), payloadCaptor.capture());

        assertEquals("CACHE_LOCAL_INVALIDATE_TOPIC", topicCaptor.getValue());
        assertTrue(payloadCaptor.getValue().contains("spu:detail:10086"));
        assertTrue(payloadCaptor.getValue().contains("spu-detail-service"));
    }

    @Test
    @DisplayName("publishLocalInvalidate - Kafka 异常不抛出（仅记录日志）")
    void testPublishLocalInvalidate_exception() {
        doThrow(new RuntimeException("Kafka Error"))
                .when(kafkaTemplate).send(anyString(), anyString());

        assertDoesNotThrow(() ->
                cachePublisher.publishLocalInvalidate(List.of("key1")));
    }

    // ==================== Record 类测试 ====================

    @Test
    @DisplayName("CacheInvalidateMessage - Record 字段验证")
    void testCacheInvalidateMessage() {
        CachePublisher.CacheInvalidateMessage msg = new CachePublisher.CacheInvalidateMessage(
                "msg-001", "t_spu", "INSERT", "10086",
                List.of("spu:detail:10086"), System.currentTimeMillis(), "trace-001");

        assertEquals("msg-001", msg.messageId());
        assertEquals("t_spu", msg.tableName());
        assertEquals("INSERT", msg.eventType());
        assertEquals("10086", msg.primaryKey());
        assertEquals(1, msg.cacheKeys().size());
        assertEquals("trace-001", msg.traceId());
    }

    @Test
    @DisplayName("LocalCacheInvalidateMessage - Record 字段验证")
    void testLocalCacheInvalidateMessage() {
        CachePublisher.LocalCacheInvalidateMessage msg = new CachePublisher.LocalCacheInvalidateMessage(
                List.of("key1", "key2"), "instance-1", System.currentTimeMillis());

        assertEquals(2, msg.keys().size());
        assertEquals("instance-1", msg.sourceInstance());
    }
}
