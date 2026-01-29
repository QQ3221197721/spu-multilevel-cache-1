package com.ecommerce.cache.optimization.v7;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * V7 终极优化模块 REST API 控制器
 * 
 * 提供以下功能:
 * 1. 模块状态查询
 * 2. 组件统计信息
 * 3. 运行时配置调整
 * 4. 手动触发操作
 * 5. 诊断数据导出
 */
@Slf4j
@RestController
@RequestMapping("/api/v7/optimization")
@RequiredArgsConstructor
public class OptimizationV7Controller {
    
    private final OptimizationV7Properties properties;
    private final CacheCoordinatorV7 coordinator;
    private final AIDrivenCachePredictor aiPredictor;
    private final ZeroAllocationPipeline zeroAllocPipeline;
    private final SmartDegradationOrchestrator degradationOrchestrator;
    private final TopologyAwareRouter topologyRouter;
    private final RealTimeDiagnosticsEngine diagnosticsEngine;
    private final DistributedLockEnhancer lockEnhancer;
    
    // ========== 模块状态 ==========
    
    /**
     * 获取V7模块整体状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("version", "7.0.0");
        status.put("enabled", properties.isEnabled());
        status.put("timestamp", new Date());
        
        // 组件状态
        Map<String, Boolean> components = new LinkedHashMap<>();
        components.put("aiPrediction", properties.isAiPredictionEnabled());
        components.put("zeroAllocation", properties.isZeroAllocationEnabled());
        components.put("smartDegradation", properties.isSmartDegradationEnabled());
        components.put("topologyAware", properties.isTopologyAwareEnabled());
        components.put("realtimeDiagnostics", properties.isRealtimeDiagnosticsEnabled());
        components.put("distributedLockEnhanced", properties.isDistributedLockEnhanced());
        status.put("components", components);
        
        // 组件健康状态
        status.put("componentHealth", coordinator.getComponentStatus());
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * 获取完整统计信息
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        return ResponseEntity.ok(coordinator.getStatistics());
    }
    
    // ========== AI预测引擎 ==========
    
    /**
     * 获取AI预测引擎状态
     */
    @GetMapping("/ai-predictor/status")
    public ResponseEntity<Map<String, Object>> getAIPredictorStatus() {
        return ResponseEntity.ok(aiPredictor.getStatistics());
    }
    
    /**
     * 获取热点预测列表
     */
    @GetMapping("/ai-predictor/hot-keys")
    public ResponseEntity<List<String>> getHotKeyPredictions(
            @RequestParam(defaultValue = "20") int topK) {
        return ResponseEntity.ok(aiPredictor.getHotKeyPredictions(topK));
    }
    
    /**
     * 预测下一批可能访问的Key
     */
    @GetMapping("/ai-predictor/predict")
    public ResponseEntity<List<String>> predictNextAccess(
            @RequestParam String key,
            @RequestParam(defaultValue = "10") int topK) {
        return ResponseEntity.ok(aiPredictor.predictNextAccess(key, topK));
    }
    
    /**
     * 获取Key的预测结果
     */
    @GetMapping("/ai-predictor/prediction/{key}")
    public ResponseEntity<AIDrivenCachePredictor.PredictionResult> getPrediction(
            @PathVariable String key) {
        return ResponseEntity.ok(aiPredictor.getPrediction(key));
    }
    
    // ========== 零分配管道 ==========
    
    /**
     * 获取零分配管道状态
     */
    @GetMapping("/zero-alloc/status")
    public ResponseEntity<Map<String, Object>> getZeroAllocStatus() {
        return ResponseEntity.ok(zeroAllocPipeline.getStatistics());
    }
    
    // ========== 智能降级编排器 ==========
    
    /**
     * 获取降级状态
     */
    @GetMapping("/degradation/status")
    public ResponseEntity<Map<String, Object>> getDegradationStatus() {
        return ResponseEntity.ok(degradationOrchestrator.getStatistics());
    }
    
    /**
     * 获取当前降级级别
     */
    @GetMapping("/degradation/level")
    public ResponseEntity<Map<String, Object>> getDegradationLevel() {
        Map<String, Object> result = new LinkedHashMap<>();
        int level = degradationOrchestrator.getCurrentLevel();
        result.put("level", level);
        result.put("levelName", degradationOrchestrator.getLevelName(level));
        result.put("readOnlyMode", degradationOrchestrator.isReadOnlyMode());
        result.put("rejectRequests", degradationOrchestrator.shouldRejectRequest());
        return ResponseEntity.ok(result);
    }
    
