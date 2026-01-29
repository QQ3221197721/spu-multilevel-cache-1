package com.ecommerce.cache.optimization.v12;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * V12终极进化优化配置属性
 * 
 * 核心特性:
 * 1. 内存数据库引擎: 高速内存存储
 * 2. 流式计算缓存: 实时流处理
 * 3. 智能分片路由: 自适应分片
 * 4. 全息缓存快照: 一致性快照
 */
@Data
@Component
@ConfigurationProperties(prefix = "optimization.v12")
public class OptimizationV12Properties {
    
    private boolean enabled = true;
    
    private InMemoryDbConfig inMemoryDb = new InMemoryDbConfig();
    private StreamCacheConfig streamCache = new StreamCacheConfig();
    private ShardRouterConfig shardRouter = new ShardRouterConfig();
    private SnapshotConfig snapshot = new SnapshotConfig();
    private CoordinatorConfig coordinator = new CoordinatorConfig();
    
    @Data
    public static class InMemoryDbConfig {
        private boolean enabled = true;
        private long maxMemoryMb = 1024;
        private int tableCount = 64;
        private String indexType = "BTREE";
        private boolean walEnabled = true;
        private int checkpointIntervalSec = 300;
    }
    
    @Data
    public static class StreamCacheConfig {
        private boolean enabled = true;
        private int windowSizeSec = 60;
        private int maxEventsPerWindow = 100000;
        private String aggregationType = "TUMBLING";
        private int parallelism = 4;
        private int bufferSize = 10000;
    }
    
    @Data
    public static class ShardRouterConfig {
        private boolean enabled = true;
        private int virtualNodes = 1024;
        private String hashAlgorithm = "MURMUR3";
        private double rebalanceThreshold = 0.2;
        private int maxShardsPerNode = 32;
        private boolean autoRebalance = true;
    }
    
    @Data
    public static class SnapshotConfig {
        private boolean enabled = true;
        private int intervalSec = 60;
        private int maxSnapshots = 10;
        private boolean incrementalEnabled = true;
        private String storageType = "DISK";
        private String compressionCodec = "LZ4";
    }
    
    @Data
    public static class CoordinatorConfig {
        private boolean enabled = true;
        private int workerThreads = 8;
        private int queueCapacity = 20000;
        private int healthCheckIntervalSec = 5;
    }
}
