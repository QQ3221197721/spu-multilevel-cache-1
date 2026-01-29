package com.ecommerce.cache.optimization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 优化模块管理控制器
 * 
 * 提供统一的优化功能管理和监控接口
 */
@RestController
@RequestMapping("/api/optimization")
public class OptimizationController {
    
    private static final Logger log = LoggerFactory.getLogger(OptimizationController.class);
    
    private final AdvancedCacheStrategy cacheStrategy;
    private final DynamicConfigCenter configCenter;
    private final FeatureToggleService featureToggleService;
    private final ABTestService abTestService;
    private final EnhancedHealthService healthService;
    private final SmartRateLimiter rateLimiter;
    private final PerformanceInterceptor performanceInterceptor;
    private final CircuitBreakerEnhancer circuitBreaker;
    private final DatabaseOptimizer databaseOptimizer;
    private final SelfHealingService selfHealingService;
    
    public OptimizationController(AdvancedCacheStrategy cacheStrategy,
                                  DynamicConfigCenter configCenter,
                                  FeatureToggleService featureToggleService,
                                  ABTestService abTestService,
                                  EnhancedHealthService healthService,
                                  SmartRateLimiter rateLimiter,
                                  PerformanceInterceptor performanceInterceptor,
                                  CircuitBreakerEnhancer circuitBreaker,
                                  DatabaseOptimizer databaseOptimizer,
                                  SelfHealingService selfHealingService) {
        this.cacheStrategy = cacheStrategy;
        this.configCenter = configCenter;
        this.featureToggleService = featureToggleService;
        this.abTestService = abTestService;
        this.healthService = healthService;
        this.rateLimiter = rateLimiter;
        this.performanceInterceptor = performanceInterceptor;
        this.circuitBreaker = circuitBreaker;
        this.databaseOptimizer = databaseOptimizer;
        this.selfHealingService = selfHealingService;
    }
    
    /**
     * 获取优化模块总览
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview() {
        Map<String, Object> overview = new HashMap<>();
        
        // 系统健康状态
        overview.put("health", Map.of(
            "status", healthService.getCurrentHealth().name(),
            "live", healthService.isLive(),
            "ready", healthService.isReady()
        ));
        
        // 缓存状态
        overview.put("cache", Map.of(
            "accessStats", cacheStrategy.getAccessStats(10).size(),
            "configVersion", configCenter.getAllConfigs().size()
        ));
        
        // 熔断器状态
        overview.put("circuitBreakers", circuitBreaker.getCircuitStates());
        
        // 慢请求
        overview.put("slowRequests", performanceInterceptor.getRecentSlowRequests().size());
        
        // 自愈统计
        overview.put("selfHealing", selfHealingService.getStats());
        
        return ResponseEntity.ok(overview);
    }
    
    /**
     * 获取完整健康报告
     */
    @GetMapping("/health/report")
    public ResponseEntity<EnhancedHealthService.HealthReport> getHealthReport() {
        return ResponseEntity.ok(healthService.performHealthCheck());
    }
    
    /**
     * 获取健康检查历史
     */
    @GetMapping("/health/history")
    public ResponseEntity<?> getHealthHistory() {
        return ResponseEntity.ok(healthService.getHealthHistory());
    }
    
    /**
     * 获取数据库优化报告
     */
    @GetMapping("/database/report")
    public ResponseEntity<DatabaseOptimizer.OptimizationReport> getDatabaseReport() {
        return ResponseEntity.ok(databaseOptimizer.getOptimizationReport());
    }
    
    /**
     * 获取缓存访问统计
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<?> getCacheStats(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(cacheStrategy.getAccessStats(limit));
    }
    
    /**
     * 获取慢请求列表
     */
    @GetMapping("/slow-requests")
    public ResponseEntity<?> getSlowRequests() {
        return ResponseEntity.ok(performanceInterceptor.getRecentSlowRequests());
    }
    
    /**
     * 获取熔断器状态
     */
    @GetMapping("/circuit-breakers")
    public ResponseEntity<?> getCircuitBreakers() {
        return ResponseEntity.ok(circuitBreaker.getCircuitStates());
    }
    