    /**
     * 手动触发降级
     */
    @PostMapping("/degradation/trigger")
    public ResponseEntity<Map<String, Object>> triggerDegradation(
            @RequestParam String reason,
            @RequestParam int targetLevel) {
        degradationOrchestrator.triggerDegradation(reason, targetLevel);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("currentLevel", degradationOrchestrator.getCurrentLevel());
        result.put("levelName", degradationOrchestrator.getLevelName(degradationOrchestrator.getCurrentLevel()));
        return ResponseEntity.ok(result);
    }
    
    // ========== 拓扑感知路由 ==========
    
    /**
     * 获取拓扑路由状态
     */
    @GetMapping("/topology/status")
    public ResponseEntity<Map<String, Object>> getTopologyStatus() {
        return ResponseEntity.ok(topologyRouter.getStatistics());
    }
    
    /**
     * 注册缓存节点
     */
    @PostMapping("/topology/nodes")
    public ResponseEntity<Map<String, Object>> registerNode(
            @RequestParam String nodeId,
            @RequestParam String host,
            @RequestParam int port,
            @RequestParam String datacenter,
            @RequestParam String rack) {
        topologyRouter.registerNode(nodeId, host, port, datacenter, rack);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("nodeId", nodeId);
        result.put("message", "节点注册成功");
        return ResponseEntity.ok(result);
    }
    
    /**
     * 注销缓存节点
     */
    @DeleteMapping("/topology/nodes/{nodeId}")
    public ResponseEntity<Map<String, Object>> unregisterNode(@PathVariable String nodeId) {
        topologyRouter.unregisterNode(nodeId);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("nodeId", nodeId);
        result.put("message", "节点注销成功");
        return ResponseEntity.ok(result);
    }
    
    /**
     * 选择最优节点
     */
    @GetMapping("/topology/route")
    public ResponseEntity<Map<String, Object>> selectNode(
            @RequestParam String key,
            @RequestParam(required = false) String sessionId) {
        String nodeId = topologyRouter.selectNode(key, sessionId);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("selectedNode", nodeId);
        
        if (nodeId != null) {
            TopologyAwareRouter.CacheNode node = topologyRouter.getNode(nodeId);
            if (node != null) {
                result.put("nodeInfo", Map.of(
                    "host", node.getHost(),
                    "port", node.getPort(),
                    "datacenter", node.getDatacenter(),
                    "rack", node.getRack()
                ));
            }
        }
        
        return ResponseEntity.ok(result);
    }
    
    // ========== 实时诊断引擎 ==========
    
    /**
     * 获取诊断引擎状态
     */
    @GetMapping("/diagnostics/status")
    public ResponseEntity<Map<String, Object>> getDiagnosticsStatus() {
        return ResponseEntity.ok(diagnosticsEngine.getStatistics());
    }
    
    /**
     * 获取热点方法
     */
    @GetMapping("/diagnostics/hot-methods")
    public ResponseEntity<List<Map<String, Object>>> getHotMethods(
            @RequestParam(defaultValue = "20") int topK) {
        return ResponseEntity.ok(diagnosticsEngine.getHotMethods(topK));
    }
    
    /**
     * 获取延迟百分位数
     */
    @GetMapping("/diagnostics/latency/{operation}")
    public ResponseEntity<Map<String, Long>> getLatencyPercentiles(
            @PathVariable String operation) {
        return ResponseEntity.ok(diagnosticsEngine.getLatencyPercentiles(operation));
    }
    
    /**
     * 获取慢请求列表
     */
    @GetMapping("/diagnostics/slow-requests")
    public ResponseEntity<List<Map<String, Object>>> getSlowRequests(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(diagnosticsEngine.getRecentSlowRequests(limit));
    }
    
    /**
     * 获取异常统计
     */
    @GetMapping("/diagnostics/exceptions")
    public ResponseEntity<Map<String, Long>> getExceptionCounts() {
        return ResponseEntity.ok(diagnosticsEngine.getExceptionCounts());
    }
    
    /**
     * 获取告警列表
     */
    @GetMapping("/diagnostics/alerts")
    public ResponseEntity<List<Map<String, Object>>> getAlerts(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(diagnosticsEngine.getAlerts(limit));
    }
    
