package com.ecommerce.cache.controller;

import com.ecommerce.cache.entity.AuditLogEntity;
import com.ecommerce.cache.repository.AuditLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询端点
 *
 * 提供审计日志的查询、统计、导出能力。
 * 仅允许 ADMIN 角色访问（由 SecurityConfig 控制）。
 */
@Tag(name = "审计日志", description = "安全审计日志查询：综合检索、摘要统计、高风险操作")
@RestController
@RequestMapping("/api/admin/audit")
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    public AuditLogController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 综合查询审计日志
     * GET /api/admin/audit/logs?operationType=API_CALL&riskLevel=HIGH&page=0&size=20
     */
    @Operation(summary = "综合查询审计日志", description = "支持按操作类型、操作人、风险等级、结果、时间范围多维度过滤")
    @GetMapping("/logs")
    public ResponseEntity<Page<AuditLogEntity>> queryLogs(
            @Parameter(description = "操作类型", example = "API_CALL") @RequestParam(required = false) String operationType,
            @Parameter(description = "操作人") @RequestParam(required = false) String operator,
            @Parameter(description = "风险等级", example = "HIGH") @RequestParam(required = false) String riskLevel,
            @Parameter(description = "操作结果") @RequestParam(required = false) String outcome,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Parameter(description = "页码") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") int size) {

        Page<AuditLogEntity> result = auditLogRepository.findByFilters(
                operationType, operator, riskLevel, outcome,
                startTime, endTime, PageRequest.of(page, Math.min(size, 100)));

        return ResponseEntity.ok(result);
    }

    /**
     * 获取审计摘要统计
     * GET /api/admin/audit/summary?hours=24
     */
    @Operation(summary = "获取审计摘要", description = "返回指定时间范围内的审计摘要：按类型/风险等级统计、总操作数、最近失败")
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary(
            @Parameter(description = "统计时间范围(小时)", example = "24") @RequestParam(defaultValue = "24") int hours) {

        LocalDateTime since = LocalDateTime.now().minusHours(hours);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("period", Map.of("hours", hours, "since", since.toString()));

        // 按操作类型统计
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Object[] row : auditLogRepository.countByOperationTypeSince(since)) {
            byType.put((String) row[0], (Long) row[1]);
        }
        summary.put("byOperationType", byType);

        // 按风险等级统计
        Map<String, Long> byRisk = new LinkedHashMap<>();
        for (Object[] row : auditLogRepository.countByRiskLevelSince(since)) {
            byRisk.put((String) row[0], (Long) row[1]);
        }
        summary.put("byRiskLevel", byRisk);

        // 总操作数
        long total = byType.values().stream().mapToLong(Long::longValue).sum();
        summary.put("totalOperations", total);

        // 最近失败操作
        List<AuditLogEntity> failures = auditLogRepository.findRecentFailures(since);
        summary.put("recentFailures", failures.size());
        summary.put("recentFailureDetails", failures.stream().limit(10).map(f -> {
            Map<String, Object> m = new HashMap<>();
            m.put("time", f.getOperationTime().toString());
            m.put("action", f.getAction());
            m.put("resource", f.getResource());
            m.put("operator", f.getOperator());
            m.put("details", f.getDetails());
            return m;
        }).toList());

        return ResponseEntity.ok(summary);
    }

    /**
     * 按资源搜索审计日志
     * GET /api/admin/audit/search?resource=/api/spu&page=0&size=20
     */
    @Operation(summary = "按资源搜索审计日志", description = "按资源路径模糊搜索审计记录")
    @GetMapping("/search")
    public ResponseEntity<Page<AuditLogEntity>> searchByResource(
            @Parameter(description = "资源路径关键词", example = "/api/spu") @RequestParam String resource,
            @Parameter(description = "页码") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") int size) {

        Page<AuditLogEntity> result = auditLogRepository
                .findByResourceContainingOrderByOperationTimeDesc(
                        resource, PageRequest.of(page, Math.min(size, 100)));

        return ResponseEntity.ok(result);
    }

    /**
     * 获取高风险操作记录
     * GET /api/admin/audit/high-risk?hours=24&page=0&size=20
     */
    @Operation(summary = "获取高风险操作", description = "查询指定时间范围内的高风险审计记录")
    @GetMapping("/high-risk")
    public ResponseEntity<Page<AuditLogEntity>> getHighRiskLogs(
            @Parameter(description = "时间范围(小时)", example = "24") @RequestParam(defaultValue = "24") int hours,
            @Parameter(description = "页码") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") int size) {

        LocalDateTime since = LocalDateTime.now().minusHours(hours);

        Page<AuditLogEntity> result = auditLogRepository.findByFilters(
                null, null, "HIGH", null,
                since, null, PageRequest.of(page, Math.min(size, 100)));

        return ResponseEntity.ok(result);
    }
}
