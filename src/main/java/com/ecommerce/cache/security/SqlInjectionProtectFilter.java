package com.ecommerce.cache.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 注入 & XSS 防护过滤器
 *
 * 多层防护:
 * 1. 请求参数黑名单关键字检测
 * 2. 路径变量合法性校验
 * 3. Header 注入防护
 * 4. XSS 标签过滤
 *
 * 注: 本项目使用 Spring Data JPA + @Param 参数绑定,
 * 已天然免疫 SQL 注入。此过滤器是纵深防御的额外保障。
 */
@Component
@Order(2)  // 在限流过滤器之后
public class SqlInjectionProtectFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(SqlInjectionProtectFilter.class);

    @Value("${security.injection-protect.enabled:true}")
    private boolean enabled;

    @Value("${security.injection-protect.log-only:false}")
    private boolean logOnly;

    // SQL 注入关键字模式（不区分大小写）
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
            "(?i)(\\b(SELECT|INSERT|UPDATE|DELETE|DROP|UNION|ALTER|CREATE|EXEC|EXECUTE|TRUNCATE|" +
            "DECLARE|CAST|CONVERT|INTO|FROM|WHERE|HAVING|ORDER\\s+BY|GROUP\\s+BY)\\b|" +
            "(--|#|/\\*|\\*/|;\\s*$|'\\s*OR\\s+'|'\\s*AND\\s+'|" +
            "\\bOR\\b\\s+\\d+\\s*=\\s*\\d+|\\bAND\\b\\s+\\d+\\s*=\\s*\\d+|" +
            "'\\s*;\\s*|\\bWAITFOR\\b|\\bBENCHMARK\\b|\\bSLEEP\\b))"
    );

    // XSS 关键标签模式
    private static final Pattern XSS_PATTERN = Pattern.compile(
            "(?i)(<script[^>]*>|</script>|javascript:|on\\w+\\s*=|<iframe|<object|<embed|" +
            "<form[^>]*action|document\\.cookie|document\\.write|eval\\(|alert\\(|prompt\\(|confirm\\()"
    );

    // 路径遍历模式
    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(
            "(\\.\\./|\\.\\.\\\\|%2e%2e%2f|%2e%2e/|\\.\\.%2f|%252e%252e%252f)"
    );

    // 允许的 Content-Type
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/json", "application/x-www-form-urlencoded",
            "multipart/form-data", "text/plain"
    );

    private Counter injectionAttempts;
    private Counter xssAttempts;

    private final MeterRegistry meterRegistry;

    public SqlInjectionProtectFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        injectionAttempts = Counter.builder("security.injection.attempts_total")
                .description("SQL injection attempts detected")
                .register(meterRegistry);
        xssAttempts = Counter.builder("security.xss.attempts_total")
                .description("XSS attempts detected")
                .register(meterRegistry);
        log.info("SQL Injection & XSS Protect Filter initialized: enabled={}, logOnly={}", enabled, logOnly);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;
        String uri = httpReq.getRequestURI();

        // 跳过内部端点
        if (uri.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        // 1. URI 路径检查
        if (containsPathTraversal(uri)) {
            handleViolation(httpResp, "Path traversal detected", uri, httpReq);
            return;
        }

        // 2. 请求参数检查
        Map<String, String[]> params = httpReq.getParameterMap();
        for (Map.Entry<String, String[]> entry : params.entrySet()) {
            String paramName = entry.getKey();
            for (String value : entry.getValue()) {
                if (containsSqlInjection(value)) {
                    injectionAttempts.increment();
                    if (!logOnly) {
                        handleViolation(httpResp, "SQL injection detected in parameter: " + paramName, uri, httpReq);
                        return;
                    }
                    log.warn("[SECURITY] SQL injection attempt (log-only): param={}, value={}, uri={}, ip={}",
                            paramName, sanitizeForLog(value), uri, extractClientIp(httpReq));
                }
                if (containsXss(value)) {
                    xssAttempts.increment();
                    if (!logOnly) {
                        handleViolation(httpResp, "XSS attempt detected in parameter: " + paramName, uri, httpReq);
                        return;
                    }
                    log.warn("[SECURITY] XSS attempt (log-only): param={}, uri={}, ip={}",
                            paramName, uri, extractClientIp(httpReq));
                }
            }
        }

        // 3. Header 安全性检查 (常见注入向量)
        String userAgent = httpReq.getHeader("User-Agent");
        if (userAgent != null && (containsSqlInjection(userAgent) || containsXss(userAgent))) {
            injectionAttempts.increment();
            if (!logOnly) {
                handleViolation(httpResp, "Malicious User-Agent detected", uri, httpReq);
                return;
            }
        }

        String referer = httpReq.getHeader("Referer");
        if (referer != null && (containsSqlInjection(referer) || containsXss(referer))) {
            injectionAttempts.increment();
            if (!logOnly) {
                handleViolation(httpResp, "Malicious Referer detected", uri, httpReq);
                return;
            }
        }

        // 4. 添加安全响应头
        httpResp.setHeader("X-Content-Type-Options", "nosniff");
        httpResp.setHeader("X-Frame-Options", "DENY");
        httpResp.setHeader("X-XSS-Protection", "1; mode=block");
        httpResp.setHeader("Content-Security-Policy", "default-src 'self'");
        httpResp.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        httpResp.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

        // 用清理后的包装器继续处理（XSS 参数净化）
        chain.doFilter(new XssCleanRequestWrapper(httpReq), response);
    }

    private boolean containsSqlInjection(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return SQL_INJECTION_PATTERN.matcher(value).find();
    }

    private boolean containsXss(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return XSS_PATTERN.matcher(value).find();
    }

    private boolean containsPathTraversal(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        return PATH_TRAVERSAL_PATTERN.matcher(uri).find();
    }

    private void handleViolation(HttpServletResponse response, String reason, String uri,
                                 HttpServletRequest request) throws IOException {
        String clientIp = extractClientIp(request);
        log.error("[SECURITY] Request blocked: reason={}, uri={}, ip={}, method={}",
                reason, uri, clientIp, request.getMethod());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"Request blocked by security filter\"}");
    }

    /**
     * 日志安全: 截断并转义恶意输入避免日志注入
     */
    private String sanitizeForLog(String value) {
        if (value == null) return "null";
        String sanitized = value.replaceAll("[\\r\\n]", " ");
        return sanitized.length() > 200 ? sanitized.substring(0, 200) + "..." : sanitized;
    }

    private String extractClientIp(HttpServletRequest request) {
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

    /**
     * XSS 清洗请求包装器
     * 对所有请求参数值进行 HTML 实体转义
     */
    static class XssCleanRequestWrapper extends HttpServletRequestWrapper {

        XssCleanRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getParameter(String name) {
            String value = super.getParameter(name);
            return cleanXss(value);
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = super.getParameterValues(name);
            if (values == null) return null;
            String[] cleaned = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                cleaned[i] = cleanXss(values[i]);
            }
            return cleaned;
        }

        @Override
        public String getHeader(String name) {
            String value = super.getHeader(name);
            return cleanXss(value);
        }

        private static String cleanXss(String value) {
            if (value == null) return null;
            return value
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#x27;")
                    .replace("/", "&#x2F;");
        }
    }
}
