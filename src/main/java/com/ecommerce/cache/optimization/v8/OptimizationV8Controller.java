package com.ecommerce.cache.optimization.v8;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * V8优化模块REST API控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v8")
@RequiredArgsConstructor
public class OptimizationV8Controller {
    
    private final CacheCoordinatorV8 coordinator;
    private final NearRealTimeSyncEngine syncEngine;
    private final BloomFilterOptimizer bloomFilter;
    private final GossipProtocolEngine gossipEngine;
    private final AutoScaleController autoScaler;
    
    // ==================== 协调器API ====================
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(coordinator.getStatistics());
    }
    
    @GetMapping("/components")
    public ResponseEntity<Map<String, CacheCoordinatorV8.ComponentStatus>> getComponents() {
        return ResponseEntity.ok(coordinator.getAllComponentStatuses());
    }
    
    @PostMapping("/task")
    public ResponseEntity<CacheCoordinatorV8.TaskResult> submitTask(
            @RequestParam String type,
            @RequestBody(required = false) Map<String, Object> params) {
        try {
            CompletableFuture<CacheCoordinatorV8.TaskResult> future = 
                coordinator.submitTask(type, params != null ? params : Collections.emptyMap());
            return ResponseEntity.ok(future.get());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
    
    @PostMapping("/global-sync")
    public ResponseEntity<Map<String, Object>> triggerGlobalSync() {
        coordinator.triggerGlobalSync();
        return ResponseEntity.ok(Map.of("status", "triggered", "timestamp", System.currentTimeMillis()));
    }
    
    @PostMapping("/emergency-degrade")
    public ResponseEntity<Map<String, Object>> emergencyDegrade() {
        coordinator.emergencyDegrade();
        return ResponseEntity.ok(Map.of("status", "degraded", "timestamp", System.currentTimeMillis()));
    }
    
    @PostMapping("/recover")
    public ResponseEntity<Map<String, Object>> recover() {
        coordinator.recoverNormal();
        return ResponseEntity.ok(Map.of("status", "recovered", "timestamp", System.currentTimeMillis()));
    }
    
    @GetMapping("/events")
    public ResponseEntity<List<CacheCoordinatorV8.CoordinationEvent>> getEvents() {
        return ResponseEntity.ok(coordinator.getEventLog());
    }
    
    // ==================== 近实时同步API ====================
    
    @GetMapping("/sync/stats")
    public ResponseEntity<Map<String, Object>> getSyncStats() {
        return ResponseEntity.ok(syncEngine.getStatistics());
    }
    
    @PostMapping("/sync/submit")
    public ResponseEntity<Map<String, Object>> submitSync(
            @RequestParam String key,
            @RequestBody Object data,
            @RequestParam(defaultValue = "PUT") String type) {
        try {
            NearRealTimeSyncEngine.SyncType syncType = NearRealTimeSyncEngine.SyncType.valueOf(type);
            syncEngine.submit(key, data, syncType);
            return ResponseEntity.ok(Map.of("submitted", true, "key", key));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
    
    @PostMapping("/sync/force")
    public ResponseEntity<NearRealTimeSyncEngine.SyncResult> forceSync(
            @RequestParam String key,
            @RequestBody Object data) {
        try {
            NearRealTimeSyncEngine.SyncResult result = syncEngine.forceSync(key, data).get();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
    
    @GetMapping("/sync/version")
    public ResponseEntity<Map<String, Object>> getVersion(@RequestParam String key) {
        return ResponseEntity.ok(Map.of("key", key, "version", syncEngine.getVersion(key)));
    }
    
    // ==================== 布隆过滤器API ====================
    
    @GetMapping("/bloom/stats")
    public ResponseEntity<Map<String, Object>> getBloomStats() {
        return ResponseEntity.ok(bloomFilter.getStatistics());
    }
    
    @PostMapping("/bloom/add")
    public ResponseEntity<Map<String, Object>> bloomAdd(@RequestParam String key) {
        bloomFilter.add(key);
        return ResponseEntity.ok(Map.of("added", true, "key", key));
    }
    
    @PostMapping("/bloom/add/batch")
    public ResponseEntity<Map<String, Object>> bloomAddBatch(@RequestBody List<String> keys) {
        bloomFilter.addAll(keys);
        return ResponseEntity.ok(Map.of("added", keys.size()));
    }
    
    @GetMapping("/bloom/check")
    public ResponseEntity<Map<String, Object>> bloomCheck(@RequestParam String key) {
        boolean result = bloomFilter.mightContain(key);
        return ResponseEntity.ok(Map.of("key", key, "mightContain", result));
    }
    
    @PostMapping("/bloom/check/batch")
    public ResponseEntity<Map<String, Boolean>> bloomCheckBatch(@RequestBody List<String> keys) {
        return ResponseEntity.ok(bloomFilter.mightContainBatch(keys));
    }
    
    @DeleteMapping("/bloom/clear")
    public ResponseEntity<Map<String, Object>> bloomClear() {
        bloomFilter.clear();
        return ResponseEntity.ok(Map.of("cleared", true));
    }
    
    @PostMapping("/bloom/counting/add")
    public ResponseEntity<Map<String, Object>> bloomCountingAdd(@RequestParam String key) {
        bloomFilter.addCounting(key);
        return ResponseEntity.ok(Map.of("added", true, "key", key, "type", "counting"));
    }
    
    @DeleteMapping("/bloom/counting/remove")
    public ResponseEntity<Map<String, Object>> bloomCountingRemove(@RequestParam String key) {
        bloomFilter.removeCounting(key);
        return ResponseEntity.ok(Map.of("removed", true, "key", key));
    }
    
    // ==================== Gossip协议API ====================
    
    @GetMapping("/gossip/stats")
    public ResponseEntity<Map<String, Object>> getGossipStats() {
        return ResponseEntity.ok(gossipEngine.getStatistics());
    }
    
    @GetMapping("/gossip/members")
    public ResponseEntity<List<GossipProtocolEngine.GossipNode>> getMembers() {
        return ResponseEntity.ok(gossipEngine.getMembers());
    }
    
    @GetMapping("/gossip/alive")
    public ResponseEntity<List<GossipProtocolEngine.GossipNode>> getAliveMembers() {
        return ResponseEntity.ok(gossipEngine.getAliveMembers());
    }
    
    @PostMapping("/gossip/broadcast")
    public ResponseEntity<Map<String, Object>> broadcast(
            @RequestParam String type,
            @RequestBody String content) {
        gossipEngine.broadcast(type, content);
        return ResponseEntity.ok(Map.of("broadcast", true, "type", type));
    }
    
    @PostMapping("/gossip/join")
    public ResponseEntity<Map<String, Object>> joinCluster(
            @RequestParam String address,
            @RequestParam int port) {
        gossipEngine.join(address, port);
        return ResponseEntity.ok(Map.of("joined", true, "target", address + ":" + port));
    }
    
    @PostMapping("/gossip/leave")
    public ResponseEntity<Map<String, Object>> leaveCluster() {
        gossipEngine.leave();
        return ResponseEntity.ok(Map.of("left", true));
    }
    
    // ==================== 自动伸缩API ====================
    
    @GetMapping("/autoscale/stats")
    public ResponseEntity<Map<String, Object>> getAutoScaleStats() {
        return ResponseEntity.ok(autoScaler.getStatistics());
    }
    
    @GetMapping("/autoscale/instances")
    public ResponseEntity<Map<String, Object>> getInstances() {
        return ResponseEntity.ok(Map.of(
            "current", autoScaler.getCurrentInstances(),
            "target", autoScaler.getTargetInstances(),
            "predicted", autoScaler.getPredictedOptimalInstances()
        ));
    }
    
    @PostMapping("/autoscale/target")
    public ResponseEntity<Map<String, Object>> setTargetInstances(@RequestParam int target) {
        autoScaler.setTargetInstances(target);
        return ResponseEntity.ok(Map.of(
            "target", target,
            "current", autoScaler.getCurrentInstances()
        ));
    }
    
    @PostMapping("/autoscale/up")
    public ResponseEntity<Map<String, Object>> scaleUp(
            @RequestParam(defaultValue = "1") int count) {
        boolean result = autoScaler.forceScaleUp(count);
        return ResponseEntity.ok(Map.of(
            "scaled", result,
            "direction", "up",
            "count", count,
            "current", autoScaler.getCurrentInstances()
        ));
    }
    
    @PostMapping("/autoscale/down")
    public ResponseEntity<Map<String, Object>> scaleDown(
            @RequestParam(defaultValue = "1") int count) {
        boolean result = autoScaler.forceScaleDown(count);
        return ResponseEntity.ok(Map.of(
            "scaled", result,
            "direction", "down",
            "count", count,
            "current", autoScaler.getCurrentInstances()
        ));
    }
    
    @GetMapping("/autoscale/events")
    public ResponseEntity<List<AutoScaleController.ScaleEvent>> getScaleEvents() {
        return ResponseEntity.ok(autoScaler.getRecentEvents());
    }
    
    // ==================== 综合API ====================
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("module", "V8");
        health.put("components", Map.of(
            "coordinator", "UP",
            "syncEngine", "UP",
            "bloomFilter", "UP",
            "gossipEngine", "UP",
            "autoScaler", "UP"
        ));
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }
    
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("module", "V8 Super Optimization");
        info.put("version", "1.0.0");
        info.put("features", List.of(
            "Near Real-Time Sync (50ms)",
            "Bloom Filter (10M capacity)",
            "Gossip Protocol (Decentralized)",
            "Auto Scaling (Predictive)",
            "Smart Coordination"
        ));
        info.put("performance", Map.of(
            "syncLatency", "50ms",
            "bloomFpp", "1%",
            "gossipInterval", "200ms",
            "scaleDecision", "60s"
        ));
        return ResponseEntity.ok(info);
    }
}
