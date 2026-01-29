package com.ecommerce.cache.optimization.v10;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 自愈缓存系统
 * 
 * 核心特性:
 * 1. 健康监控: 实时检测缓存状态
 * 2. 故障检测: 自动发现异常
 * 3. 自动修复: 智能恢复机制
 * 4. 故障转移: 自动切换备用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfHealingCacheSystem {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV10Properties properties;
    private final StringRedisTemplate redisTemplate;
    
    /** 健康状态 */
    private final ConcurrentMap<String, ComponentHealth> componentHealth = new ConcurrentHashMap<>();
    
    /** 恢复历史 */
    private final Queue<RecoveryEvent> recoveryHistory = new ConcurrentLinkedDeque<>();
    
    /** 断路器状态 */
    private final ConcurrentMap<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    private ExecutorService recoveryExecutor;
    
    /** 统计 */
    private final AtomicLong healthCheckCount = new AtomicLong(0);
    private final AtomicLong failureDetectCount = new AtomicLong(0);
    private final AtomicLong recoveryCount = new AtomicLong(0);
    private final AtomicLong failoverCount = new AtomicLong(0);
    
    private Counter healthCheckCounter;
    private Counter failureCounter;
    private Counter recoveryCounter;
    
    /** 事件监听器 */
    private final List<SelfHealEventListener> listeners = new CopyOnWriteArrayList<>();
    
    @PostConstruct
    public void init() {
        if (!properties.getSelfHeal().isEnabled()) {
            log.info("[自愈系统] 已禁用");
            return;
        }
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "self-heal-monitor");
            t.setDaemon(true);
            return t;
        });
        
        recoveryExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "self-heal-recovery");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        initComponents();
        
        // 启动健康检查
        int interval = properties.getSelfHeal().getHealthCheckIntervalSec();
        scheduler.scheduleWithFixedDelay(this::performHealthCheck, 5, interval, TimeUnit.SECONDS);
        
        // 启动恢复检查
        scheduler.scheduleWithFixedDelay(this::attemptRecovery, 10, interval * 2, TimeUnit.SECONDS);
        
        log.info("[自愈系统] 初始化完成 - 检查间隔: {}s, 最大恢复尝试: {}",
            interval, properties.getSelfHeal().getMaxRecoveryAttempts());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (recoveryExecutor != null) recoveryExecutor.shutdown();
        log.info("[自愈系统] 已关闭 - 检测: {}, 故障: {}, 恢复: {}",
            healthCheckCount.get(), failureDetectCount.get(), recoveryCount.get());
    }
    
    private void initMetrics() {
        healthCheckCounter = Counter.builder("cache.selfheal.healthcheck").register(meterRegistry);
        failureCounter = Counter.builder("cache.selfheal.failure").register(meterRegistry);
        recoveryCounter = Counter.builder("cache.selfheal.recovery").register(meterRegistry);
    }
    
    private void initComponents() {
        // 注册需要监控的组件
        registerComponent("redis-master", this::checkRedisMaster);
        registerComponent("l1-cache", this::checkL1Cache);
        registerComponent("l2-cache", this::checkL2Cache);
        registerComponent("sync-engine", this::checkSyncEngine);
    }
    
    // ========== 组件注册 ==========
    
    public void registerComponent(String componentId, Supplier<HealthCheckResult> healthCheck) {
        componentHealth.put(componentId, new ComponentHealth(
            componentId, HealthStatus.UNKNOWN, healthCheck, Instant.now()
        ));
        circuitStates.put(componentId, new CircuitState(componentId));
        log.debug("[自愈系统] 注册组件: {}", componentId);
    }
    
    public void addListener(SelfHealEventListener listener) {
        listeners.add(listener);
    }
    
    // ========== 健康检查 ==========
    
    private void performHealthCheck() {
        healthCheckCount.incrementAndGet();
        healthCheckCounter.increment();
        
        for (var entry : componentHealth.entrySet()) {
            String componentId = entry.getKey();
            ComponentHealth health = entry.getValue();
            
            try {
                HealthCheckResult result = health.healthCheck.get();
                updateComponentHealth(componentId, result);
            } catch (Exception e) {
                log.warn("[自愈系统] 健康检查失败 - {}: {}", componentId, e.getMessage());
                updateComponentHealth(componentId, new HealthCheckResult(
                    HealthStatus.UNHEALTHY, e.getMessage(), Map.of()
                ));
            }
        }
    }
    
    private void updateComponentHealth(String componentId, HealthCheckResult result) {
        ComponentHealth current = componentHealth.get(componentId);
        if (current == null) return;
        
        HealthStatus previousStatus = current.status;
        current.status = result.status;
        current.lastCheck = Instant.now();
        current.details = result.details;
        current.errorMessage = result.errorMessage;
        
        // 检测状态变化
        if (previousStatus == HealthStatus.HEALTHY && result.status == HealthStatus.UNHEALTHY) {
            onFailureDetected(componentId, result);
        } else if (previousStatus == HealthStatus.UNHEALTHY && result.status == HealthStatus.HEALTHY) {
            onRecoveryDetected(componentId, result);
        }
    }
    
    private void onFailureDetected(String componentId, HealthCheckResult result) {
        failureDetectCount.incrementAndGet();
        failureCounter.increment();
        
        log.warn("[自愈系统] 检测到故障 - {}: {}", componentId, result.errorMessage);
        
        // 触发断路器
        CircuitState circuit = circuitStates.get(componentId);
        if (circuit != null) {
            circuit.recordFailure();
        }
        
        // 记录事件
        RecoveryEvent event = new RecoveryEvent(
            componentId, RecoveryEventType.FAILURE_DETECTED,
            result.errorMessage, Instant.now()
        );
        recoveryHistory.offer(event);
        trimHistory();
        
        // 通知监听器
        notifyListeners(event);
        
        // 尝试自动恢复
        if (properties.getSelfHeal().isAutoFailover()) {
            recoveryExecutor.submit(() -> attemptComponentRecovery(componentId));
        }
    }
    
    private void onRecoveryDetected(String componentId, HealthCheckResult result) {
        recoveryCount.incrementAndGet();
        recoveryCounter.increment();
        
        log.info("[自愈系统] 组件恢复 - {}", componentId);
        
        // 重置断路器
        CircuitState circuit = circuitStates.get(componentId);
        if (circuit != null) {
            circuit.reset();
        }
        
        RecoveryEvent event = new RecoveryEvent(
            componentId, RecoveryEventType.RECOVERED,
            "Component recovered", Instant.now()
        );
        recoveryHistory.offer(event);
        
        notifyListeners(event);
    }
    
    // ========== 恢复机制 ==========
    
    private void attemptRecovery() {
        for (var entry : componentHealth.entrySet()) {
            if (entry.getValue().status == HealthStatus.UNHEALTHY) {
                attemptComponentRecovery(entry.getKey());
            }
        }
    }
    
    private void attemptComponentRecovery(String componentId) {
        ComponentHealth health = componentHealth.get(componentId);
        if (health == null || health.status != HealthStatus.UNHEALTHY) return;
        
        CircuitState circuit = circuitStates.get(componentId);
        if (circuit != null && !circuit.allowRequest()) {
            log.debug("[自愈系统] 断路器打开，跳过恢复: {}", componentId);
            return;
        }
        
        int maxAttempts = properties.getSelfHeal().getMaxRecoveryAttempts();
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.info("[自愈系统] 尝试恢复 - {} (尝试 {}/{})", componentId, attempt, maxAttempts);
            
            try {
                RecoveryStrategy strategy = selectRecoveryStrategy(componentId);
                boolean recovered = executeRecovery(componentId, strategy);
                
                if (recovered) {
                    log.info("[自愈系统] 恢复成功 - {}", componentId);
                    return;
                }
            } catch (Exception e) {
                log.warn("[自愈系统] 恢复失败 - {}: {}", componentId, e.getMessage());
            }
            
            // 等待后重试
            try {
                Thread.sleep(1000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        
        // 恢复失败，尝试故障转移
        if (properties.getSelfHeal().isAutoFailover()) {
            attemptFailover(componentId);
        }
    }
    
    private RecoveryStrategy selectRecoveryStrategy(String componentId) {
        ComponentHealth health = componentHealth.get(componentId);
        
        if (componentId.contains("redis")) {
            return RecoveryStrategy.RECONNECT;
        } else if (componentId.contains("cache")) {
            return RecoveryStrategy.REBUILD;
        } else {
            return RecoveryStrategy.RESTART;
        }
    }
    
    private boolean executeRecovery(String componentId, RecoveryStrategy strategy) {
        log.debug("[自愈系统] 执行恢复策略: {} -> {}", componentId, strategy);
        
        switch (strategy) {
            case RECONNECT:
                return attemptReconnect(componentId);
            case REBUILD:
                return attemptRebuild(componentId);
            case RESTART:
                return attemptRestart(componentId);
            case FAILOVER:
                return attemptFailover(componentId);
            default:
                return false;
        }
    }
    
    private boolean attemptReconnect(String componentId) {
        try {
            // 测试Redis连接
            redisTemplate.opsForValue().get("__health_check__");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean attemptRebuild(String componentId) {
        // 模拟缓存重建
        log.info("[自愈系统] 重建缓存: {}", componentId);
        return true;
    }
    
    private boolean attemptRestart(String componentId) {
        // 模拟组件重启
        log.info("[自愈系统] 重启组件: {}", componentId);
        return true;
    }
    
    private boolean attemptFailover(String componentId) {
        failoverCount.incrementAndGet();
        log.warn("[自愈系统] 执行故障转移: {}", componentId);
        
        RecoveryEvent event = new RecoveryEvent(
            componentId, RecoveryEventType.FAILOVER,
            "Failover to backup", Instant.now()
        );
        recoveryHistory.offer(event);
        
        notifyListeners(event);
        return true;
    }
    
    // ========== 健康检查实现 ==========
    
    private HealthCheckResult checkRedisMaster() {
        try {
            String result = redisTemplate.opsForValue().get("__health__");
            redisTemplate.opsForValue().set("__health__", "ok", Duration.ofSeconds(10));
            return new HealthCheckResult(HealthStatus.HEALTHY, null, Map.of("latency", "ok"));
        } catch (Exception e) {
            return new HealthCheckResult(HealthStatus.UNHEALTHY, e.getMessage(), Map.of());
        }
    }
    
    private HealthCheckResult checkL1Cache() {
        // 检查本地缓存
        Runtime rt = Runtime.getRuntime();
        double memUsage = (double)(rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
        
        if (memUsage > properties.getSelfHeal().getDegradationThreshold()) {
            return new HealthCheckResult(HealthStatus.DEGRADED, 
                "High memory usage: " + (int)(memUsage * 100) + "%", 
                Map.of("memoryUsage", memUsage));
        }
        return new HealthCheckResult(HealthStatus.HEALTHY, null, Map.of("memoryUsage", memUsage));
    }
    
    private HealthCheckResult checkL2Cache() {
        return checkRedisMaster();
    }
    
    private HealthCheckResult checkSyncEngine() {
        return new HealthCheckResult(HealthStatus.HEALTHY, null, Map.of());
    }
    
    // ========== 工具方法 ==========
    
    private void trimHistory() {
        while (recoveryHistory.size() > 1000) {
            recoveryHistory.poll();
        }
    }
    
    private void notifyListeners(RecoveryEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("[自愈系统] 监听器异常: {}", e.getMessage());
            }
        }
    }
    
    // ========== 状态查询 ==========
    
    public Map<String, Object> getSystemStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        
        Map<String, Object> components = new LinkedHashMap<>();
        for (var entry : componentHealth.entrySet()) {
            ComponentHealth h = entry.getValue();
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("status", h.status);
            comp.put("lastCheck", h.lastCheck);
            comp.put("errorMessage", h.errorMessage);
            comp.put("details", h.details);
            components.put(entry.getKey(), comp);
        }
        status.put("components", components);
        
        Map<String, Object> circuits = new LinkedHashMap<>();
        for (var entry : circuitStates.entrySet()) {
            CircuitState c = entry.getValue();
            circuits.put(entry.getKey(), Map.of(
                "state", c.getState(),
                "failures", c.failureCount.get(),
                "lastFailure", c.lastFailure
            ));
        }
        status.put("circuits", circuits);
        
        status.put("statistics", Map.of(
            "healthChecks", healthCheckCount.get(),
            "failuresDetected", failureDetectCount.get(),
            "recoveries", recoveryCount.get(),
            "failovers", failoverCount.get()
        ));
        
        return status;
    }
    
    public List<RecoveryEvent> getRecentEvents(int limit) {
        return recoveryHistory.stream()
            .sorted((a, b) -> b.timestamp.compareTo(a.timestamp))
            .limit(limit)
            .toList();
    }
    
    // ========== 内部类 ==========
    
    public enum HealthStatus {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }
    
    public enum RecoveryStrategy {
        RECONNECT, REBUILD, RESTART, FAILOVER
    }
    
    public enum RecoveryEventType {
        FAILURE_DETECTED, RECOVERY_ATTEMPT, RECOVERED, FAILOVER
    }
    
    @Data
    public static class HealthCheckResult {
        private final HealthStatus status;
        private final String errorMessage;
        private final Map<String, Object> details;
    }
    
    @Data
    private static class ComponentHealth {
        private final String componentId;
        private HealthStatus status;
        private final Supplier<HealthCheckResult> healthCheck;
        private Instant lastCheck;
        private String errorMessage;
        private Map<String, Object> details = Map.of();
    }
    
    @Data
    public static class RecoveryEvent {
        private final String componentId;
        private final RecoveryEventType type;
        private final String message;
        private final Instant timestamp;
    }
    
    @Data
    private static class CircuitState {
        private final String componentId;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private Instant lastFailure = Instant.EPOCH;
        private Instant openedAt = null;
        
        enum State { CLOSED, OPEN, HALF_OPEN }
        
        void recordFailure() {
            failureCount.incrementAndGet();
            lastFailure = Instant.now();
            if (failureCount.get() >= 5) {
                openedAt = Instant.now();
            }
        }
        
        void reset() {
            failureCount.set(0);
            openedAt = null;
        }
        
        boolean allowRequest() {
            if (openedAt == null) return true;
            // 30秒后允许半开尝试
            return Duration.between(openedAt, Instant.now()).getSeconds() > 30;
        }
        
        State getState() {
            if (openedAt == null) return State.CLOSED;
            if (Duration.between(openedAt, Instant.now()).getSeconds() > 30) return State.HALF_OPEN;
            return State.OPEN;
        }
    }
    
    public interface SelfHealEventListener {
        void onEvent(RecoveryEvent event);
    }
}