    // ========== 动态配置管理 ==========
    
    /**
     * 获取所有配置
     */
    @GetMapping("/config")
    public ResponseEntity<?> getAllConfigs() {
        return ResponseEntity.ok(configCenter.getAllConfigs());
    }
    
    /**
     * 获取单个配置
     */
    @GetMapping("/config/{key}")
    public ResponseEntity<?> getConfig(@PathVariable String key) {
        String value = configCenter.getConfig(key);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }
    
    /**
     * 设置配置
     */
    @PostMapping("/config")
    public ResponseEntity<?> setConfig(@RequestBody ConfigRequest request) {
        configCenter.setConfig(request.key(), request.value());
        return ResponseEntity.ok(Map.of("message", "Config updated", "key", request.key()));
    }
    
    /**
     * 删除配置
     */
    @DeleteMapping("/config/{key}")
    public ResponseEntity<?> deleteConfig(@PathVariable String key) {
        configCenter.deleteConfig(key);
        return ResponseEntity.ok(Map.of("message", "Config deleted", "key", key));
    }
    
    // ========== 特性开关管理 ==========
    
    /**
     * 获取所有特性开关
     */
    @GetMapping("/features")
    public ResponseEntity<?> getAllFeatures() {
        return ResponseEntity.ok(featureToggleService.getAllToggles());
    }
    
    /**
     * 检查特性是否启用
     */
    @GetMapping("/features/{name}/check")
    public ResponseEntity<?> checkFeature(@PathVariable String name, 
                                          @RequestParam(required = false) String userId) {
        boolean enabled = featureToggleService.isEnabled(name, userId);
        return ResponseEntity.ok(Map.of("feature", name, "enabled", enabled, "userId", userId));
    }
    
    /**
     * 设置特性开关
     */
    @PostMapping("/features")
    public ResponseEntity<?> setFeature(@RequestBody FeatureToggleService.FeatureToggle toggle) {
        featureToggleService.setToggle(toggle);
        return ResponseEntity.ok(Map.of("message", "Feature toggle updated", "feature", toggle.name()));
    }
    
    // ========== A/B 测试管理 ==========
    
    /**
     * 获取用户实验分桶
     */
    @GetMapping("/experiments/{name}/bucket")
    public ResponseEntity<?> getExperimentBucket(@PathVariable String name,
                                                  @RequestParam String userId) {
        String bucket = abTestService.getBucket(name, userId);
        return ResponseEntity.ok(Map.of("experiment", name, "userId", userId, "bucket", bucket));
    }
    
    /**
     * 设置实验
     */
    @PostMapping("/experiments")
    public ResponseEntity<?> setExperiment(@RequestBody ABTestService.Experiment experiment) {
        abTestService.setExperiment(experiment);
        return ResponseEntity.ok(Map.of("message", "Experiment updated", "experiment", experiment.name()));
    }
    
    /**
     * 记录实验转化
     */
    @PostMapping("/experiments/{name}/conversion")
    public ResponseEntity<?> recordConversion(@PathVariable String name,
                                               @RequestParam String userId,
                                               @RequestParam String metric) {
        abTestService.recordConversion(name, userId, metric);
        return ResponseEntity.ok(Map.of("message", "Conversion recorded"));
    }
    
    // ========== 运维操作 ==========
    
    /**
     * 手动触发 GC
     */
    @PostMapping("/ops/gc")
    public ResponseEntity<?> forceGc() {
        selfHealingService.forceGc();
        return ResponseEntity.ok(Map.of("message", "GC triggered"));
    }
    
    /**
     * 刷新所有配置
     */
    @PostMapping("/ops/refresh-config")
    public ResponseEntity<?> refreshConfig() {
        configCenter.refreshAllConfigs();
        return ResponseEntity.ok(Map.of("message", "Configs refreshed"));
    }
    
    /**
     * 清除特性开关缓存
     */
    @PostMapping("/ops/clear-feature-cache")
    public ResponseEntity<?> clearFeatureCache() {
        featureToggleService.clearCache();
        return ResponseEntity.ok(Map.of("message", "Feature cache cleared"));
    }
    
    // DTO
    public record ConfigRequest(String key, String value) {}
}
