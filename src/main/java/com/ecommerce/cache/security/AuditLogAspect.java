package com.ecommerce.cache.security;

import com.ecommerce.cache.entity.AuditLogEntity;
import com.ecommerce.cache.repository.AuditLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * 审计日志 AOP 切面
 *
 * 自动拦截所有 Controller 层的写操作（POST/PUT/DELETE），
 * 以及缓存管理、降级开关等敏感操作，记录完整审计日志。
 *
 * 特性:
 * - 异步写入数据库，不阻塞业务请求
 * - 自动提取操作人（从 JWT/SecurityContext）
 * - 自动判定风险等级
 * - Prometheus 指标统计
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final AuditLogRepository auditLogRepository;
    private final MeterRegistry meterRegistry;

    @Value("${security.audit.enabled:true}")
    private boolean enabled;

    // 高风险操作 URI 关键字
    private static final Set<String> HIGH_RISK_KEYWORDS = Set.of(
            "degradation", "admin", "config", "delete", "cache/preheat"
    );

    private Counter auditLogTotal;
    private Counter auditLogFailures;

    public AuditLogAspect(AuditLogRepository auditLogRepository, MeterRegistry meterRegistry) {
        this.auditLogRepository = auditLogRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        auditLogTotal = Counter.builder("security.audit.log_total")
                .description("Total audit log entries recorded")
                .register(meterRegistry);
        auditLogFailures = Counter.builder("security.audit.log_failures")
                .description("Audit log recording failures")
                .register(meterRegistry);
    }

    /**
     * 拦截所有 Controller 写操作 (POST/PUT/DELETE/PATCH)
     */
    @Around("@within(org.springframework.web.bind.annotation.RestController) && " +
            "(@annotation(org.springframework.web.bind.annotation.PostMapping) || " +
            " @annotation(org.springframework.web.bind.annotation.PutMapping) || " +
            " @annotation(org.springframework.web.bind.annotation.DeleteMapping) || " +
            " @annotation(org.springframework.web.bind.annotation.PatchMapping))")
    public Object auditWriteOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!enabled) {
            return joinPoint.proceed();
        }
        return recordAudit(joinPoint, "API_CALL");
    }

    /**
     * 拦截降级操作
     */
    @Around("execution(* com.ecommerce.cache.controller.DegradationController.*(..))")
    public Object auditDegradation(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!enabled) {
            return joinPoint.proceed();
        }
        return recordAudit(joinPoint, "DEGRADATION");
    }

    /**
     * 核心审计记录逻辑
     */
    private Object recordAudit(ProceedingJoinPoint joinPoint, String operationType) throws Throwable {
        long startTime = System.currentTimeMillis();
        String outcome = "SUCCESS";
        Integer httpStatus = 200;
        Throwable error = null;

        try {
            Object result = joinPoint.proceed();
            // 尝试从 ResponseEntity 提取状态码
            if (result instanceof org.springframework.http.ResponseEntity<?> re) {
                httpStatus = re.getStatusCode().value();
            }
            return result;
        } catch (Throwable t) {
            outcome = "FAILURE";
            httpStatus = 500;
            error = t;
            throw t;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            try {
                persistAuditLog(joinPoint, operationType, outcome, httpStatus, duration, error);
            } catch (Exception e) {
                auditLogFailures.increment();
                log.error("Failed to record audit log", e);
            }
        }
    }

    /**
     * 异步持久化审计日志
     */
    @Async
    protected void persistAuditLog(ProceedingJoinPoint joinPoint, String operationType,
                                   String outcome, Integer httpStatus, long durationMs,
                                   Throwable error) {
        try {
            HttpServletRequest request = getCurrentRequest();
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();

            String action = resolveAction(method);
            String resource = resolveResource(request, method);
            String operator = resolveOperator(request);
            String clientIp = extractClientIp(request);
            String riskLevel = assessRiskLevel(resource, action, operationType);

            AuditLogEntity auditLog = AuditLogEntity.builder()
                    .operationType(operationType)
                    .action(action)
                    .resource(resource)
                    .operator(operator)
                    .clientIp(clientIp)
                    .httpMethod(request != null ? request.getMethod() : "UNKNOWN")
                    .httpStatus(httpStatus)
                    .outcome(outcome)
                    .riskLevel(riskLevel)
                    .durationMs(durationMs)
                    .userAgent(request != null ? truncate(request.getHeader("User-Agent"), 500) : null)
                    .details(error != null ? truncate(error.getMessage(), 500) : null)
                    .build();

            auditLogRepository.save(auditLog);
            auditLogTotal.increment();

            if ("HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel)) {
                log.warn("[AUDIT] High-risk operation: type={}, action={}, resource={}, operator={}, ip={}, outcome={}",
                        operationType, action, resource, operator, clientIp, outcome);
            }
        } catch (Exception e) {
            auditLogFailures.increment();
            log.error("Failed to persist audit log", e);
        }
    }

    private String resolveAction(Method method) {
        if (method.isAnnotationPresent(PostMapping.class)) return "CREATE";
        if (method.isAnnotationPresent(PutMapping.class)) return "UPDATE";
        if (method.isAnnotationPresent(DeleteMapping.class)) return "DELETE";
        if (method.isAnnotationPresent(PatchMapping.class)) return "PATCH";
        if (method.isAnnotationPresent(GetMapping.class)) return "READ";
        return "UNKNOWN";
    }

    private String resolveResource(HttpServletRequest request, Method method) {
        if (request != null) {
            return request.getRequestURI();
        }
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    private String resolveOperator(HttpServletRequest request) {
        // 1. 从 SecurityContext 获取
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        // 2. 从请求头获取
        if (request != null) {
            String authHeader = request.getHeader("X-Operator");
            if (authHeader != null && !authHeader.isEmpty()) {
                return authHeader;
            }
        }
        // 3. 使用 IP 作为兜底
        return "ip:" + (request != null ? extractClientIp(request) : "unknown");
    }

    private String assessRiskLevel(String resource, String action, String operationType) {
        // 降级操作 = CRITICAL
        if ("DEGRADATION".equals(operationType)) {
            return "CRITICAL";
        }
        // DELETE 操作 = HIGH
        if ("DELETE".equals(action)) {
            return "HIGH";
        }
        // 高风险 URI = HIGH
        if (resource != null) {
            for (String keyword : HIGH_RISK_KEYWORDS) {
                if (resource.contains(keyword)) {
                    return "HIGH";
                }
            }
        }
        // 写操作 = MEDIUM
        if ("CREATE".equals(action) || "UPDATE".equals(action) || "PATCH".equals(action)) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) + "..." : value;
    }
}
