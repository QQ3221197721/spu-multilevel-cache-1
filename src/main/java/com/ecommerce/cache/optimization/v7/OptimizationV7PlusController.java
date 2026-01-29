package com.ecommerce.cache.optimization.v7;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * V7+增强控制器
 * 提供事件总线、限流器、一致性检查等高级功能的REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/v7/plus")
@RequiredArgsConstructor
public class OptimizationV7PlusController {
    
    private final CacheEventBus eventBus;
    private final AdaptiveRateLimiter rateLimiter;
    private final CacheConsistencyChecker consistencyChecker;
    
    // ==================== 事件总线API ====================
    
    /**
     * 获取事件总线统计
     */
    @GetMapping("/event-bus/stats")
    public ResponseEntity<Map<String, Object>> getEventBusStats() {
        return ResponseEntity.ok(eventBus.getStatistics());
    }
    
    /**
     * 发布事件
     */
    @PostMapping("/event-bus/publish")
    public ResponseEntity<Map<String, Object>> publishEvent(
            @RequestParam String type,
            @RequestParam String key,
            @RequestBody(required = false) Object data) {
        try {
            CacheEventBus.CacheEventType eventType = CacheEventBus.CacheEventType.valueOf(type);
            eventBus.publish(eventType, key, data);
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("type", type);
            result.put("key", key);
            result.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", "Invalid event type: " + type);
            return ResponseEntity.badRequest().body(error);
        }
    }
    
    /**
     * 发布高优先级事件
     */
    @PostMapping("/event-bus/publish/high-priority")
    public ResponseEntity<Map<String, Object>> publishHighPriorityEvent(
            @RequestParam String type,
            @RequestParam String key,
            @RequestBody(required = false) Object data) {
        try {
            CacheEventBus.CacheEventType eventType = CacheEventBus.CacheEventType.valueOf(type);
            eventBus.publishHighPriority(eventType, key, data);
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("type", type);
            result.put("key", key);
            result.put("priority", "HIGH");
            
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid event type"));
        }
    }
    
    /**
     * 获取可用事件类型
     */
    @GetMapping("/event-bus/types")
    public ResponseEntity<List<String>> getEventTypes() {
        return ResponseEntity.ok(
            Arrays.stream(CacheEventBus.CacheEventType.values())
                .map(Enum::name)
                .toList()
        );
    }
    
    // ==================== 限流器API ====================
    
    /**
     * 获取限流器统计
     */
    @GetMapping("/rate-limiter/stats")
    public ResponseEntity<Map<String, Object>> getRateLimiterStats() {
        return ResponseEntity.ok(rateLimiter.getStatistics());
    }
    
