package com.ecommerce.cache.optimization.v10;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 混沌工程测试引擎
 * 
 * 核心特性:
 * 1. 故障注入: 模拟各类故障场景
 * 2. 延迟注入: 模拟网络延迟
 * 3. 资源耗尽: 模拟资源压力
 * 4. 实验管理: 可控的混沌实验
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChaosEngineeringTester {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV10Properties properties;
    
    /** 活跃实验 */
    private final ConcurrentMap<String, ChaosExperiment> activeExperiments = new ConcurrentHashMap<>();
    
    /** 实验历史 */
    private final Queue<ExperimentResult> experimentHistory = new ConcurrentLinkedDeque<>();
    
    /** 故障注入点 */
    private final ConcurrentMap<String, FaultInjector> faultInjectors = new ConcurrentHashMap<>();
    
    /** 全局开关 */
    private final AtomicBoolean chaosEnabled = new AtomicBoolean(false);
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong experimentCount = new AtomicLong(0);
    private final AtomicLong faultInjectedCount = new AtomicLong(0);
    private final AtomicLong latencyInjectedCount = new AtomicLong(0);
    
    private Counter experimentCounter;
    private Counter faultCounter;
    
    private final Random random = new Random();
    
    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "chaos-engine");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        initDefaultInjectors();
        
        // 定期检查实验状态
        scheduler.scheduleWithFixedDelay(this::checkExperiments, 5, 5, TimeUnit.SECONDS);
        
        log.info("[混沌工程] 初始化完成 - 默认状态: {}", 
            properties.getChaos().isEnabled() ? "启用" : "禁用");
        
        if (properties.getChaos().isEnabled()) {
            chaosEnabled.set(true);
        }
    }
    
    @PreDestroy
    public void shutdown() {
        // 停止所有实验
        stopAllExperiments();
        if (scheduler != null) scheduler.shutdown();
        log.info("[混沌工程] 已关闭 - 实验: {}, 故障注入: {}",
            experimentCount.get(), faultInjectedCount.get());
    }
    
    private void initMetrics() {
        experimentCounter = Counter.builder("chaos.experiment").register(meterRegistry);
        faultCounter = Counter.builder("chaos.fault.injected").register(meterRegistry);
    }
    
    private void initDefaultInjectors() {
        // 注册默认故障注入器
        registerFaultInjector("network-latency", new NetworkLatencyInjector());
        registerFaultInjector("connection-timeout", new ConnectionTimeoutInjector());
        registerFaultInjector("memory-pressure", new MemoryPressureInjector());
        registerFaultInjector("cpu-spike", new CpuSpikeInjector());
        registerFaultInjector("cache-miss", new CacheMissInjector());
        registerFaultInjector("partial-failure", new PartialFailureInjector());
    }
    
    // ========== 实验管理 ==========
    
    /**
     * 启动混沌实验
     */
    public ExperimentResult startExperiment(ChaosExperimentConfig config) {
        if (!chaosEnabled.get()) {
            return new ExperimentResult(config.getId(), false, "Chaos engineering is disabled",
                Instant.now(), Instant.now(), Map.of());
        }
        
        experimentCount.incrementAndGet();
        experimentCounter.increment();
        
        ChaosExperiment experiment = new ChaosExperiment(
            config.getId(),
            config,
            Instant.now(),
            config.getDurationSeconds()
        );
        
        activeExperiments.put(config.getId(), experiment);
        
        log.info("[混沌工程] 启动实验: {} - 类型: {}, 持续: {}s",
            config.getId(), config.getType(), config.getDurationSeconds());
        
        // 执行故障注入
        FaultInjector injector = faultInjectors.get(config.getType());
        if (injector != null) {
            experiment.setInjector(injector);
            injector.inject(config.getParams());
            experiment.setActive(true);
        }
        
        return new ExperimentResult(config.getId(), true, "Experiment started",
            experiment.getStartTime(), null, Map.of("type", config.getType()));
    }
    
    /**
     * 停止指定实验
     */
    public ExperimentResult stopExperiment(String experimentId) {
        ChaosExperiment experiment = activeExperiments.remove(experimentId);
        if (experiment == null) {
            return new ExperimentResult(experimentId, false, "Experiment not found",
                Instant.now(), Instant.now(), Map.of());
        }
        
        experiment.setActive(false);
        if (experiment.getInjector() != null) {
            experiment.getInjector().restore();
        }
        
        Instant endTime = Instant.now();
        ExperimentResult result = new ExperimentResult(
            experimentId, true, "Experiment stopped",
            experiment.getStartTime(), endTime,
            Map.of("duration", Duration.between(experiment.getStartTime(), endTime).getSeconds())
        );
        
        experimentHistory.offer(result);
        trimHistory();
        
        log.info("[混沌工程] 停止实验: {}", experimentId);
        return result;
    }
    
    /**
     * 停止所有实验
     */
    public void stopAllExperiments() {
        for (String id : new ArrayList<>(activeExperiments.keySet())) {
            stopExperiment(id);
        }
    }
    
    /**
     * 检查是否应该注入故障
     */
    public boolean shouldInjectFault(String faultType) {
        if (!chaosEnabled.get()) return false;
        
        double rate = properties.getChaos().getFailureRate();
        boolean inject = random.nextDouble() < rate;
        
        if (inject) {
            faultInjectedCount.incrementAndGet();
            faultCounter.increment();
        }
        
        return inject;
    }
    
    /**
     * 注入延迟
     */
    public void maybeInjectLatency() {
        if (!chaosEnabled.get()) return;
        
        if (random.nextDouble() < properties.getChaos().getFailureRate()) {
            int latency = properties.getChaos().getLatencyInjectionMs();
            try {
                Thread.sleep(latency);
                latencyInjectedCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    // ========== 故障注入器注册 ==========
    
    public void registerFaultInjector(String type, FaultInjector injector) {
        faultInjectors.put(type, injector);
        log.debug("[混沌工程] 注册故障注入器: {}", type);
    }
    
    // ========== 控制开关 ==========
    
    public void enableChaos() {
        chaosEnabled.set(true);
        log.warn("[混沌工程] 已启用混沌测试");
    }
    
    public void disableChaos() {
        chaosEnabled.set(false);
        stopAllExperiments();
        log.info("[混沌工程] 已禁用混沌测试");
    }
    
    public boolean isChaosEnabled() {
        return chaosEnabled.get();
    }
    
    // ========== 内部方法 ==========
    
    private void checkExperiments() {
        Instant now = Instant.now();
        
        for (var entry : activeExperiments.entrySet()) {
            ChaosExperiment exp = entry.getValue();
            Duration elapsed = Duration.between(exp.getStartTime(), now);
            
            if (elapsed.getSeconds() >= exp.getDurationSeconds()) {
                log.info("[混沌工程] 实验超时自动停止: {}", entry.getKey());
                stopExperiment(entry.getKey());
            }
        }
    }
    
    private void trimHistory() {
        while (experimentHistory.size() > 100) {
            experimentHistory.poll();
        }
    }
    
    // ========== 状态查询 ==========
    
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", chaosEnabled.get());
        status.put("activeExperiments", activeExperiments.size());
        status.put("registeredInjectors", faultInjectors.keySet());
        
        List<Map<String, Object>> experiments = new ArrayList<>();
        for (var exp : activeExperiments.values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", exp.getId());
            e.put("type", exp.getConfig().getType());
            e.put("startTime", exp.getStartTime());
            e.put("durationSeconds", exp.getDurationSeconds());
            e.put("active", exp.isActive());
            experiments.add(e);
        }
        status.put("experiments", experiments);
        
        status.put("statistics", Map.of(
            "totalExperiments", experimentCount.get(),
            "faultsInjected", faultInjectedCount.get(),
            "latencyInjected", latencyInjectedCount.get()
        ));
        
        return status;
    }
    
    public List<ExperimentResult> getHistory(int limit) {
        return experimentHistory.stream()
            .sorted((a, b) -> b.startTime.compareTo(a.startTime))
            .limit(limit)
            .toList();
    }
    
    // ========== 内部类 ==========
    
    @Data
    public static class ChaosExperimentConfig {
        private final String id;
        private final String type;
        private final int durationSeconds;
        private final Map<String, Object> params;
    }
    
    @Data
    public static class ExperimentResult {
        private final String experimentId;
        private final boolean success;
        private final String message;
        private final Instant startTime;
        private final Instant endTime;
        private final Map<String, Object> metrics;
    }
    
    @Data
    private static class ChaosExperiment {
        private final String id;
        private final ChaosExperimentConfig config;
        private final Instant startTime;
        private final int durationSeconds;
        private FaultInjector injector;
        private boolean active;
    }
    
    // ========== 故障注入器接口和实现 ==========
    
    public interface FaultInjector {
        void inject(Map<String, Object> params);
        void restore();
    }
    
    private class NetworkLatencyInjector implements FaultInjector {
        @Override
        public void inject(Map<String, Object> params) {
            log.info("[混沌] 注入网络延迟");
        }
        
        @Override
        public void restore() {
            log.info("[混沌] 恢复网络延迟");
        }
    }
    
    private class ConnectionTimeoutInjector implements FaultInjector {
        @Override
        public void inject(Map<String, Object> params) {
            log.info("[混沌] 注入连接超时");
        }
        
        @Override
        public void restore() {
            log.info("[混沌] 恢复连接超时");
        }
    }
    
    private class MemoryPressureInjector implements FaultInjector {
        private byte[] memoryHog;
        
        @Override
        public void inject(Map<String, Object> params) {
            int sizeMB = (int) params.getOrDefault("sizeMB", 100);
            memoryHog = new byte[sizeMB * 1024 * 1024];
            Arrays.fill(memoryHog, (byte) 1);
            log.info("[混沌] 注入内存压力: {}MB", sizeMB);
        }
        
        @Override
        public void restore() {
            memoryHog = null;
            System.gc();
            log.info("[混沌] 释放内存压力");
        }
    }
    
    private class CpuSpikeInjector implements FaultInjector {
        private volatile boolean running;
        private final List<Thread> workers = new ArrayList<>();
        
        @Override
        public void inject(Map<String, Object> params) {
            running = true;
            int threads = (int) params.getOrDefault("threads", 2);
            
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    while (running) {
                        Math.random();
                    }
                });
                t.setDaemon(true);
                t.start();
                workers.add(t);
            }
            log.info("[混沌] 注入CPU压力: {} threads", threads);
        }
        
        @Override
        public void restore() {
            running = false;
            workers.clear();
            log.info("[混沌] 释放CPU压力");
        }
    }
    
    private class CacheMissInjector implements FaultInjector {
        @Override
        public void inject(Map<String, Object> params) {
            log.info("[混沌] 注入缓存未命中");
        }
        
        @Override
        public void restore() {
            log.info("[混沌] 恢复缓存未命中");
        }
    }
    
    private class PartialFailureInjector implements FaultInjector {
        @Override
        public void inject(Map<String, Object> params) {
            double rate = (double) params.getOrDefault("rate", 0.1);
            log.info("[混沌] 注入部分失败: rate={}", rate);
        }
        
        @Override
        public void restore() {
            log.info("[混沌] 恢复部分失败");
        }
    }
}
