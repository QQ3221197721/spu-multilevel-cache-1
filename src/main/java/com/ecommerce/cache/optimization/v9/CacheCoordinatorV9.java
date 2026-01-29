package com.ecommerce.cache.optimization.v9;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V9缓存协调器
 * 统一管理所有V9组件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheCoordinatorV9 {
    
    private final OptimizationV9Properties properties;
    private final MeterRegistry meterRegistry;
    private final CRDTDataStructures crdt;
    private final VectorClockManager vectorClock;
    private final EdgeComputeGateway edgeGateway;
    private final MLWarmupEngine mlWarmup;
    
    private final ConcurrentMap<String, ComponentStatus> componentStatuses = new ConcurrentHashMap<>();
    private final Queue<CoordinationEvent> eventLog = new ConcurrentLinkedQueue<>();
    
    private ScheduledExecutorService scheduler;
    private final AtomicLong eventCount = new AtomicLong(0);
    private volatile boolean running = false;
    private volatile long startTime;
    
    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("[V9协调器] 已禁用");
            return;
        }
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "v9-coordinator");
            t.setDaemon(true);
            return t;
        });
        
        running = true;
        startTime = System.currentTimeMillis();
        
        initComponentStatuses();
        
        int interval = properties.getCoordinator().getHealthCheckIntervalSec();
        scheduler.scheduleWithFixedDelay(this::healthCheck, interval, interval, TimeUnit.SECONDS);
        
        logEvent("STARTUP", "V9协调器启动");
        log.info("[V9协调器] 初始化完成");
    }
    
    @PreDestroy
    public void shutdown() {
        running = false;
        logEvent("SHUTDOWN", "V9协调器关闭");
        if (scheduler != null) scheduler.shutdown();
        log.info("[V9协调器] 已关闭 - 运行时长: {}s", (System.currentTimeMillis() - startTime) / 1000);
    }
    
    private void initComponentStatuses() {
        registerComponent("crdt", properties.getCrdt().isEnabled());
        registerComponent("vectorClock", properties.getVectorClock().isEnabled());
        registerComponent("edgeCompute", properties.getEdgeCompute().isEnabled());
        registerComponent("mlWarmup", properties.getMlWarmup().isEnabled());
    }
    
    private void registerComponent(String name, boolean enabled) {
        componentStatuses.put(name, new ComponentStatus(name,
            enabled ? HealthState.UP : HealthState.DISABLED,
            System.currentTimeMillis(), new LinkedHashMap<>()));
    }
    
    private void healthCheck() {
        try {
            checkComponent("crdt", crdt::getStatistics);
            checkComponent("vectorClock", vectorClock::getStatistics);
            checkComponent("edgeCompute", edgeGateway::getStatistics);
            checkComponent("mlWarmup", mlWarmup::getStatistics);
        } catch (Exception e) {
            log.warn("[V9协调器] 健康检查失败: {}", e.getMessage());
        }
    }
    
    private void checkComponent(String name, java.util.function.Supplier<Map<String, Object>> supplier) {
        ComponentStatus status = componentStatuses.get(name);
        if (status == null || status.state == HealthState.DISABLED) return;
        
        try {
            Map<String, Object> stats = supplier.get();
            componentStatuses.put(name, new ComponentStatus(name, HealthState.UP, 
                System.currentTimeMillis(), stats));
        } catch (Exception e) {
            componentStatuses.put(name, new ComponentStatus(name, HealthState.DOWN,
                System.currentTimeMillis(), Map.of("error", e.getMessage())));
        }
    }
    
    private void logEvent(String type, String message) {
        eventLog.offer(new CoordinationEvent(UUID.randomUUID().toString(), type, message, System.currentTimeMillis()));
        while (eventLog.size() > 500) eventLog.poll();
        eventCount.incrementAndGet();
    }
    
    public Map<String, ComponentStatus> getComponentStatuses() {
        return new LinkedHashMap<>(componentStatuses);
    }
    
    public List<CoordinationEvent> getEventLog() {
        return new ArrayList<>(eventLog);
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("running", running);
        stats.put("uptime", (System.currentTimeMillis() - startTime) / 1000 + "s");
        stats.put("eventCount", eventCount.get());
        
        Map<String, String> componentStates = new LinkedHashMap<>();
        for (var entry : componentStatuses.entrySet()) {
            componentStates.put(entry.getKey(), entry.getValue().state.name());
        }
        stats.put("components", componentStates);
        
        Map<String, Object> subStats = new LinkedHashMap<>();
        if (properties.getCrdt().isEnabled()) subStats.put("crdt", crdt.getStatistics());
        if (properties.getVectorClock().isEnabled()) subStats.put("vectorClock", vectorClock.getStatistics());
        if (properties.getEdgeCompute().isEnabled()) subStats.put("edgeCompute", edgeGateway.getStatistics());
        if (properties.getMlWarmup().isEnabled()) subStats.put("mlWarmup", mlWarmup.getStatistics());
        stats.put("subComponents", subStats);
        
        return stats;
    }
    
    public enum HealthState { UP, DOWN, DEGRADED, DISABLED }
    
    @Data
    public static class ComponentStatus {
        private final String name;
        private final HealthState state;
        private final long lastCheck;
        private final Map<String, Object> details;
    }
    
    @Data
    public static class CoordinationEvent {
        private final String id;
        private final String type;
        private final String message;
        private final long timestamp;
    }
}
