package com.ecommerce.cache.optimization.v9;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * V9极限优化配置属性
 * 
 * 核心特性:
 * 1. CRDT: 无冲突复制数据类型
 * 2. 向量时钟: 精确因果一致性
 * 3. 边缘计算: 就近访问网关
 * 4. ML预热: 机器学习驱动预热
 */
@Data
@Component
@ConfigurationProperties(prefix = "optimization.v9")
public class OptimizationV9Properties {
    
    private boolean enabled = true;
    
    private CRDTConfig crdt = new CRDTConfig();
    private VectorClockConfig vectorClock = new VectorClockConfig();
    private EdgeComputeConfig edgeCompute = new EdgeComputeConfig();
    private MLWarmupConfig mlWarmup = new MLWarmupConfig();
    private CoordinatorConfig coordinator = new CoordinatorConfig();
    
    @Data
    public static class CRDTConfig {
        private boolean enabled = true;
        private String defaultType = "G_COUNTER";
        private int gcIntervalSec = 300;
        private int maxTombstones = 10000;
        private boolean compressionEnabled = true;
    }
    
    @Data
    public static class VectorClockConfig {
        private boolean enabled = true;
        private int maxNodes = 100;
        private int pruneThreshold = 1000;
        private int snapshotIntervalSec = 60;
    }
    
    @Data
    public static class EdgeComputeConfig {
        private boolean enabled = true;
        private int maxEdgeNodes = 50;
        private int syncIntervalMs = 100;
        private double localityWeight = 0.7;
        private int ttlSec = 300;
    }
    
    @Data
    public static class MLWarmupConfig {
        private boolean enabled = true;
        private String modelType = "LSTM";
        private int predictionWindow = 60;
        private double confidenceThreshold = 0.8;
        private int batchSize = 100;
        private int maxPrefetch = 1000;
    }
    
    @Data
    public static class CoordinatorConfig {
        private boolean enabled = true;
        private int workerThreads = 4;
        private int queueCapacity = 5000;
        private int healthCheckIntervalSec = 15;
    }
}
