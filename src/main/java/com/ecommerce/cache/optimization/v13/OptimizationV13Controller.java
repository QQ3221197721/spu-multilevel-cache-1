package com.ecommerce.cache.optimization.v13;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V13终极巅峰优化模块REST API控制器
 */
@RestController
@RequestMapping("/api/v13/cache")
public class OptimizationV13Controller {

    private final CacheCoordinatorV13 coordinator;
    private final DistributedTransactionCacheEngine transactionEngine;
    private final MultiActiveDataCenterEngine dataCenterEngine;
    private final SmartCompressionEngine compressionEngine;
    private final RealTimeAnomalyDetector anomalyDetector;

    public OptimizationV13Controller(
            CacheCoordinatorV13 coordinator,
            DistributedTransactionCacheEngine transactionEngine,
            MultiActiveDataCenterEngine dataCenterEngine,
            SmartCompressionEngine compressionEngine,
            RealTimeAnomalyDetector anomalyDetector) {
        this.coordinator = coordinator;
        this.transactionEngine = transactionEngine;
        this.dataCenterEngine = dataCenterEngine;
        this.compressionEngine = compressionEngine;
        this.anomalyDetector = anomalyDetector;
    }

    // ==================== 综合状态 ====================

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(coordinator.getComprehensiveStatus());
    }

    // ==================== 分布式事务 ====================

    @PostMapping("/transaction/begin")
    public ResponseEntity<Map<String, Object>> beginTransaction(
            @RequestParam(defaultValue = "TWO_PHASE_COMMIT") String mode) {
        String txId = transactionEngine.beginTransaction(
                DistributedTransactionCacheEngine.TransactionMode.valueOf(mode));
        return ResponseEntity.ok(Map.of("transactionId", txId, "mode", mode));
    }

    @PostMapping("/transaction/{txId}/put")
    public ResponseEntity<Map<String, Object>> transactionalPut(
            @PathVariable String txId,
            @RequestParam String key,
            @RequestBody String value) {
        transactionEngine.transactionalPut(txId, key, value);
        return ResponseEntity.ok(Map.of("status", "written", "key", key));
    }

    @GetMapping("/transaction/{txId}/get")
    public ResponseEntity<Map<String, Object>> transactionalGet(
            @PathVariable String txId,
            @RequestParam String key) {
        Object value = transactionEngine.transactionalGet(txId, key, Object.class);
        return ResponseEntity.ok(Map.of("key", key, "value", value != null ? value : "null"));
    }

    @PostMapping("/transaction/{txId}/commit")
    public ResponseEntity<Map<String, Object>> commitTransaction(@PathVariable String txId) {
        boolean success = transactionEngine.commit(txId);
        return ResponseEntity.ok(Map.of("transactionId", txId, "committed", success));
    }

    @PostMapping("/transaction/{txId}/rollback")
    public ResponseEntity<Map<String, Object>> rollbackTransaction(@PathVariable String txId) {
        transactionEngine.rollback(txId);
        return ResponseEntity.ok(Map.of("transactionId", txId, "rolledBack", true));
    }

    @GetMapping("/transaction/stats")
    public ResponseEntity<Map<String, Object>> getTransactionStats() {
        return ResponseEntity.ok(transactionEngine.getStats());
    }

    // ==================== 多活数据中心 ====================

    @PostMapping("/datacenter/put")
    public ResponseEntity<Map<String, Object>> dataCenterPut(
            @RequestParam String key,
            @RequestBody String value,
            @RequestParam(defaultValue = "-1") long ttlMs) {
        dataCenterEngine.put(key, value, ttlMs);
        return ResponseEntity.ok(Map.of(
                "key", key,
                "datacenter", dataCenterEngine.getLocalDataCenterId(),
                "replicated", true
        ));
    }

    @GetMapping("/datacenter/get")
    public ResponseEntity<Map<String, Object>> dataCenterGet(
            @RequestParam String key,
            @RequestParam(defaultValue = "EVENTUAL") String consistency) {
        Object value = dataCenterEngine.get(key, 
                MultiActiveDataCenterEngine.ConsistencyLevel.valueOf(consistency));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("value", value);
        result.put("datacenter", dataCenterEngine.getLocalDataCenterId());
        result.put("isLeader", dataCenterEngine.isLeader());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/datacenter/delete")
    public ResponseEntity<Map<String, Object>> dataCenterDelete(@RequestParam String key) {
        dataCenterEngine.delete(key);
        return ResponseEntity.ok(Map.of("key", key, "deleted", true));
    }

    @GetMapping("/datacenter/status")
    public ResponseEntity<Map<String, Object>> getDataCenterStatus() {
        return ResponseEntity.ok(dataCenterEngine.getStatus());
    }

    // ==================== 智能压缩 ====================

    @PostMapping("/compression/compress")
    public ResponseEntity<Map<String, Object>> compress(
            @RequestBody String data,
            @RequestParam(required = false) String algorithm) {
        byte[] input = data.getBytes(StandardCharsets.UTF_8);
        SmartCompressionEngine.CompressedData compressed = algorithm != null 
                ? compressionEngine.compress(input, algorithm)
                : compressionEngine.compress(input);
        
        return ResponseEntity.ok(Map.of(
                "originalSize", compressed.getOriginalSize(),
                "compressedSize", compressed.getCompressedSize(),
                "algorithm", compressed.getAlgorithm(),
                "ratio", String.format("%.2f%%", compressed.getRatio() * 100)
        ));
    }

    @PostMapping("/compression/analyze")
    public ResponseEntity<Map<String, Object>> analyzeCompression(@RequestBody String data) {
        byte[] input = data.getBytes(StandardCharsets.UTF_8);
        String recommended = compressionEngine.selectBestAlgorithm(input);
        
        // 测试所有算法
        Map<String, Object> results = new LinkedHashMap<>();
        for (String alg : List.of("LZ4", "ZSTD", "SNAPPY", "GZIP", "DEFLATE")) {
            SmartCompressionEngine.CompressedData cd = compressionEngine.compress(input, alg);
            results.put(alg, Map.of(
                    "compressedSize", cd.getCompressedSize(),
                    "ratio", String.format("%.2f%%", cd.getRatio() * 100)
            ));
        }
        
        return ResponseEntity.ok(Map.of(
                "originalSize", input.length,
                "recommendedAlgorithm", recommended,
                "algorithmComparison", results
        ));
    }

    @GetMapping("/compression/stats")
    public ResponseEntity<Map<String, Object>> getCompressionStats() {
        return ResponseEntity.ok(compressionEngine.getStats());
    }

    // ==================== 异常检测 ====================

    @PostMapping("/anomaly/record")
    public ResponseEntity<Map<String, Object>> recordMetric(
            @RequestParam String metric,
            @RequestParam double value) {
        RealTimeAnomalyDetector.AnomalyResult result = anomalyDetector.record(metric, value);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metric", metric);
        response.put("value", value);
        response.put("score", result.getScore());
        response.put("isAnomaly", result.isAnomaly());
        response.put("type", result.getType().name());
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/anomaly/batch")
    public ResponseEntity<Map<String, Object>> recordBatch(
            @RequestParam String metric,
            @RequestBody List<Double> values) {
        List<RealTimeAnomalyDetector.AnomalyResult> results = anomalyDetector.recordBatch(metric, values);
        
        long anomalyCount = results.stream().filter(RealTimeAnomalyDetector.AnomalyResult::isAnomaly).count();
        
        return ResponseEntity.ok(Map.of(
                "metric", metric,
                "totalPoints", values.size(),
                "anomaliesDetected", anomalyCount
        ));
    }

    @GetMapping("/anomaly/history/{metric}")
    public ResponseEntity<List<RealTimeAnomalyDetector.AnomalyEvent>> getAnomalyHistory(
            @PathVariable String metric) {
        return ResponseEntity.ok(anomalyDetector.getAnomalyHistory(metric));
    }

    @GetMapping("/anomaly/stats")
    public ResponseEntity<Map<String, Object>> getAnomalyStats() {
        return ResponseEntity.ok(anomalyDetector.getStats());
    }

    // ==================== 组合操作 ====================

    @PostMapping("/combined/transactional-write")
    public ResponseEntity<Map<String, Object>> combinedTransactionalWrite(
            @RequestParam String key,
            @RequestBody String value) {
        coordinator.putWithTransaction(key, value);
        return ResponseEntity.ok(Map.of(
                "key", key,
                "status", "committed",
                "replicated", true
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", coordinator.isRunning() ? "UP" : "DOWN");
        health.put("components", Map.of(
                "transaction", coordinator.getProperties().getDistributedTransaction().isEnabled(),
                "multiDC", coordinator.getProperties().getMultiActiveDataCenter().isEnabled(),
                "compression", coordinator.getProperties().getSmartCompression().isEnabled(),
                "anomalyDetection", coordinator.getProperties().getAnomalyDetection().isEnabled()
        ));
        return ResponseEntity.ok(health);
    }
}
