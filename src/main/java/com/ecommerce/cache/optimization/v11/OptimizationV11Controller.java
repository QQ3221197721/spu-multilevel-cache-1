package com.ecommerce.cache.optimization.v11;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * V11超越极限优化REST API控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v11/optimization")
@RequiredArgsConstructor
public class OptimizationV11Controller {
    
    private final CacheCoordinatorV11 coordinator;
    private final TemporalCacheEngine temporalCacheEngine;
    private final GraphCacheOptimizer graphCacheOptimizer;
    private final FederatedLearningEngine federatedLearningEngine;
    private final ZeroLatencyPrefetcher zeroLatencyPrefetcher;
    
    // ========== 状态接口 ==========
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(coordinator.getCoordinatorStatus());
    }
    
    // ========== 时序缓存接口 ==========
    
    @PostMapping("/temporal/write")
    public ResponseEntity<Map<String, Object>> writeTimeSeries(
            @RequestParam String metric,
            @RequestParam double value) {
        temporalCacheEngine.write(metric, value);
        return ResponseEntity.ok(Map.of("success", true, "metric", metric, "value", value));
    }
    
    @GetMapping("/temporal/query")
    public ResponseEntity<List<TemporalCacheEngine.DataPoint>> queryTimeSeries(
            @RequestParam String metric,
            @RequestParam long startMs,
            @RequestParam long endMs) {
        return ResponseEntity.ok(temporalCacheEngine.rangeQuery(
            metric, Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs)
        ));
    }
    
    @GetMapping("/temporal/aggregate")
    public ResponseEntity<TemporalCacheEngine.AggregationResult> aggregate(
            @RequestParam String metric,
            @RequestParam String type,
            @RequestParam long startMs,
            @RequestParam long endMs) {
        TemporalCacheEngine.AggregationType aggType = 
            TemporalCacheEngine.AggregationType.valueOf(type.toUpperCase());
        return ResponseEntity.ok(temporalCacheEngine.aggregate(
            metric, aggType, Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs)
        ));
    }
    
    @GetMapping("/temporal/statistics")
    public ResponseEntity<Map<String, Object>> getTemporalStatistics() {
        return ResponseEntity.ok(temporalCacheEngine.getStatistics());
    }
    
    // ========== 图缓存接口 ==========
    
    @PostMapping("/graph/node")
    public ResponseEntity<Map<String, Object>> addNode(
            @RequestParam String nodeId,
            @RequestBody Map<String, Object> properties) {
        graphCacheOptimizer.addNode(nodeId, properties);
        return ResponseEntity.ok(Map.of("success", true, "nodeId", nodeId));
    }
    
    @PostMapping("/graph/edge")
    public ResponseEntity<Map<String, Object>> addEdge(
            @RequestParam String fromId,
            @RequestParam String toId) {
        graphCacheOptimizer.addEdge(fromId, toId);
        return ResponseEntity.ok(Map.of("success", true, "from", fromId, "to", toId));
    }
    
    @GetMapping("/graph/traverse/{startId}")
    public ResponseEntity<List<String>> traverseGraph(
            @PathVariable String startId,
            @RequestParam(defaultValue = "3") int depth) {
        return ResponseEntity.ok(graphCacheOptimizer.bfsTraversal(startId, depth));
    }
    
    @GetMapping("/graph/path")
    public ResponseEntity<List<String>> shortestPath(
            @RequestParam String from,
            @RequestParam String to) {
        return ResponseEntity.ok(graphCacheOptimizer.shortestPath(from, to));
    }
    
    @GetMapping("/graph/high-value")
    public ResponseEntity<List<String>> getHighValueNodes(
            @RequestParam(defaultValue = "10") int topK) {
        return ResponseEntity.ok(graphCacheOptimizer.getHighValueNodes(topK));
    }
    
    @GetMapping("/graph/statistics")
    public ResponseEntity<Map<String, Object>> getGraphStatistics() {
        return ResponseEntity.ok(graphCacheOptimizer.getStatistics());
    }
    
    // ========== 联邦学习接口 ==========
    
    @PostMapping("/federated/register")
    public ResponseEntity<Map<String, Object>> registerParticipant(
            @RequestParam String participantId,
            @RequestBody Map<String, Object> metadata) {
        federatedLearningEngine.registerParticipant(participantId, metadata);
        return ResponseEntity.ok(Map.of("success", true, "participantId", participantId));
    }
    
    @PostMapping("/federated/train")
    public ResponseEntity<FederatedLearningEngine.TrainingResult> train(
            @RequestParam String participantId,
            @RequestBody List<TrainingSampleRequest> samples) {
        List<FederatedLearningEngine.TrainingSample> trainingSamples = samples.stream()
            .map(s -> new FederatedLearningEngine.TrainingSample(s.getFeatures(), s.getLabel()))
            .toList();
        return ResponseEntity.ok(federatedLearningEngine.localTrain(participantId, trainingSamples));
    }
    
    @GetMapping("/federated/model")
    public ResponseEntity<Map<String, Object>> getGlobalModel() {
        double[] model = federatedLearningEngine.getGlobalModel();
        return ResponseEntity.ok(Map.of(
            "modelSize", model.length,
            "modelNorm", computeNorm(model)
        ));
    }
    
    @GetMapping("/federated/statistics")
    public ResponseEntity<Map<String, Object>> getFederatedStatistics() {
        return ResponseEntity.ok(federatedLearningEngine.getStatistics());
    }
    
    // ========== 零延迟接口 ==========
    
    @PostMapping("/prefetch")
    public ResponseEntity<Map<String, Object>> prefetch(
            @RequestParam String key,
            @RequestParam(defaultValue = "0.5") double priority) {
        zeroLatencyPrefetcher.prefetch(key, priority);
        return ResponseEntity.ok(Map.of("success", true, "key", key, "priority", priority));
    }
    
    @PostMapping("/prefetch/batch")
    public ResponseEntity<Map<String, Object>> prefetchBatch(
            @RequestBody List<String> keys,
            @RequestParam(defaultValue = "0.5") double basePriority) {
        zeroLatencyPrefetcher.prefetchBatch(keys, basePriority);
        return ResponseEntity.ok(Map.of("success", true, "count", keys.size()));
    }
    
    @PostMapping("/prefetch/dependency")
    public ResponseEntity<Map<String, Object>> registerDependency(
            @RequestParam String key,
            @RequestParam String dependentKey) {
        zeroLatencyPrefetcher.registerDependency(key, dependentKey);
        return ResponseEntity.ok(Map.of("success", true, "key", key, "dependent", dependentKey));
    }
    
    @GetMapping("/prefetch/statistics")
    public ResponseEntity<Map<String, Object>> getPrefetchStatistics() {
        return ResponseEntity.ok(zeroLatencyPrefetcher.getStatistics());
    }
    
    // ========== 辅助方法 ==========
    
    private double computeNorm(double[] params) {
        double sum = 0;
        for (double p : params) {
            sum += p * p;
        }
        return Math.sqrt(sum);
    }
    
    // ========== 请求体 ==========
    
    @lombok.Data
    public static class TrainingSampleRequest {
        private double[] features;
        private double label;
    }
}
