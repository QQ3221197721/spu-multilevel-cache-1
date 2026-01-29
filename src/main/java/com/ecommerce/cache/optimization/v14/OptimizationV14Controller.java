package com.ecommerce.cache.optimization.v14;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V14超神优化模块REST API控制器
 */
@RestController
@RequestMapping("/api/v14/cache")
public class OptimizationV14Controller {

    private final CacheCoordinatorV14 coordinator;
    private final WebAssemblyCacheAccelerator wasmAccelerator;
    private final ServerlessCacheEngine serverlessEngine;
    private final AdaptiveLoadPredictor loadPredictor;
    private final IntelligentOrchestrator orchestrator;

    public OptimizationV14Controller(
            CacheCoordinatorV14 coordinator,
            WebAssemblyCacheAccelerator wasmAccelerator,
            ServerlessCacheEngine serverlessEngine,
            AdaptiveLoadPredictor loadPredictor,
            IntelligentOrchestrator orchestrator) {
        this.coordinator = coordinator;
        this.wasmAccelerator = wasmAccelerator;
        this.serverlessEngine = serverlessEngine;
        this.loadPredictor = loadPredictor;
        this.orchestrator = orchestrator;
    }

    // ==================== 综合状态 ====================

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(coordinator.getComprehensiveStatus());
    }

    // ==================== WebAssembly加速器 ====================

    @PostMapping("/wasm/load-module")
    public ResponseEntity<Map<String, Object>> loadWasmModule(
            @RequestParam String name,
            @RequestBody byte[] wasmBytes) {
        String moduleHash = wasmAccelerator.loadModule(name, wasmBytes);
        return ResponseEntity.ok(Map.of(
                "moduleName", name,
                "moduleHash", moduleHash,
                "loaded", true
        ));
    }

    @PostMapping("/wasm/execute")
    public ResponseEntity<Map<String, Object>> executeWasm(
            @RequestParam String moduleName,
            @RequestParam String functionName,
            @RequestBody byte[] input) {
        byte[] result = wasmAccelerator.execute(moduleName, functionName, input);
        return ResponseEntity.ok(Map.of(
                "result", new String(result),
                "executed", true
        ));
    }

    @PostMapping("/wasm/builtin-call")
    public ResponseEntity<Map<String, Object>> callBuiltin(
            @RequestParam String functionName,
            @RequestBody byte[] input) {
        byte[] result = wasmAccelerator.callBuiltin(functionName, input);
        return ResponseEntity.ok(Map.of(
                "result", new String(result),
                "function", functionName
        ));
    }

    @GetMapping("/wasm/stats")
    public ResponseEntity<Map<String, Object>> getWasmStats() {
        return ResponseEntity.ok(wasmAccelerator.getStats());
    }

    // ==================== Serverless引擎 ====================

    @PostMapping("/serverless/invoke")
    public ResponseEntity<Map<String, Object>> invokeFunction(
            @RequestParam String functionName,
            @RequestBody Object input) {
        Object result = serverlessEngine.invoke(functionName, input, Object.class);
        return ResponseEntity.ok(Map.of(
                "result", result,
                "function", functionName,
                "invoked", true
        ));
    }

    @GetMapping("/serverless/functions")
    public ResponseEntity<List<String>> getRegisteredFunctions() {
        return ResponseEntity.ok(serverlessEngine.getRegisteredFunctions());
    }

    @GetMapping("/serverless/function-stats")
    public ResponseEntity<Map<String, Object>> getFunctionStats() {
        return ResponseEntity.ok(serverlessEngine.getStats());
    }

    // ==================== 负载预测器 ====================

    @PostMapping("/load-predictor/record")
    public ResponseEntity<Map<String, Object>> recordLoad(
            @RequestParam String metric,
            @RequestParam double load) {
        loadPredictor.recordCurrentLoad(metric, load);
        return ResponseEntity.ok(Map.of(
                "metric", metric,
                "load", load,
                "recorded", true
        ));
    }

    @GetMapping("/load-predictor/predict")
    public ResponseEntity<Map<String, Object>> predictLoad(
            @RequestParam String metric,
            @RequestParam(defaultValue = "10") int minutesAhead) {
        AdaptiveLoadPredictor.PredictionResult result = loadPredictor.predict(metric, minutesAhead);
        return ResponseEntity.ok(Map.of(
                "metric", metric,
                "predictions", result.getPredictions(),
                "maxPrediction", result.getMaxPrediction(),
                "avgPrediction", result.getAvgPrediction(),
                "timestamp", result.getTimestamp()
        ));
    }

    @GetMapping("/load-predictor/statistics")
    public ResponseEntity<Map<String, Object>> getLoadStatistics(
            @RequestParam String metric) {
        AdaptiveLoadPredictor.LoadStatistics stats = loadPredictor.getStatistics(metric);
        return ResponseEntity.ok(Map.of(
                "average", stats.getAverage(),
                "max", stats.getMax(),
                "min", stats.getMin(),
                "stdDev", stats.getStdDev()
        ));
    }

    @GetMapping("/load-predictor/metrics")
    public ResponseEntity<Map<String, Object>> getLoadPredictorMetrics() {
        return ResponseEntity.ok(loadPredictor.getStats());
    }

    // ==================== 智能编排器 ====================

    @PostMapping("/orchestrator/execute")
    public ResponseEntity<Map<String, Object>> executeOrchestration(
            @RequestParam(defaultValue = "global") String scope) {
        IntelligentOrchestrator.OrchestrationResult result = coordinator.executeOrchestration(scope);
        return ResponseEntity.ok(Map.of(
                "scope", scope,
                "plannedActions", result.getPlannedActions(),
                "executedActions", result.getExecutedActions(),
                "improvement", result.isImprovement(),
                "timestamp", result.getTimestamp()
        ));
    }

    @GetMapping("/orchestrator/suggestions")
    public ResponseEntity<List<IntelligentOrchestrator.OrchestrationSuggestion>> getSuggestions(
            @RequestParam(defaultValue = "global") String scope) {
        return ResponseEntity.ok(orchestrator.getSuggestions(scope));
    }

    @GetMapping("/orchestrator/status")
    public ResponseEntity<Map<String, Object>> getOrchestratorStatus() {
        IntelligentOrchestrator.OrchestrationStatus status = orchestrator.getStatus();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDecisions", status.getTotalDecisions());
        result.put("totalImprovements", status.getTotalImprovements());
        result.put("improvementRate", status.getTotalDecisions() > 0 
                ? (double) status.getTotalImprovements() / status.getTotalDecisions() 
                : 0);
        result.put("currentState", Map.of(
                "avgLatency", status.getCurrentState().getAvgLatency(),
                "throughput", status.getCurrentState().getThroughput(),
                "errorRate", status.getCurrentState().getErrorRate(),
                "cost", status.getCurrentState().getCost()
        ));
        result.put("resourceAllocation", Map.of(
                "cacheSizeMb", status.getResourceAllocation().getCacheSizeMb(),
                "cpuCores", status.getResourceAllocation().getCpuCores(),
                "memoryGb", status.getResourceAllocation().getMemoryGb()
        ));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/orchestrator/adjust-strategy-weights")
    public ResponseEntity<Map<String, Object>> adjustStrategyWeights(
            @RequestBody Map<String, Double> weights) {
        orchestrator.adjustStrategyWeights(weights);
        return ResponseEntity.ok(Map.of(
                "weights", weights,
                "adjusted", true
        ));
    }

    // ==================== 组合操作 ====================

    @PostMapping("/combined/optimize")
    public ResponseEntity<Map<String, Object>> applyOptimization(
            @RequestParam String optimizationType,
            @RequestBody Map<String, Object> params) {
        coordinator.applyOptimization(optimizationType, params);
        return ResponseEntity.ok(Map.of(
                "optimizationType", optimizationType,
                "applied", true,
                "params", params
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", coordinator.isRunning() ? "UP" : "DOWN");
        health.put("components", Map.of(
                "wasmAccelerator", wasmAccelerator != null,
                "serverlessEngine", serverlessEngine != null,
                "loadPredictor", loadPredictor != null,
                "orchestrator", orchestrator != null
        ));
        return ResponseEntity.ok(health);
    }
}