    /**
     * 尝试获取限流许可
     */
    @PostMapping("/rate-limiter/acquire")
    public ResponseEntity<Map<String, Object>> tryAcquire(
            @RequestParam String key,
            @RequestParam(defaultValue = "default") String rule) {
        boolean permitted = rateLimiter.tryAcquire(key, rule);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("rule", rule);
        result.put("permitted", permitted);
        result.put("waitTimeMs", rateLimiter.getWaitTime(key, rule));
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 分布式限流
     */
    @PostMapping("/rate-limiter/acquire/distributed")
    public ResponseEntity<Map<String, Object>> tryAcquireDistributed(
            @RequestParam String key,
            @RequestParam(defaultValue = "default") String rule) {
        boolean permitted = rateLimiter.tryAcquireDistributed(key, rule);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("rule", rule);
        result.put("permitted", permitted);
        result.put("distributed", true);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 批量检查限流
     */
    @PostMapping("/rate-limiter/acquire/batch")
    public ResponseEntity<Map<String, Boolean>> tryAcquireBatch(
            @RequestBody List<String> keys,
            @RequestParam(defaultValue = "default") String rule) {
        return ResponseEntity.ok(rateLimiter.tryAcquireBatch(keys, rule));
    }
    
    /**
     * 添加限流规则
     */
    @PostMapping("/rate-limiter/rules")
    public ResponseEntity<Map<String, Object>> addRule(
            @RequestParam String name,
            @RequestParam int qps,
            @RequestParam(defaultValue = "0") int burst,
            @RequestParam(defaultValue = "TOKEN_BUCKET") String algorithm) {
        try {
            AdaptiveRateLimiter.Algorithm algo = AdaptiveRateLimiter.Algorithm.valueOf(algorithm);
            
            rateLimiter.addRule(name, AdaptiveRateLimiter.RateLimitRule.builder()
                .name(name)
                .qps(qps)
                .burst(burst > 0 ? burst : qps * 2)
                .algorithm(algo)
                .build());
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("rule", name);
            result.put("qps", qps);
            result.put("burst", burst > 0 ? burst : qps * 2);
            result.put("algorithm", algorithm);
            
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid algorithm"));
        }
    }
    
    /**
     * 更新限流规则
     */
    @PutMapping("/rate-limiter/rules/{name}")
    public ResponseEntity<Map<String, Object>> updateRule(
            @PathVariable String name,
            @RequestParam int qps,
            @RequestParam int burst) {
        rateLimiter.updateRule(name, qps, burst);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("rule", name);
        result.put("newQps", qps);
        result.put("newBurst", burst);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 删除限流规则
     */
    @DeleteMapping("/rate-limiter/rules/{name}")
    public ResponseEntity<Map<String, Object>> removeRule(@PathVariable String name) {
        rateLimiter.removeRule(name);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("removed", name);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 设置系统负载因子
     */
    @PostMapping("/rate-limiter/load-factor")
    public ResponseEntity<Map<String, Object>> setLoadFactor(@RequestParam double factor) {
        rateLimiter.setSystemLoadFactor(factor);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("loadFactor", factor);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取可用算法
     */
    @GetMapping("/rate-limiter/algorithms")
    public ResponseEntity<List<String>> getAlgorithms() {
        return ResponseEntity.ok(
            Arrays.stream(AdaptiveRateLimiter.Algorithm.values())
                .map(Enum::name)
                .toList()
        );
    }
    
    // ==================== 一致性检查API ====================
    
    /**
     * 获取一致性检查统计
     */
    @GetMapping("/consistency/stats")
    public ResponseEntity<Map<String, Object>> getConsistencyStats() {
        return ResponseEntity.ok(consistencyChecker.getStatistics());
    }
    
    /**
     * 检查单个Key一致性
     */
    @GetMapping("/consistency/check")
    public ResponseEntity<CacheConsistencyChecker.ConsistencyResult> checkConsistency(
            @RequestParam String key) {
        return ResponseEntity.ok(consistencyChecker.checkKey(key));
    }
    
    /**
     * 批量检查一致性
     */
    @PostMapping("/consistency/check/batch")
    public ResponseEntity<List<CacheConsistencyChecker.ConsistencyResult>> checkConsistencyBatch(
            @RequestBody List<String> keys) {
        return ResponseEntity.ok(consistencyChecker.checkBatch(keys));
    }
    
    /**
     * 检查并修复
     */
    @PostMapping("/consistency/fix")
    public ResponseEntity<CacheConsistencyChecker.FixResult> checkAndFix(
            @RequestParam String key,
            @RequestParam(defaultValue = "L2_WINS") String strategy) {
        try {
            CacheConsistencyChecker.FixStrategy fixStrategy = 
                CacheConsistencyChecker.FixStrategy.valueOf(strategy);
            return ResponseEntity.ok(consistencyChecker.checkAndFix(key, fixStrategy));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 修复所有不一致
     */
    @PostMapping("/consistency/fix/all")
    public ResponseEntity<Map<String, CacheConsistencyChecker.FixResult>> fixAllInconsistencies(
            @RequestParam(defaultValue = "L2_WINS") String strategy) {
        try {
            CacheConsistencyChecker.FixStrategy fixStrategy = 
                CacheConsistencyChecker.FixStrategy.valueOf(strategy);
            return ResponseEntity.ok(consistencyChecker.fixAllInconsistencies(fixStrategy));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * 触发全量检查
     */
    @PostMapping("/consistency/full-check")
    public ResponseEntity<CacheConsistencyChecker.ConsistencyReport> triggerFullCheck() {
        return ResponseEntity.ok(consistencyChecker.triggerFullCheck());
    }
    
    /**
     * 获取不一致记录
     */
    @GetMapping("/consistency/inconsistencies")
    public ResponseEntity<Map<String, CacheConsistencyChecker.InconsistencyRecord>> getInconsistencies() {
        return ResponseEntity.ok(consistencyChecker.getInconsistencies());
    }
    
    /**
     * 获取最近检测报告
     */
    @GetMapping("/consistency/reports")
    public ResponseEntity<List<CacheConsistencyChecker.ConsistencyReport>> getReports() {
        return ResponseEntity.ok(consistencyChecker.getRecentReports());
    }
    
    /**
     * 注册测试数据
     */
    @PostMapping("/consistency/register")
    public ResponseEntity<Map<String, Object>> registerTestData(
            @RequestParam String key,
            @RequestBody Object data) {
        consistencyChecker.registerL1Entry(key, data);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("key", key);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取修复策略
     */
    @GetMapping("/consistency/strategies")
    public ResponseEntity<List<String>> getFixStrategies() {
        return ResponseEntity.ok(
            Arrays.stream(CacheConsistencyChecker.FixStrategy.values())
                .map(Enum::name)
                .toList()
        );
    }
    
    // ==================== 综合API ====================
    
    /**
     * 获取V7+全部统计
     */
    @GetMapping("/stats/all")
    public ResponseEntity<Map<String, Object>> getAllStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("eventBus", eventBus.getStatistics());
        stats.put("rateLimiter", rateLimiter.getStatistics());
        stats.put("consistencyChecker", consistencyChecker.getStatistics());
        stats.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(stats);
    }
    
    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("components", Map.of(
            "eventBus", "UP",
            "rateLimiter", "UP",
            "consistencyChecker", "UP"
        ));
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }
}
