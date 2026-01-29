package com.ecommerce.cache.optimization.v8;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自动伸缩控制器
 * 
 * 核心特性:
 * 1. CPU/内存监控: 实时系统资源监控
 * 2. 阈值触发: 基于阈值的伸缩决策
 * 3. 冷却时间: 防止频繁伸缩
 * 4. 预测性伸缩: 基于历史数据预测
 * 5. 平滑伸缩: 渐进式实例调整
 * 6. 多维度指标: 综合多指标决策
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoScaleController {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV8Properties properties;
    
    // ========== 伸缩状态 ==========
    
    /** 当前实例数 */
    private final AtomicInteger currentInstances = new AtomicInteger(2);
    
    /** 目标实例数 */
    private final AtomicInteger targetInstances = new AtomicInteger(2);
    
    /** 历史指标(用于预测) */
    private final Queue<MetricSnapshot> metricHistory = new ConcurrentLinkedQueue<>();
    
    /** 伸缩事件 */
    private final Queue<ScaleEvent> scaleEvents = new ConcurrentLinkedQueue<>();
    
    /** 伸缩监听器 */
    private final List<ScaleListener> listeners = new CopyOnWriteArrayList<>();
    
    /** 上次伸缩时间 */
    private volatile long lastScaleUpTime = 0;
    private volatile long lastScaleDownTime = 0;
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 系统监控 */
    private OperatingSystemMXBean osBean;
    
    /** 统计 */
    private final AtomicLong scaleUpCount = new AtomicLong(0);
    private final AtomicLong scaleDownCount = new AtomicLong(0);
    private final AtomicLong evaluationCount = new AtomicLong(0);
    
    /** 运行状态 */
    private volatile boolean running = false;
    
    // ========== 常量 ==========
    
    private static final int MAX_HISTORY_SIZE = 100;
    private static final int PREDICTION_WINDOW = 10;
    
    // ========== 指标 ==========
    
    private Counter scaleUpCounter;
    private Counter scaleDownCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getAutoScale().isEnabled()) {
            log.info("[自动伸缩] 已禁用");
            return;
        }
        
        osBean = ManagementFactory.getOperatingSystemMXBean();
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "autoscale-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        running = true;
        
        currentInstances.set(properties.getAutoScale().getMinInstances());
        targetInstances.set(properties.getAutoScale().getMinInstances());
        
        // 启动评估任务
        int period = properties.getAutoScale().getEvaluationPeriodSec();
        scheduler.scheduleWithFixedDelay(
            this::evaluate,
            period,
            period,
            TimeUnit.SECONDS
        );
        
        // 启动指标收集
        scheduler.scheduleWithFixedDelay(
            this::collectMetrics,
            5,
            5,
            TimeUnit.SECONDS
        );
        
        log.info("[自动伸缩] 初始化完成 - 范围: [{}, {}], 评估周期: {}s",
            properties.getAutoScale().getMinInstances(),
            properties.getAutoScale().getMaxInstances(),
            period);
    }
    
    @PreDestroy
    public void shutdown() {
        running = false;
        
        if (scheduler != null) {
            scheduler.shutdown();
        }
        
        log.info("[自动伸缩] 已关闭 - 扩容: {}, 缩容: {}, 评估: {}",
            scaleUpCount.get(), scaleDownCount.get(), evaluationCount.get());
    }
    
    private void initMetrics() {
        scaleUpCounter = Counter.builder("cache.autoscale.up")
            .description("扩容次数")
            .register(meterRegistry);
        
        scaleDownCounter = Counter.builder("cache.autoscale.down")
            .description("缩容次数")
            .register(meterRegistry);
        
        Gauge.builder("cache.autoscale.instances", currentInstances::get)
            .description("当前实例数")
            .register(meterRegistry);
        
        Gauge.builder("cache.autoscale.target", targetInstances::get)
            .description("目标实例数")
            .register(meterRegistry);
    }
    
    // ========== 核心API ==========
    
    /**
     * 手动设置目标实例数
     */
    public void setTargetInstances(int target) {
        int min = properties.getAutoScale().getMinInstances();
        int max = properties.getAutoScale().getMaxInstances();
        target = Math.max(min, Math.min(max, target));
        
        int current = currentInstances.get();
        targetInstances.set(target);
        
        if (target > current) {
            triggerScaleUp(target - current, "manual");
        } else if (target < current) {
            triggerScaleDown(current - target, "manual");
        }
    }
    
    /**
     * 获取当前实例数
     */
    public int getCurrentInstances() {
        return currentInstances.get();
    }
    
    /**
     * 获取目标实例数
     */
    public int getTargetInstances() {
        return targetInstances.get();
    }
    
    /**
     * 注册伸缩监听器
     */
    public void addListener(ScaleListener listener) {
        listeners.add(listener);
    }
    
    /**
     * 移除监听器
     */
    public void removeListener(ScaleListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * 强制扩容
     */
    public boolean forceScaleUp(int count) {
        return triggerScaleUp(count, "forced");
    }
    
    /**
     * 强制缩容
     */
    public boolean forceScaleDown(int count) {
        return triggerScaleDown(count, "forced");
    }
    
    /**
     * 获取伸缩事件
     */
    public List<ScaleEvent> getRecentEvents() {
        return new ArrayList<>(scaleEvents);
    }
    
    /**
     * 获取预测的最优实例数
     */
    public int getPredictedOptimalInstances() {
        if (!properties.getAutoScale().isPredictiveEnabled()) {
            return currentInstances.get();
        }
        return predictOptimalInstances();
    }
    
    // ========== 评估逻辑 ==========
    
    private void evaluate() {
        if (!running) {
            return;
        }
        
        evaluationCount.incrementAndGet();
        
        try {
            // 收集当前指标
            double cpuLoad = getCpuLoad();
            double memoryUsage = getMemoryUsage();
            double qps = getQps();
            
            int current = currentInstances.get();
            int target = current;
            String reason = null;
            
            // 判断是否需要扩容
            if (shouldScaleUp(cpuLoad, memoryUsage, qps)) {
                target = Math.min(current + properties.getAutoScale().getScaleUpStep(),
                    properties.getAutoScale().getMaxInstances());
                reason = String.format("CPU: %.1f%%, Memory: %.1f%%", cpuLoad, memoryUsage);
            }
            // 判断是否需要缩容
            else if (shouldScaleDown(cpuLoad, memoryUsage, qps)) {
                target = Math.max(current - properties.getAutoScale().getScaleDownStep(),
                    properties.getAutoScale().getMinInstances());
                reason = String.format("CPU: %.1f%%, Memory: %.1f%%", cpuLoad, memoryUsage);
            }
            
            // 预测性调整
            if (properties.getAutoScale().isPredictiveEnabled()) {
                int predicted = predictOptimalInstances();
                if (predicted > target) {
                    target = predicted;
                    reason = "predictive";
                }
            }
            
            // 执行伸缩
            if (target != current) {
                targetInstances.set(target);
                
                if (target > current) {
                    triggerScaleUp(target - current, reason);
                } else {
                    triggerScaleDown(current - target, reason);
                }
            }
            
        } catch (Exception e) {
            log.error("[自动伸缩] 评估失败", e);
        }
    }
    
    private boolean shouldScaleUp(double cpuLoad, double memoryUsage, double qps) {
        long now = System.currentTimeMillis();
        int cooldown = properties.getAutoScale().getScaleUpCooldownSec() * 1000;
        
        if (now - lastScaleUpTime < cooldown) {
            return false;
        }
        
        if (currentInstances.get() >= properties.getAutoScale().getMaxInstances()) {
            return false;
        }
        
        int threshold = properties.getAutoScale().getScaleUpThreshold();
        return cpuLoad > threshold || memoryUsage > threshold;
    }
    
    private boolean shouldScaleDown(double cpuLoad, double memoryUsage, double qps) {
        long now = System.currentTimeMillis();
        int cooldown = properties.getAutoScale().getScaleDownCooldownSec() * 1000;
        
        if (now - lastScaleDownTime < cooldown) {
            return false;
        }
        
        if (currentInstances.get() <= properties.getAutoScale().getMinInstances()) {
            return false;
        }
        
        int threshold = properties.getAutoScale().getScaleDownThreshold();
        return cpuLoad < threshold && memoryUsage < threshold;
    }
    
    private boolean triggerScaleUp(int count, String reason) {
        int newCount = Math.min(
            currentInstances.addAndGet(count),
            properties.getAutoScale().getMaxInstances()
        );
        currentInstances.set(newCount);
        
        lastScaleUpTime = System.currentTimeMillis();
        scaleUpCount.incrementAndGet();
        scaleUpCounter.increment();
        
        ScaleEvent event = new ScaleEvent(
            UUID.randomUUID().toString(),
            ScaleType.SCALE_UP,
            count,
            newCount,
            reason,
            System.currentTimeMillis()
        );
        
        saveEvent(event);
        notifyListeners(event);
        
        log.info("[自动伸缩] 扩容: +{} -> {} ({})", count, newCount, reason);
        
        return true;
    }
    
    private boolean triggerScaleDown(int count, String reason) {
        int newCount = Math.max(
            currentInstances.addAndGet(-count),
            properties.getAutoScale().getMinInstances()
        );
        currentInstances.set(newCount);
        
        lastScaleDownTime = System.currentTimeMillis();
        scaleDownCount.incrementAndGet();
        scaleDownCounter.increment();
        
        ScaleEvent event = new ScaleEvent(
            UUID.randomUUID().toString(),
            ScaleType.SCALE_DOWN,
            count,
            newCount,
            reason,
            System.currentTimeMillis()
        );
        
        saveEvent(event);
        notifyListeners(event);
        
        log.info("[自动伸缩] 缩容: -{} -> {} ({})", count, newCount, reason);
        
        return true;
    }
    
    private int predictOptimalInstances() {
        if (metricHistory.size() < PREDICTION_WINDOW) {
            return currentInstances.get();
        }
        
        // 获取最近的指标
        List<MetricSnapshot> recent = new ArrayList<>(metricHistory).subList(
            Math.max(0, metricHistory.size() - PREDICTION_WINDOW),
            metricHistory.size()
        );
        
        // 计算趋势
        double cpuTrend = calculateTrend(recent, m -> m.cpuLoad);
        double memTrend = calculateTrend(recent, m -> m.memoryUsage);
        
        int current = currentInstances.get();
        
        // 根据趋势预测
        if (cpuTrend > 5 || memTrend > 5) {
            // 上升趋势，提前扩容
            return Math.min(current + 1, properties.getAutoScale().getMaxInstances());
        } else if (cpuTrend < -5 && memTrend < -5) {
            // 下降趋势，可以缩容
            return Math.max(current - 1, properties.getAutoScale().getMinInstances());
        }
        
        return current;
    }
    
    private double calculateTrend(List<MetricSnapshot> snapshots, 
            java.util.function.ToDoubleFunction<MetricSnapshot> extractor) {
        if (snapshots.size() < 2) {
            return 0;
        }
        
        double first = extractor.applyAsDouble(snapshots.get(0));
        double last = extractor.applyAsDouble(snapshots.get(snapshots.size() - 1));
        
        return last - first;
    }
    
    private void collectMetrics() {
        MetricSnapshot snapshot = new MetricSnapshot(
            System.currentTimeMillis(),
            getCpuLoad(),
            getMemoryUsage(),
            getQps(),
            currentInstances.get()
        );
        
        metricHistory.offer(snapshot);
        
        while (metricHistory.size() > MAX_HISTORY_SIZE) {
            metricHistory.poll();
        }
    }
    
    // ========== 系统指标 ==========
    
    private double getCpuLoad() {
        try {
            double load = osBean.getSystemLoadAverage();
            if (load < 0) {
                // Windows不支持，使用CPU使用率
                return Runtime.getRuntime().availableProcessors() * 50; // 模拟
            }
            return load / osBean.getAvailableProcessors() * 100;
        } catch (Exception e) {
            return 50;
        }
    }
    
    private double getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        return (double) used / max * 100;
    }
    
    private double getQps() {
        // 模拟QPS获取
        return 1000 + new Random().nextInt(500);
    }
    
    private void saveEvent(ScaleEvent event) {
        scaleEvents.offer(event);
        while (scaleEvents.size() > 100) {
            scaleEvents.poll();
        }
    }
    
    private void notifyListeners(ScaleEvent event) {
        for (ScaleListener listener : listeners) {
            try {
                listener.onScale(event);
            } catch (Exception e) {
                log.warn("[自动伸缩] 通知监听器失败", e);
            }
        }
    }
    
    // ========== 统计信息 ==========
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("currentInstances", currentInstances.get());
        stats.put("targetInstances", targetInstances.get());
        stats.put("minInstances", properties.getAutoScale().getMinInstances());
        stats.put("maxInstances", properties.getAutoScale().getMaxInstances());
        stats.put("scaleUpCount", scaleUpCount.get());
        stats.put("scaleDownCount", scaleDownCount.get());
        stats.put("evaluationCount", evaluationCount.get());
        stats.put("running", running);
        
        // 当前系统指标
        Map<String, Object> currentMetrics = new LinkedHashMap<>();
        currentMetrics.put("cpuLoad", String.format("%.1f%%", getCpuLoad()));
        currentMetrics.put("memoryUsage", String.format("%.1f%%", getMemoryUsage()));
        currentMetrics.put("qps", getQps());
        stats.put("currentMetrics", currentMetrics);
        
        // 配置
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("scaleUpThreshold", properties.getAutoScale().getScaleUpThreshold());
        config.put("scaleDownThreshold", properties.getAutoScale().getScaleDownThreshold());
        config.put("predictiveEnabled", properties.getAutoScale().isPredictiveEnabled());
        stats.put("config", config);
        
        // 预测
        stats.put("predictedOptimal", getPredictedOptimalInstances());
        
        // 最近事件
        List<Map<String, Object>> events = new ArrayList<>();
        for (ScaleEvent event : scaleEvents) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", event.type.name());
            e.put("delta", event.delta);
            e.put("resultInstances", event.resultInstances);
            e.put("reason", event.reason);
            e.put("timestamp", event.timestamp);
            events.add(e);
        }
        stats.put("recentEvents", events);
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    public enum ScaleType {
        SCALE_UP, SCALE_DOWN
    }
    
    @Data
    public static class ScaleEvent {
        private final String id;
        private final ScaleType type;
        private final int delta;
        private final int resultInstances;
        private final String reason;
        private final long timestamp;
    }
    
    @Data
    private static class MetricSnapshot {
        private final long timestamp;
        private final double cpuLoad;
        private final double memoryUsage;
        private final double qps;
        private final int instances;
    }
    
    public interface ScaleListener {
        void onScale(ScaleEvent event);
    }
}
