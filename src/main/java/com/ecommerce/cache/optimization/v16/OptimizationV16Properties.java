package com.ecommerce.cache.optimization.v16;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * V16量子纠错优化模块属性配置
 * 
 * 包含量子纠错系统的所有可配置参数
 */
@ConfigurationProperties(prefix = "v16")
public class OptimizationV16Properties {

    private boolean enabled = true;
    private double thresholdFidelity = 0.999999;
    private long maxCorrectionLatencyNs = 100000; // 0.1毫秒
    private int resourceUtilizationPercentage = 95;
    
    // 表面码缓存层配置
    private SurfaceCodeCacheProperties surfaceCodeCache = new SurfaceCodeCacheProperties();
    
    // 色码缓存层配置
    private ColorCodeCacheProperties colorCodeCache = new ColorCodeCacheProperties();
    
    // 拓扑保护层配置
    private TopologicalProtectionProperties topologicalProtection = new TopologicalProtectionProperties();
    
    // 动态纠错调度器配置
    private DynamicSchedulerProperties dynamicScheduler = new DynamicSchedulerProperties();

    // Getters and Setters
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getThresholdFidelity() {
        return thresholdFidelity;
    }

    public void setThresholdFidelity(double thresholdFidelity) {
        this.thresholdFidelity = thresholdFidelity;
    }

    public long getMaxCorrectionLatencyNs() {
        return maxCorrectionLatencyNs;
    }

    public void setMaxCorrectionLatencyNs(long maxCorrectionLatencyNs) {
        this.maxCorrectionLatencyNs = maxCorrectionLatencyNs;
    }

    public int getResourceUtilizationPercentage() {
        return resourceUtilizationPercentage;
    }

    public void setResourceUtilizationPercentage(int resourceUtilizationPercentage) {
        this.resourceUtilizationPercentage = resourceUtilizationPercentage;
    }

    public SurfaceCodeCacheProperties getSurfaceCodeCache() {
        return surfaceCodeCache;
    }

    public void setSurfaceCodeCache(SurfaceCodeCacheProperties surfaceCodeCache) {
        this.surfaceCodeCache = surfaceCodeCache;
    }

    public ColorCodeCacheProperties getColorCodeCache() {
        return colorCodeCache;
    }

    public void setColorCodeCache(ColorCodeCacheProperties colorCodeCache) {
        this.colorCodeCache = colorCodeCache;
    }

    public TopologicalProtectionProperties getTopologicalProtection() {
        return topologicalProtection;
    }

    public void setTopologicalProtection(TopologicalProtectionProperties topologicalProtection) {
        this.topologicalProtection = topologicalProtection;
    }

    public DynamicSchedulerProperties getDynamicScheduler() {
        return dynamicScheduler;
    }

    public void setDynamicScheduler(DynamicSchedulerProperties dynamicScheduler) {
        this.dynamicScheduler = dynamicScheduler;
    }

    /**
     * 表面码缓存层属性
     */
    public static class SurfaceCodeCacheProperties {
        private boolean enabled = true;
        private int latticeSizeX = 10;
        private int latticeSizeY = 10;
        private double initialFidelity = 0.999;
        private long measurementIntervalNs = 50000; // 50微秒
        private double errorCorrectionThreshold = 0.05; // 5%错误率阈值

        // Getters and Setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLatticeSizeX() {
            return latticeSizeX;
        }

        public void setLatticeSizeX(int latticeSizeX) {
            this.latticeSizeX = latticeSizeX;
        }

        public int getLatticeSizeY() {
            return latticeSizeY;
        }

        public void setLatticeSizeY(int latticeSizeY) {
            this.latticeSizeY = latticeSizeY;
        }

        public double getInitialFidelity() {
            return initialFidelity;
        }

        public void setInitialFidelity(double initialFidelity) {
            this.initialFidelity = initialFidelity;
        }

        public long getMeasurementIntervalNs() {
            return measurementIntervalNs;
        }

        public void setMeasurementIntervalNs(long measurementIntervalNs) {
            this.measurementIntervalNs = measurementIntervalNs;
        }

        public double getErrorCorrectionThreshold() {
            return errorCorrectionThreshold;
        }

