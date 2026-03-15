package com.ecommerce.cache.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 慢查询监控 AOP 切面
 *
 * 功能：
 * 1. 拦截所有 JPA Repository / Service 层 DB 访问
 * 2. 记录超过阈值的慢查询（查询名、耗时、线程、时间戳）
 * 3. 暴露 Prometheus 指标：db.query.duration / db.slow.query.total
 * 4. 提供 REST API 查看最近慢查询列表
 */
@Aspect
@Component
public class SlowQueryMonitorAspect {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryMonitorAspect.class);

    @Value("${monitoring.slow-query.threshold-ms:100}")
    private long slowQueryThresholdMs;

    @Value("${monitoring.slow-query.max-history:200}")
    private int maxHistory;

    private final Timer queryTimer;
    private final Counter slowQueryCounter;
    private final Counter queryTotalCounter;
    private final AtomicLong slowQueryTotal = new AtomicLong(0);

    // 慢查询历史记录（FIFO，线程安全）
    private final ConcurrentLinkedDeque<SlowQueryRecord> slowQueryLog = new ConcurrentLinkedDeque<>();

    public SlowQueryMonitorAspect(MeterRegistry meterRegistry) {
        this.queryTimer = Timer.builder("db.query.duration")
                .description("Database query duration")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.slowQueryCounter = Counter.builder("db.slow.query.total")
                .description("Total slow queries detected")
                .register(meterRegistry);

        this.queryTotalCounter = Counter.builder("db.query.total")
                .description("Total database queries")
                .register(meterRegistry);
    }

    /**
     * 拦截 Repository 层所有方法
     */
    @Around("execution(* com.ecommerce.cache.repository..*(..))")
    public Object monitorRepositoryQuery(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorQuery(joinPoint, "repo");
    }

    /**
     * 拦截 SpuService 中的 DB 相关方法
     */
    @Around("execution(* com.ecommerce.cache.service.SpuService.loadFromDatabase(..)) || " +
            "execution(* com.ecommerce.cache.service.SpuService.existsById(..)) || " +
            "execution(* com.ecommerce.cache.service.SpuService.getAllActiveSpuIds(..))")
    public Object monitorServiceDbQuery(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorQuery(joinPoint, "service");
    }

    private Object monitorQuery(ProceedingJoinPoint joinPoint, String layer) throws Throwable {
        String queryName = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
        long startNanos = System.nanoTime();

        queryTotalCounter.increment();

        try {
            Object result = joinPoint.proceed();

            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            queryTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);

            if (durationMs > slowQueryThresholdMs) {
                recordSlowQuery(queryName, durationMs, layer, null);
            }

            return result;
        } catch (Throwable ex) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            queryTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            recordSlowQuery(queryName, durationMs, layer, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private void recordSlowQuery(String queryName, long durationMs, String layer, String error) {
        slowQueryCounter.increment();
        long total = slowQueryTotal.incrementAndGet();

        SlowQueryRecord record = new SlowQueryRecord(
                queryName, durationMs, layer,
                Thread.currentThread().getName(),
                Instant.now().toString(),
                error
        );

        slowQueryLog.addFirst(record);
        // 控制历史大小
        while (slowQueryLog.size() > maxHistory) {
            slowQueryLog.removeLast();
        }

        if (error != null) {
            log.warn("Slow query [{}] {}ms (layer={}, error={}, thread={})",
                    queryName, durationMs, layer, error, Thread.currentThread().getName());
        } else {
            log.warn("Slow query [{}] {}ms (layer={}, thread={})",
                    queryName, durationMs, layer, Thread.currentThread().getName());
        }
    }

    /**
     * 获取慢查询列表（供 REST 端点使用）
     */
    public List<SlowQueryRecord> getSlowQueryLog(int limit) {
        return slowQueryLog.stream().limit(limit).toList();
    }

    /**
     * 获取慢查询总数
     */
    public long getSlowQueryTotal() {
        return slowQueryTotal.get();
    }

    /**
     * 慢查询记录
     */
    public record SlowQueryRecord(
            String queryName,
            long durationMs,
            String layer,
            String thread,
            String timestamp,
            String error
    ) {}
}

/**
 * 慢查询监控 REST 端点
 */
@RestController
@RequestMapping("/api/monitor/slow-queries")
class SlowQueryEndpoint {

    private final SlowQueryMonitorAspect slowQueryMonitor;

    SlowQueryEndpoint(SlowQueryMonitorAspect slowQueryMonitor) {
        this.slowQueryMonitor = slowQueryMonitor;
    }

    /**
     * 获取最近的慢查询列表
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSlowQueries(
            @RequestParam(defaultValue = "50") int limit) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("totalSlowQueries", slowQueryMonitor.getSlowQueryTotal());
        result.put("queries", slowQueryMonitor.getSlowQueryLog(limit));

        return ResponseEntity.ok(result);
    }

    /**
     * 清理慢查询历史
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clearSlowQueries() {
        // 获取历史（只读快照），实际不提供清理入口以保证安全
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Slow query log is managed automatically (max 200 entries, FIFO)");
        return ResponseEntity.ok(result);
    }
}
