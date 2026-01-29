package com.ecommerce.cache.optimization;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 增强版优化控制器
 * 
 * 提供对所有增强优化功能的访问接口
 */
@RestController
@RequestMapping("/api/enhanced/optimization")
public class EnhancedOptimizationController {

    @Autowired(required = false)
    private EnhancedCacheCoordinator cacheCoordinator;
    
    @Autowired(required = false)
    private AdvancedPerformanceAnalyzer performanceAnalyzer;
    
    @Autowired(required = false)
    private CacheOptimizationHub optimizationHub;

    /**
     * 获取增强版优化状态
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        
        status.put("enhancedComponentsInitialized", true);
        status.put("cacheCoordinatorAvailable", cacheCoordinator != null);
        status.put("performanceAnalyzerAvailable", performanceAnalyzer != null);
        status.put("optimizationHubAvailable", optimizationHub != null);
        
        if (cacheCoordinator != null) {
            status.put("cacheCoordinatorStatus", cacheCoordinator.getStats());
        }
        
        if (performanceAnalyzer != null) {
            status.put("performanceAnalyzerStatus", performanceAnalyzer.getMetrics());
        }
        
        if (optimizationHub != null) {
            status.put("optimizationHubStatus", optimizationHub.getOptimizationStats());
        }
        
        return status;
    }

    /**
     * 获取增强版性能指标
     */
    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        if (cacheCoordinator != null) {
            metrics.put("cacheCoordinatorMetrics", cacheCoordinator.getStats());
        }
        
        if (performanceAnalyzer != null) {
            metrics.put("performanceMetrics", performanceAnalyzer.getMetrics());
            metrics.put("performanceRecommendations", performanceAnalyzer.generateRecommendations().getRecommendations());
        }
        
        if (optimizationHub != null) {
            metrics.put("optimizationHubMetrics", optimizationHub.getOptimizationStats());
        }
        
        return metrics;
    }

    /**
     * 执行全局优化
     */
    @PostMapping("/optimize/global")
    public Map<String, Object> performGlobalOptimization() {
        Map<String, Object> result = new HashMap<>();
        
        if (optimizationHub != null) {
            optimizationHub.performGlobalOptimizations();
            result.put("globalOptimizationPerformed", true);
            result.put("timestamp", System.currentTimeMillis());
        } else {
            result.put("globalOptimizationPerformed", false);
            result.put("message", "Optimization hub not available");
        }
        
        result.put("success", true);
        return result;
    }

    /**
     * 执行紧急错误纠正
     */
    @PostMapping("/correction/emergency")
    public Map<String, Object> performEmergencyCorrection() {
        Map<String, Object> result = new HashMap<>();
        
        if (optimizationHub != null) {
            optimizationHub.performEmergencyCorrection();
            result.put("emergencyCorrectionPerformed", true);
            result.put("timestamp", System.currentTimeMillis());
        } else {
            result.put("emergencyCorrectionPerformed", false);
            result.put("message", "Optimization hub not available");
        }
        
        result.put("success", true);
        return result;
    }

    /**
     * 获取缓存协调器统计
     */
    @GetMapping("/cache/coordinator/stats")
    public Map<String, Object> getCacheCoordinatorStats() {
        Map<String, Object> result = new HashMap<>();
        
        if (cacheCoordinator != null) {
            result.put("stats", cacheCoordinator.getStats());
            result.put("success", true);
        } else {
            result.put("success", false);
            result.put("message", "Cache coordinator not available");
        }
        
        return result;
    }

    /**
     * 获取性能分析结果
     */
    @GetMapping("/performance/analysis")
    public Map<String, Object> getPerformanceAnalysis() {
        Map<String, Object> result = new HashMap<>();
        
        if (performanceAnalyzer != null) {
            result.put("metrics", performanceAnalyzer.getMetrics());
            result.put("recommendations", performanceAnalyzer.generateRecommendations().getRecommendations());
            result.put("success", true);
        } else {
            result.put("success", false);
            result.put("message", "Performance analyzer not available");
        }
        
        return result;
    }

    /**
     * 获取优化建议
     */
    @GetMapping("/recommendations")
    public Map<String, Object> getRecommendations() {
        Map<String, Object> result = new HashMap<>();
        
        if (performanceAnalyzer != null) {
            result.put("recommendations", performanceAnalyzer.generateRecommendations().getRecommendations());
            result.put("hasRecommendations", performanceAnalyzer.generateRecommendations().hasRecommendations());
            result.put("success", true);
        } else {
            result.put("success", false);
            result.put("message", "Performance analyzer not available");
        }
        
        return result;
    }

    /**
     * 模拟缓存操作以收集性能数据
     */
    @PostMapping("/simulate/cache-operation")
    public Map<String, Object> simulateCacheOperation(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        
        String key = request.get("key") != null ? request.get("key").toString() : "default_key";
        String operation = request.get("operation") != null ? request.get("operation").toString() : "get";
        
        if ("get".equals(operation)) {
            if (cacheCoordinator != null) {
                Object value = cacheCoordinator.get(key, Object.class);
                result.put("value", value);
                result.put("operation", "get");
            }
        } else if ("set".equals(operation)) {
            Object value = request.get("value") != null ? request.get("value") : "default_value";
            if (cacheCoordinator != null) {
                cacheCoordinator.put(key, value);
                result.put("operation", "set");
            }
        }
        
        result.put("key", key);
        result.put("success", true);
        result.put("timestamp", System.currentTimeMillis());
        
        return result;
    }
}