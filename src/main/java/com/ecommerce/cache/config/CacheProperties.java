package com.ecommerce.cache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 缓存配置属性类
 */
@Data
@Component
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {
    
    /** L1 本地缓存配置 */
    private L1Config l1 = new L1Config();
    
    /** L2 Redis 配置 */
    private L2Config l2 = new L2Config();
    
    /** L3 Memcached 配置 */
    private L3Config l3 = new L3Config();
    
    /** 布隆过滤器配置 */
    private BloomConfig bloom = new BloomConfig();
    
    /** 热点 Key 配置 */
    private HotKeyConfig hotKey = new HotKeyConfig();
    
    @Data
    public static class L1Config {
        /** 最大容量 */
        private int maxSize = 50000;
        /** 过期时间（秒） */
        private int expireSeconds = 60;
        /** 初始容量 */
        private int initialCapacity = 1000;
        /** 是否开启统计 */
        private boolean recordStats = true;
    }
    
    @Data
    public static class L2Config {
        /** 集群节点 */
        private List<String> nodes;
        /** 密码 */
        private String password;
        /** 默认过期时间（秒） */
        private int defaultTtlSeconds = 600;
        /** 最大重定向次数 */
        private int maxRedirects = 3;
        /** 连接池最大连接数 */
        private int maxTotal = 500;
        /** 连接池最大空闲连接数 */
        private int maxIdle = 100;
        /** 连接池最小空闲连接数 */
        private int minIdle = 50;
    }
    
    @Data
    public static class L3Config {
        /** 服务器地址列表 */
        private List<String> servers;
        /** 连接池大小 */
        private int poolSize = 50;
        /** 操作超时时间（毫秒） */
        private long opTimeoutMillis = 1000;
        /** 默认过期时间（秒） */
        private int defaultTtlSeconds = 1800;
    }
    
    @Data
    public static class BloomConfig {
        /** 预期插入数量 */
        private long expectedInsertions = 100_000_000L;
        /** 误判率 */
        private double falseProbability = 0.005;
        /** Redis Key 名称 */
        private String redisKey = "spu:bloom:filter";
    }
    
    @Data
    public static class HotKeyConfig {
        /** 检测窗口大小（秒） */
        private int windowSizeSeconds = 10;
        /** QPS 阈值 */
        private int qpsThreshold = 5000;
        /** 分片数量 */
        private int shardCount = 10;
        /** 统计 Key 前缀 */
        private String statsKeyPrefix = "hotkey:stats:";
    }
}
