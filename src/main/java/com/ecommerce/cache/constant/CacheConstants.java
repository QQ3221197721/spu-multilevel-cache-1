package com.ecommerce.cache.constant;

/**
 * 缓存相关常量
 */
public final class CacheConstants {
    
    private CacheConstants() {}
    
    // ==================== Key 前缀 ====================
    
    /** SPU 详情缓存前缀 */
    public static final String SPU_DETAIL_PREFIX = "spu:detail:";
    
    /** 热点 Key 统计前缀 */
    public static final String HOT_KEY_STATS_PREFIX = "hotkey:stats:";
    
    /** 热点 Key 分片前缀 */
    public static final String HOT_KEY_SHARD_PREFIX = ":shard:";
    
    /** 分布式锁前缀 */
    public static final String LOCK_PREFIX = "lock:cache:";
    
    /** 布隆过滤器 Key */
    public static final String BLOOM_FILTER_KEY = "spu:bloom:filter";
    
    // ==================== 缓存空值标识 ====================
    
    /** 空值占位符（防穿透） */
    public static final String EMPTY_VALUE = "[[EMPTY]]";
    
    /** 空值 TTL（秒） */
    public static final int EMPTY_VALUE_TTL = 60;
    
    // ==================== 默认配置 ====================
    
    /** L1 默认过期时间（秒） */
    public static final int L1_DEFAULT_TTL = 60;
    
    /** L2 默认过期时间（秒） */
    public static final int L2_DEFAULT_TTL = 600;
    
    /** L3 默认过期时间（秒） */
    public static final int L3_DEFAULT_TTL = 1800;
    
    /** 热点检测窗口（秒） */
    public static final int HOT_KEY_WINDOW_SECONDS = 10;
    
    /** 热点 QPS 阈值 */
    public static final int HOT_KEY_QPS_THRESHOLD = 5000;
    
    /** 热点分片数量 */
    public static final int HOT_KEY_SHARD_COUNT = 10;
    
    // ==================== MQ Topic ====================
    
    /** 缓存失效 Topic */
    public static final String TOPIC_CACHE_INVALIDATE = "SPU_CACHE_INVALIDATE";
    
    /** 本地缓存广播 Topic */
    public static final String TOPIC_LOCAL_CACHE_BROADCAST = "SPU_LOCAL_CACHE_BROADCAST";
    
    // ==================== 分布式锁配置 ====================
    
    /** 锁等待时间（毫秒） */
    public static final long LOCK_WAIT_TIME_MS = 500;
    
    /** 锁持有时间（毫秒） */
    public static final long LOCK_LEASE_TIME_MS = 3000;
    
    // ==================== 雪崩防护配置 ====================
    
    /** 随机 TTL 最小扰动（秒） */
    public static final int RANDOM_TTL_MIN = 60;
    
    /** 随机 TTL 最大扰动（秒） */
    public static final int RANDOM_TTL_MAX = 300;
}