    /**
     * 生成火焰图数据
     */
    @GetMapping("/diagnostics/flame-graph")
    public ResponseEntity<List<Map<String, Object>>> getFlameGraphData() {
        return ResponseEntity.ok(diagnosticsEngine.generateFlameGraphData());
    }
    
    // ========== 分布式锁增强 ==========
    
    /**
     * 获取锁增强器状态
     */
    @GetMapping("/lock/status")
    public ResponseEntity<Map<String, Object>> getLockStatus() {
        return ResponseEntity.ok(lockEnhancer.getStatistics());
    }
    
    /**
     * 检查锁是否被持有
     */
    @GetMapping("/lock/check/{lockKey}")
    public ResponseEntity<Map<String, Object>> checkLock(@PathVariable String lockKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lockKey", lockKey);
        result.put("locked", lockEnhancer.isLocked(lockKey));
        return ResponseEntity.ok(result);
    }
    
    // ========== 配置管理 ==========
    
    /**
     * 获取当前配置
     */
    @GetMapping("/config")
    public ResponseEntity<OptimizationV7Properties> getConfig() {
        return ResponseEntity.ok(properties);
    }
    
    /**
     * 动态更新AI预测配置
     */
    @PutMapping("/config/ai-prediction")
    public ResponseEntity<Map<String, Object>> updateAIPredictionConfig(
            @RequestBody OptimizationV7Properties.AIPrediction config) {
        properties.setAiPrediction(config);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "AI预测配置已更新");
        result.put("config", config);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 动态更新降级配置
     */
    @PutMapping("/config/degradation")
    public ResponseEntity<Map<String, Object>> updateDegradationConfig(
            @RequestBody OptimizationV7Properties.SmartDegradation config) {
        properties.setSmartDegradation(config);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "降级配置已更新");
        result.put("config", config);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 动态开关组件
     */
    @PutMapping("/config/toggle")
    public ResponseEntity<Map<String, Object>> toggleComponent(
            @RequestParam String component,
            @RequestParam boolean enabled) {
        switch (component.toLowerCase()) {
            case "ai-prediction" -> properties.setAiPredictionEnabled(enabled);
            case "zero-allocation" -> properties.setZeroAllocationEnabled(enabled);
            case "smart-degradation" -> properties.setSmartDegradationEnabled(enabled);
            case "topology-aware" -> properties.setTopologyAwareEnabled(enabled);
            case "realtime-diagnostics" -> properties.setRealtimeDiagnosticsEnabled(enabled);
            case "distributed-lock" -> properties.setDistributedLockEnhanced(enabled);
            default -> {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("success", false);
                error.put("message", "未知组件: " + component);
                return ResponseEntity.badRequest().body(error);
            }
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("component", component);
        result.put("enabled", enabled);
        result.put("message", "组件状态已更新");
        return ResponseEntity.ok(result);
    }
    
    // ========== 健康检查 ==========
    
    /**
     * V7模块健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("version", "7.0.0");
        
        Map<String, String> components = new LinkedHashMap<>();
        
        try {
            aiPredictor.getStatistics();
            components.put("aiPredictor", "UP");
        } catch (Exception e) {
            components.put("aiPredictor", "DOWN");
        }
        
        try {
            zeroAllocPipeline.getStatistics();
            components.put("zeroAllocPipeline", "UP");
        } catch (Exception e) {
            components.put("zeroAllocPipeline", "DOWN");
        }
        
        try {
            degradationOrchestrator.getStatistics();
            components.put("degradationOrchestrator", "UP");
        } catch (Exception e) {
            components.put("degradationOrchestrator", "DOWN");
        }
        
        try {
            topologyRouter.getStatistics();
            components.put("topologyRouter", "UP");
        } catch (Exception e) {
            components.put("topologyRouter", "DOWN");
        }
        
        try {
            diagnosticsEngine.getStatistics();
            components.put("diagnosticsEngine", "UP");
        } catch (Exception e) {
            components.put("diagnosticsEngine", "DOWN");
        }
        
        try {
            lockEnhancer.getStatistics();
            components.put("lockEnhancer", "UP");
        } catch (Exception e) {
            components.put("lockEnhancer", "DOWN");
        }
        
        health.put("components", components);
        
        // 检查是否有DOWN的组件
        boolean allUp = components.values().stream().allMatch(s -> s.equals("UP"));
        if (!allUp) {
            health.put("status", "DEGRADED");
        }
        
        return ResponseEntity.ok(health);
    }
}
