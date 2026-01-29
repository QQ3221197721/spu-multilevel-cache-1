package com.ecommerce.cache.service;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * 布隆过滤器服务
 * 用于缓存穿透防护
 * 预期元素：1 亿，误判率：< 0.5%
 */
@Service
public class BloomFilterService {
    
    private static final Logger log = LoggerFactory.getLogger(BloomFilterService.class);
    
    @Value("${bloom-filter.expected-insertions:100000000}")
    private long expectedInsertions;
    
    @Value("${bloom-filter.false-positive-rate:0.005}")
    private double falsePositiveRate;
    
    @Value("${bloom-filter.name:spu:bloom:filter}")
    private String filterName;
    
    private final RedissonClient redissonClient;
    private RBloomFilter<String> spuBloomFilter;

    public BloomFilterService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @PostConstruct
    public void init() {
        spuBloomFilter = redissonClient.getBloomFilter(filterName);
        // 初始化布隆过滤器（仅首次）
        boolean initialized = spuBloomFilter.tryInit(expectedInsertions, falsePositiveRate);
        if (initialized) {
            log.info("Bloom filter initialized: name={}, expectedInsertions={}, falsePositiveRate={}", 
                filterName, expectedInsertions, falsePositiveRate);
        } else {
            log.info("Bloom filter already exists: name={}, count={}", filterName, spuBloomFilter.count());
        }
    }

    /**
     * 检查 SPU 是否可能存在
     * @param spuId SPU ID
     * @return true=可能存在，false=一定不存在
     */
    public boolean mightContain(String spuId) {
        try {
            return spuBloomFilter.contains(spuId);
        } catch (Exception e) {
            log.error("Bloom filter check error, spuId: {}", spuId, e);
            // 出错时放行，避免影响正常请求
            return true;
        }
    }

    /**
     * 添加 SPU 到布隆过滤器
     */
    public boolean add(String spuId) {
        try {
            return spuBloomFilter.add(spuId);
        } catch (Exception e) {
            log.error("Bloom filter add error, spuId: {}", spuId, e);
            return false;
        }
    }

    /**
     * 批量添加
     */
    public long addAll(Iterable<String> spuIds) {
        long count = 0;
        for (String spuId : spuIds) {
            if (add(spuId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取已添加元素数量
     */
    public long count() {
        try {
            return spuBloomFilter.count();
        } catch (Exception e) {
            log.error("Bloom filter count error", e);
            return -1;
        }
    }

    /**
     * 获取布隆过滤器统计信息
     */
    public BloomFilterStats getStats() {
        return new BloomFilterStats(
            spuBloomFilter.count(),
            spuBloomFilter.getExpectedInsertions(),
            spuBloomFilter.getFalseProbability(),
            spuBloomFilter.getHashIterations(),
            spuBloomFilter.getSize()
        );
    }

    /**
     * 重置布隆过滤器（谨慎使用）
     */
    public void reset() {
        log.warn("Resetting bloom filter: {}", filterName);
        spuBloomFilter.delete();
        spuBloomFilter.tryInit(expectedInsertions, falsePositiveRate);
        log.info("Bloom filter reset completed");
    }

    /**
     * 布隆过滤器统计信息
     */
    public record BloomFilterStats(
        long count,
        long expectedInsertions,
        double falseProbability,
        int hashIterations,
        long bitSize
    ) {
        /**
         * 计算当前填充率
         */
        public double fillRate() {
            return expectedInsertions > 0 ? (double) count / expectedInsertions : 0;
        }

        /**
         * 计算实际误判率（估算）
         */
        public double estimatedFalsePositiveRate() {
            if (bitSize <= 0 || hashIterations <= 0) return falseProbability;
            double p = Math.pow(1 - Math.exp(-hashIterations * count / (double) bitSize), hashIterations);
            return Math.min(p, 1.0);
        }
    }
}
