package com.ecommerce.cache.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存雪崩保护器
 * 随机过期时间 + 定时预热，防止大量缓存同时失效
 * 预热比例：30%，随机扰动：1-5 分钟
 */
@Service
public class CacheAvalancheProtector {
    
    private static final Logger log = LoggerFactory.getLogger(CacheAvalancheProtector.class);
    
    // 随机扰动范围：1-5 分钟（秒）
    private static final int MIN_RANDOM_SECONDS = 60;
    private static final int MAX_RANDOM_SECONDS = 300;
    
    // 预热比例：30%
    private static final double PREHEAT_RATIO = 0.3;
    
    @Value("${multilevel-cache.caffeine.expire-after-write:300}")
    private long baseExpireSeconds;
    
    private final L2RedisService l2RedisService;
    private final L3MemcachedService l3MemcachedService;
    private final HotKeyDetectorService hotKeyDetectorService;

    public CacheAvalancheProtector(L2RedisService l2RedisService,
                                    L3MemcachedService l3MemcachedService,
                                    HotKeyDetectorService hotKeyDetectorService) {
        this.l2RedisService = l2RedisService;
        this.l3MemcachedService = l3MemcachedService;
        this.hotKeyDetectorService = hotKeyDetectorService;
    }

    /**
     * 计算带随机扰动的过期时间
     * 在基础过期时间上增加 1-5 分钟随机值，防止批量过期
     * 
     * @param baseTtlSeconds 基础过期时间（秒）
     * @return 添加随机扰动后的过期时间
     */
    public long calculateRandomTtl(long baseTtlSeconds) {
        int randomSeconds = ThreadLocalRandom.current().nextInt(MIN_RANDOM_SECONDS, MAX_RANDOM_SECONDS + 1);
        return baseTtlSeconds + randomSeconds;
    }

    /**
     * 计算带随机扰动的过期时间（使用默认基础时间）
     */
    public long calculateRandomTtl() {
        return calculateRandomTtl(baseExpireSeconds);
    }

    /**
     * 定时预热热点 Key（每 5 分钟执行）
     * 预热 30% 的热 Key，防止批量过期导致雪崩
     */
    @Scheduled(fixedRate = 300000) // 5 分钟
    public void preheatHotKeys() {
        log.info("Starting hot key preheat task...");
        
        try {
            // 获取热点 Key 列表（Top 1000）
            List<String> hotKeys = hotKeyDetectorService.getTopHotKeys(1000);
            
            if (hotKeys.isEmpty()) {
                log.debug("No hot keys to preheat");
                return;
            }
            
            // 计算需要预热的数量（30%）
            int preheatCount = Math.max(1, (int) (hotKeys.size() * PREHEAT_RATIO));
            
            // 随机选择 30% 进行预热
            List<String> keysToPreheat = hotKeys.stream()
                .filter(k -> ThreadLocalRandom.current().nextDouble() < PREHEAT_RATIO)
                .limit(preheatCount)
                .toList();
            
            int refreshed = 0;
            int failed = 0;
            
            for (String key : keysToPreheat) {
                try {
                    // 检查 Key 是否即将过期（TTL < 2 分钟）
                    Long ttl = l2RedisService.ttl(key);
                    if (ttl != null && ttl > 0 && ttl < 120) {
                        // 刷新过期时间
                        long newTtl = calculateRandomTtl();
                        l2RedisService.expire(key, newTtl);
                        refreshed++;
                        log.debug("Preheated key: {}, newTtl: {}s", key, newTtl);
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("Failed to preheat key: {}", key, e);
                }
            }
            
            log.info("Hot key preheat completed: total={}, refreshed={}, failed={}", 
                keysToPreheat.size(), refreshed, failed);
            
        } catch (Exception e) {
            log.error("Hot key preheat task failed", e);
        }
    }

    /**
     * 手动预热指定 Key
     */
    public void preheatKey(String key, String value, long ttlSeconds) {
        long randomTtl = calculateRandomTtl(ttlSeconds);
        l2RedisService.set(key, value, randomTtl);
        log.info("Key preheated: {}, ttl: {}s", key, randomTtl);
    }

    /**
     * 批量预热
     */
    public int preheatKeys(java.util.Map<String, String> keyValues, long ttlSeconds) {
        int count = 0;
        for (var entry : keyValues.entrySet()) {
            try {
                preheatKey(entry.getKey(), entry.getValue(), ttlSeconds);
                count++;
            } catch (Exception e) {
                log.warn("Failed to preheat key: {}", entry.getKey(), e);
            }
        }
        return count;
    }

    /**
     * 检查是否存在雪崩风险
     * 统计即将过期的 Key 数量，超过阈值则预警
     */
    @Scheduled(fixedRate = 60000) // 1 分钟
    public void checkAvalancheRisk() {
        try {
            List<String> hotKeys = hotKeyDetectorService.getTopHotKeys(100);
            
            int expiringCount = 0;
            for (String key : hotKeys) {
                Long ttl = l2RedisService.ttl(key);
                if (ttl != null && ttl > 0 && ttl < 60) { // 1 分钟内过期
                    expiringCount++;
                }
            }
            
            // 超过 20% 的热点 Key 即将过期，触发预警
            if (expiringCount > hotKeys.size() * 0.2) {
                log.warn("Avalanche risk detected! {} hot keys expiring within 1 minute", expiringCount);
                // 可以触发告警或自动预热
                triggerEmergencyPreheat(hotKeys);
            }
        } catch (Exception e) {
            log.error("Avalanche risk check failed", e);
        }
    }

    /**
     * 紧急预热（发现雪崩风险时触发）
     */
    private void triggerEmergencyPreheat(List<String> keys) {
        log.warn("Triggering emergency preheat for {} keys", keys.size());
        
        for (String key : keys) {
            try {
                Long ttl = l2RedisService.ttl(key);
                if (ttl != null && ttl > 0 && ttl < 120) {
                    long newTtl = calculateRandomTtl();
                    l2RedisService.expire(key, newTtl);
                }
            } catch (Exception e) {
                log.warn("Emergency preheat failed for key: {}", key, e);
            }
        }
    }

    /**
     * 获取雪崩风险统计
     */
    public AvalancheRiskStats getRiskStats() {
        List<String> hotKeys = hotKeyDetectorService.getTopHotKeys(100);
        
        int expiring1Min = 0;
        int expiring5Min = 0;
        int expiring10Min = 0;
        
        for (String key : hotKeys) {
            Long ttl = l2RedisService.ttl(key);
            if (ttl != null && ttl > 0) {
                if (ttl < 60) expiring1Min++;
                else if (ttl < 300) expiring5Min++;
                else if (ttl < 600) expiring10Min++;
            }
        }
        
        return new AvalancheRiskStats(
            hotKeys.size(),
            expiring1Min,
            expiring5Min,
            expiring10Min,
            expiring1Min > hotKeys.size() * 0.2 ? "HIGH" : 
                expiring5Min > hotKeys.size() * 0.3 ? "MEDIUM" : "LOW"
        );
    }

    public record AvalancheRiskStats(
        int totalHotKeys,
        int expiring1Min,
        int expiring5Min,
        int expiring10Min,
        String riskLevel
    ) {}
}
