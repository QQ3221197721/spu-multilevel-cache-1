package com.ecommerce.cache.optimization.v9;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * V9优化模块REST API控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v9")
@RequiredArgsConstructor
public class OptimizationV9Controller {
    
    private final CacheCoordinatorV9 coordinator;
    private final CRDTDataStructures crdt;
    private final VectorClockManager vectorClock;
    private final EdgeComputeGateway edgeGateway;
    private final MLWarmupEngine mlWarmup;
    
    // ========== 协调器API ==========
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(coordinator.getStatistics());
    }
    
    @GetMapping("/components")
    public ResponseEntity<Map<String, CacheCoordinatorV9.ComponentStatus>> getComponents() {
        return ResponseEntity.ok(coordinator.getComponentStatuses());
    }
    
    @GetMapping("/events")
    public ResponseEntity<List<CacheCoordinatorV9.CoordinationEvent>> getEvents() {
        return ResponseEntity.ok(coordinator.getEventLog());
    }
    
    // ========== CRDT API ==========
    
    @GetMapping("/crdt/stats")
    public ResponseEntity<Map<String, Object>> getCrdtStats() {
        return ResponseEntity.ok(crdt.getStatistics());
    }
    
    @PostMapping("/crdt/counter/increment")
    public ResponseEntity<Map<String, Object>> incrementCounter(
            @RequestParam String key, @RequestParam(defaultValue = "1") long delta) {
        crdt.incrementGCounter(key, delta);
        return ResponseEntity.ok(Map.of("key", key, "value", crdt.getGCounterValue(key)));
    }
    
    @GetMapping("/crdt/counter/{key}")
    public ResponseEntity<Map<String, Object>> getCounter(@PathVariable String key) {
        return ResponseEntity.ok(Map.of("key", key, "value", crdt.getGCounterValue(key)));
    }
    
    @PostMapping("/crdt/set/add")
    public ResponseEntity<Map<String, Object>> addToSet(
            @RequestParam String key, @RequestParam String element) {
        crdt.addToORSet(key, element);
        return ResponseEntity.ok(Map.of("key", key, "added", element));
    }
    
    @GetMapping("/crdt/set/{key}")
    public ResponseEntity<Set<Object>> getSet(@PathVariable String key) {
        return ResponseEntity.ok(crdt.getORSetElements(key));
    }
    
    // ========== 向量时钟API ==========
    
    @GetMapping("/vclock/stats")
    public ResponseEntity<Map<String, Object>> getVClockStats() {
        return ResponseEntity.ok(vectorClock.getStatistics());
    }
    
    @PostMapping("/vclock/tick")
    public ResponseEntity<VectorClockManager.VectorClock> tick(@RequestParam String key) {
        return ResponseEntity.ok(vectorClock.tick(key));
    }
    
    @GetMapping("/vclock/history/{key}")
    public ResponseEntity<List<String>> getHistory(@PathVariable String key) {
        return ResponseEntity.ok(vectorClock.getCausalHistory(key));
    }
    
    // ========== 边缘网关API ==========
    
    @GetMapping("/edge/stats")
    public ResponseEntity<Map<String, Object>> getEdgeStats() {
        return ResponseEntity.ok(edgeGateway.getStatistics());
    }
    
    @GetMapping("/edge/nodes")
    public ResponseEntity<List<EdgeComputeGateway.EdgeNode>> getEdgeNodes() {
        return ResponseEntity.ok(edgeGateway.getEdgeNodes());
    }
    
    @PostMapping("/edge/node")
    public ResponseEntity<Map<String, Object>> registerNode(
            @RequestParam String nodeId, @RequestParam String region,
            @RequestParam String address, @RequestParam int port) {
        edgeGateway.registerEdgeNode(nodeId, region, address, port);
        return ResponseEntity.ok(Map.of("registered", nodeId, "region", region));
    }
    
    @PostMapping("/edge/cache")
    public ResponseEntity<Map<String, Object>> putToEdge(
            @RequestParam String key, @RequestBody Object value,
            @RequestParam(defaultValue = "*") String region) {
        edgeGateway.putToEdge(key, value, region);
        return ResponseEntity.ok(Map.of("cached", true, "key", key, "region", region));
    }
    
    @GetMapping("/edge/route")
    public ResponseEntity<Map<String, Object>> route(
            @RequestParam String key, @RequestParam String clientRegion) {
        String node = edgeGateway.route(key, clientRegion);
        return ResponseEntity.ok(Map.of("key", key, "routedTo", node != null ? node : "none"));
    }
    
    // ========== ML预热API ==========
    
    @GetMapping("/ml/stats")
    public ResponseEntity<Map<String, Object>> getMLStats() {
        return ResponseEntity.ok(mlWarmup.getStatistics());
    }
    
    @PostMapping("/ml/record")
    public ResponseEntity<Map<String, Object>> recordAccess(@RequestParam String key) {
        mlWarmup.recordAccess(key);
        return ResponseEntity.ok(Map.of("recorded", key));
    }
    
    @GetMapping("/ml/predict")
    public ResponseEntity<MLWarmupEngine.PredictionResult> predict(@RequestParam String key) {
        return ResponseEntity.ok(mlWarmup.predict(key));
    }
    
    @GetMapping("/ml/hotkeys")
    public ResponseEntity<List<String>> getHotKeys(
            @RequestParam(defaultValue = "10") int topK) {
        return ResponseEntity.ok(mlWarmup.predictHotKeys(topK));
    }
    
    @PostMapping("/ml/warmup")
    public ResponseEntity<Map<String, Object>> triggerWarmup() {
        mlWarmup.triggerPredictiveWarmup(key -> log.debug("Warming up: {}", key));
        return ResponseEntity.ok(Map.of("triggered", true));
    }
    
    // ========== 综合API ==========
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "module", "V9",
            "components", coordinator.getComponentStatuses().keySet(),
            "timestamp", System.currentTimeMillis()
        ));
    }
    
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
            "module", "V9 Ultimate Optimization",
            "version", "1.0.0",
            "features", List.of(
                "CRDT (Conflict-free Replicated Data Types)",
                "Vector Clock (Causal Consistency)",
                "Edge Computing Gateway",
                "ML-Driven Cache Warmup"
            )
        ));
    }
}
