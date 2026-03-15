package com.ecommerce.cache.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 降级开关控制端点
 *
 * 功能：
 * 1. 查看当前降级状态
 * 2. 手动开启/关闭降级模式
 * 3. 设置降级级别（NORMAL / READ_ONLY / CACHE_ONLY / REJECT）
 * 4. 降级操作审计日志
 * 5. 单独控制各缓存层的启用/禁用
 */
@Tag(name = "降级开关", description = "服务降级控制：级别设置、细粒度开关、审计日志")
@RestController
@RequestMapping("/api/ops/degradation")
public class DegradationController {

    private static final Logger log = LoggerFactory.getLogger(DegradationController.class);

    private final MeterRegistry meterRegistry;
    private final AtomicReference<DegradationLevel> currentLevel = new AtomicReference<>(DegradationLevel.NORMAL);
    private final AtomicReference<DegradationConfig> config = new AtomicReference<>(new DegradationConfig());
    private final ConcurrentLinkedDeque<DegradationEvent> auditLog = new ConcurrentLinkedDeque<>();
    private static final int MAX_AUDIT = 100;

    private Counter degradationToggleCounter;

    public DegradationController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        degradationToggleCounter = Counter.builder("ops.degradation.toggles")
                .description("Number of degradation level changes")
                .register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder("ops.degradation.level", currentLevel,
                        ref -> ref.get().ordinal())
                .description("Current degradation level (0=NORMAL, 1=READ_ONLY, 2=CACHE_ONLY, 3=REJECT)")
                .register(meterRegistry);
    }

    // ==================== 查询 ====================

    /**
     * 获取当前降级状态
     */
    @Operation(summary = "获取降级状态", description = "返回当前降级级别、描述及各层配置状态")
    @GetMapping
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("level", currentLevel.get().name());
        result.put("levelDescription", currentLevel.get().description);
        result.put("config", formatConfig(config.get()));
        return ResponseEntity.ok(result);
    }

    /**
     * 获取降级操作审计日志
     */
    @Operation(summary = "获取降级审计日志", description = "返回降级操作的历史审计记录")
    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> getAuditLog(
            @Parameter(description = "返回数量上限") @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("totalEvents", auditLog.size());
        result.put("events", auditLog.stream().limit(limit).toList());
        return ResponseEntity.ok(result);
    }

    // ==================== 降级操作 ====================

    /**
     * 设置降级级别
     */
    @Operation(summary = "设置降级级别", description = "设置服务降级级别：NORMAL/READ_ONLY/CACHE_ONLY/REJECT")
    @PostMapping("/level")
    public ResponseEntity<Map<String, Object>> setLevel(
            @Parameter(description = "降级级别", example = "CACHE_ONLY") @RequestParam String level,
            @Parameter(description = "降级原因") @RequestParam(defaultValue = "manual") String reason) {

        DegradationLevel newLevel;
        try {
            newLevel = DegradationLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid level. Valid levels: NORMAL, READ_ONLY, CACHE_ONLY, REJECT",
                    "current", currentLevel.get().name()
            ));
        }

        DegradationLevel oldLevel = currentLevel.getAndSet(newLevel);
        degradationToggleCounter.increment();

        DegradationEvent event = new DegradationEvent(
                Instant.now().toString(),
                oldLevel.name(),
                newLevel.name(),
                reason
        );
        addAuditEvent(event);

        log.warn("Degradation level changed: {} -> {} (reason: {})",
                oldLevel.name(), newLevel.name(), reason);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("previousLevel", oldLevel.name());
        result.put("currentLevel", newLevel.name());
        result.put("description", newLevel.description);
        return ResponseEntity.ok(result);
    }

    /**
     * 快捷：开启降级（设置为 CACHE_ONLY）
     */
    @Operation(summary = "快捷开启降级", description = "一键开启降级模式，设置为 CACHE_ONLY 级别")
    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> enableDegradation(
            @Parameter(description = "降级原因") @RequestParam(defaultValue = "manual enable") String reason) {
        return setLevel("CACHE_ONLY", reason);
    }

    /**
     * 快捷：关闭降级（恢复 NORMAL）
     */
    @Operation(summary = "快捷关闭降级", description = "一键关闭降级模式，恢复为 NORMAL 级别")
    @PostMapping("/disable")
    public ResponseEntity<Map<String, Object>> disableDegradation(
            @Parameter(description = "恢复原因") @RequestParam(defaultValue = "manual disable") String reason) {
        return setLevel("NORMAL", reason);
    }

    // ==================== 细粒度开关 ====================

    /**
     * 更新降级配置（单独控制各层）
     */
    @Operation(summary = "更新降级配置", description = "细粒度控制各缓存层/DB/写入/热点检测/布隆过滤器的启用状态")
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(
            @RequestBody DegradationConfig newConfig) {

        DegradationConfig oldConfig = config.getAndSet(newConfig);

        addAuditEvent(new DegradationEvent(
                Instant.now().toString(),
                "CONFIG:" + formatConfig(oldConfig),
                "CONFIG:" + formatConfig(newConfig),
                "config update"
        ));

        log.info("Degradation config updated: {}", formatConfig(newConfig));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("config", formatConfig(newConfig));
        return ResponseEntity.ok(result);
    }

    /**
     * 获取配置详情
     */
    @Operation(summary = "获取降级配置", description = "获取当前降级细粒度配置详情")
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(formatConfig(config.get()));
    }

    // ==================== 查询方法（供其他组件使用） ====================

    public DegradationLevel getCurrentLevel() {
        return currentLevel.get();
    }

    public boolean isNormal() {
        return currentLevel.get() == DegradationLevel.NORMAL;
    }

    public boolean isReadOnly() {
        return currentLevel.get().ordinal() >= DegradationLevel.READ_ONLY.ordinal();
    }

    public boolean isCacheOnly() {
        return currentLevel.get().ordinal() >= DegradationLevel.CACHE_ONLY.ordinal();
    }

    public boolean shouldReject() {
        return currentLevel.get() == DegradationLevel.REJECT;
    }

    public DegradationConfig getConfig() {
        return config.get();
    }

    // ==================== 内部类型 ====================

    public enum DegradationLevel {
        NORMAL("All systems operational"),
        READ_ONLY("Write operations disabled, reads from all layers"),
        CACHE_ONLY("Only serving from L1/L2 cache, DB queries disabled"),
        REJECT("Rejecting all non-health requests");

        public final String description;

        DegradationLevel(String description) {
            this.description = description;
        }
    }

    public static class DegradationConfig {
        private boolean l1Enabled = true;
        private boolean l2Enabled = true;
        private boolean l3Enabled = true;
        private boolean dbEnabled = true;
        private boolean writeEnabled = true;
        private boolean hotKeyDetectionEnabled = true;
        private boolean bloomFilterEnabled = true;

        // Getters and setters
        public boolean isL1Enabled() { return l1Enabled; }
        public void setL1Enabled(boolean l1Enabled) { this.l1Enabled = l1Enabled; }
        public boolean isL2Enabled() { return l2Enabled; }
        public void setL2Enabled(boolean l2Enabled) { this.l2Enabled = l2Enabled; }
        public boolean isL3Enabled() { return l3Enabled; }
        public void setL3Enabled(boolean l3Enabled) { this.l3Enabled = l3Enabled; }
        public boolean isDbEnabled() { return dbEnabled; }
        public void setDbEnabled(boolean dbEnabled) { this.dbEnabled = dbEnabled; }
        public boolean isWriteEnabled() { return writeEnabled; }
        public void setWriteEnabled(boolean writeEnabled) { this.writeEnabled = writeEnabled; }
        public boolean isHotKeyDetectionEnabled() { return hotKeyDetectionEnabled; }
        public void setHotKeyDetectionEnabled(boolean v) { this.hotKeyDetectionEnabled = v; }
        public boolean isBloomFilterEnabled() { return bloomFilterEnabled; }
        public void setBloomFilterEnabled(boolean v) { this.bloomFilterEnabled = v; }
    }

    public record DegradationEvent(String timestamp, String fromLevel, String toLevel, String reason) {}

    private void addAuditEvent(DegradationEvent event) {
        auditLog.addFirst(event);
        while (auditLog.size() > MAX_AUDIT) {
            auditLog.removeLast();
        }
    }

    private Map<String, Object> formatConfig(DegradationConfig cfg) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("l1Enabled", cfg.isL1Enabled());
        map.put("l2Enabled", cfg.isL2Enabled());
        map.put("l3Enabled", cfg.isL3Enabled());
        map.put("dbEnabled", cfg.isDbEnabled());
        map.put("writeEnabled", cfg.isWriteEnabled());
        map.put("hotKeyDetectionEnabled", cfg.isHotKeyDetectionEnabled());
        map.put("bloomFilterEnabled", cfg.isBloomFilterEnabled());
        return map;
    }
}
