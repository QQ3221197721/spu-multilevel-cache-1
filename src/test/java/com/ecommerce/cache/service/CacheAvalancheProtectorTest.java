package com.ecommerce.cache.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缓存雪崩保护器测试
 */
class CacheAvalancheProtectorTest {
    
    private final CacheAvalancheProtector protector = new CacheAvalancheProtector(null, null, null);
    
    @Test
    @DisplayName("随机 TTL 计算 - 基础值")
    void testCalculateRandomTtl_base() {
        long baseTtl = 600L;
        long randomTtl = protector.calculateRandomTtl(baseTtl);
        
        // 随机 TTL 应该在 [baseTtl + 60, baseTtl + 300] 范围内
        assertTrue(randomTtl >= baseTtl + 60);
        assertTrue(randomTtl <= baseTtl + 300);
    }
    
    @RepeatedTest(100)
    @DisplayName("随机 TTL 计算 - 多次验证随机性")
    void testCalculateRandomTtl_randomness() {
        long baseTtl = 600L;
        long randomTtl = protector.calculateRandomTtl(baseTtl);
        
        // 验证范围
        assertTrue(randomTtl >= 660L, "随机 TTL 最小值应为 660");
        assertTrue(randomTtl <= 900L, "随机 TTL 最大值应为 900");
    }
    
    @Test
    @DisplayName("随机 TTL 计算 - 零基础值")
    void testCalculateRandomTtl_zeroBase() {
        long baseTtl = 0L;
        long randomTtl = protector.calculateRandomTtl(baseTtl);
        
        assertTrue(randomTtl >= 60);
        assertTrue(randomTtl <= 300);
    }
    
    @Test
    @DisplayName("随机 TTL 计算 - 大数值")
    void testCalculateRandomTtl_largeBase() {
        long baseTtl = 86400L; // 1 天
        long randomTtl = protector.calculateRandomTtl(baseTtl);
        
        assertTrue(randomTtl >= baseTtl + 60);
        assertTrue(randomTtl <= baseTtl + 300);
    }
}