        public void setErrorCorrectionThreshold(double errorCorrectionThreshold) {
            this.errorCorrectionThreshold = errorCorrectionThreshold;
        }
    }

    /**
     * 色码缓存层属性
     */
    public static class ColorCodeCacheProperties {
        private boolean enabled = true;
        private int latticeSizeX = 8;
        private int latticeSizeY = 8;
        private int latticeSizeZ = 8;
        private double initialFidelity = 0.999995;
        private long measurementIntervalNs = 75000; // 75微秒
        private double highOrderErrorThreshold = 0.01; // 1%高阶错误阈值
        private double encodingRate = 0.25; // 1/4 编码率

        // Getters and Setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLatticeSizeX() {
            return latticeSizeX;
        }

        public void setLatticeSizeX(int latticeSizeX) {
            this.latticeSizeX = latticeSizeX;
        }

        public int getLatticeSizeY() {
            return latticeSizeY;
        }

        public void setLatticeSizeY(int latticeSizeY) {
            this.latticeSizeY = latticeSizeY;
        }

        public int getLatticeSizeZ() {
            return latticeSizeZ;
        }

        public void setLatticeSizeZ(int latticeSizeZ) {
            this.latticeSizeZ = latticeSizeZ;
        }

        public double getInitialFidelity() {
            return initialFidelity;
        }

        public void setInitialFidelity(double initialFidelity) {
            this.initialFidelity = initialFidelity;
        }

        public long getMeasurementIntervalNs() {
            return measurementIntervalNs;
        }

        public void setMeasurementIntervalNs(long measurementIntervalNs) {
            this.measurementIntervalNs = measurementIntervalNs;
        }

        public double getHighOrderErrorThreshold() {
            return highOrderErrorThreshold;
        }

        public void setHighOrderErrorThreshold(double highOrderErrorThreshold) {
            this.highOrderErrorThreshold = highOrderErrorThreshold;
        }

        public double getEncodingRate() {
            return encodingRate;
        }

        public void setEncodingRate(double encodingRate) {
            this.encodingRate = encodingRate;
        }
    }

    /**
     * 拓扑保护层属性
     */
    public static class TopologicalProtectionProperties {
        private boolean enabled = true;
        private double initialFidelity = 0.999999999;
        private long protectionIntervalNs = 200000; // 200微秒
        private double decoherenceSuppressionFactor = 1_000_000; // 退相干抑制倍数
        private String protectionDuration = ">=1 year"; // 保护持续时间
        private double topologicalErrorRate = 1e-12; // 拓扑错误率

        // Getters and Setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getInitialFidelity() {
            return initialFidelity;
        }

        public void setInitialFidelity(double initialFidelity) {
            this.initialFidelity = initialFidelity;
        }

        public long getProtectionIntervalNs() {
            return protectionIntervalNs;
        }

        public void setProtectionIntervalNs(long protectionIntervalNs) {
            this.protectionIntervalNs = protectionIntervalNs;
        }

        public double getDecoherenceSuppressionFactor() {
            return decoherenceSuppressionFactor;
        }

        public void setDecoherenceSuppressionFactor(double decoherenceSuppressionFactor) {
            this.decoherenceSuppressionFactor = decoherenceSuppressionFactor;
        }

        public String getProtectionDuration() {
            return protectionDuration;
        }

        public void setProtectionDuration(String protectionDuration) {
            this.protectionDuration = protectionDuration;
        }

        public double getTopologicalErrorRate() {
            return topologicalErrorRate;
        }

        public void setTopologicalErrorRate(double topologicalErrorRate) {
            this.topologicalErrorRate = topologicalErrorRate;
        }
    }

    /**
     * 动态纠错调度器属性
     */
    public static class DynamicSchedulerProperties {
        private boolean enabled = true;
        private long schedulingIntervalNs = 100000; // 100微秒
        private long executionIntervalNs = 50000; // 50微秒
        private long adaptiveAdjustmentIntervalNs = 1000000; // 1毫秒
        private int queueCapacity = 10000;
        private double resourceUtilizationTarget = 95.0; // 目标资源利用率

        // Getters and Setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getSchedulingIntervalNs() {
            return schedulingIntervalNs;
        }

        public void setSchedulingIntervalNs(long schedulingIntervalNs) {
            this.schedulingIntervalNs = schedulingIntervalNs;
        }

        public long getExecutionIntervalNs() {
            return executionIntervalNs;
        }

        public void setExecutionIntervalNs(long executionIntervalNs) {
            this.executionIntervalNs = executionIntervalNs;
        }

        public long getAdaptiveAdjustmentIntervalNs() {
            return adaptiveAdjustmentIntervalNs;
        }

        public void setAdaptiveAdjustmentIntervalNs(long adaptiveAdjustmentIntervalNs) {
            this.adaptiveAdjustmentIntervalNs = adaptiveAdjustmentIntervalNs;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public double getResourceUtilizationTarget() {
            return resourceUtilizationTarget;
        }

        public void setResourceUtilizationTarget(double resourceUtilizationTarget) {
            this.resourceUtilizationTarget = resourceUtilizationTarget;
        }
    }
}