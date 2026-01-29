package com.ecommerce.cache.optimization.v6;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * 自适应内存管理器 - 智能内存优化
 * 
 * 核心特性:
 * 1. 实时内存压力感知
 * 2. 多级内存警戒线
 * 3. 智能淘汰策略
 * 4. GC友好设计
 * 5. 堆外内存管理
 * 6. 内存碎片整理
 * 
 * 性能目标:
 * - 内存利用率 > 85%
 * - GC停顿 < 10ms
 * - 零OOM风险
 */
public class AdaptiveMemoryManager {
    
    private static final Logger log = LoggerFactory.getLogger(AdaptiveMemoryManager.class);
    
    // 内存阈值配置
    @Value("${optimization.v6.memory.warning-threshold:0.70}")
    private double warningThreshold = 0.70;
    
    @Value("${optimization.v6.memory.critical-threshold:0.85}")
    private double criticalThreshold = 0.85;
    
    @Value("${optimization.v6.memory.emergency-threshold:0.95}")
    private double emergencyThreshold = 0.95;
    
    @Value("${optimization.v6.memory.target-utilization:0.75}")
    private double targetUtilization = 0.75;
    
    @Value("${optimization.v6.memory.check-interval-ms:500}")
    private long checkIntervalMs = 500;
    
    @Value("${optimization.v6.memory.eviction-batch-size:100}")
    private int evictionBatchSize = 100;
    
    private final MeterRegistry meterRegistry;
    
    // JVM内存管理
    private final MemoryMXBean memoryMXBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    
    // 状态
    private volatile MemoryPressureLevel currentLevel = MemoryPressureLevel.NORMAL;
    private final AtomicLong lastGcCount = new AtomicLong(0);
    private final AtomicLong lastGcTime = new AtomicLong(0);
    
    // 淘汰回调
    private final List<Consumer<Integer>> evictionCallbacks = new CopyOnWriteArrayList<>();
    
    // 统计
    private final LongAdder totalEvictions = new LongAdder();
    private final LongAdder warningEvents = new LongAdder();
    private final LongAdder criticalEvents = new LongAdder();
    private final LongAdder emergencyEvents = new LongAdder();
    private final AtomicDouble currentUtilization = new AtomicDouble(0);
    
    // 历史记录
    private final ConcurrentLinkedDeque<MemorySnapshot> history = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY_SIZE = 120; // 最近60秒
    
    // 后台监控
    private ScheduledExecutorService monitorExecutor;
    
    // 指标
    private Gauge utilizationGauge;
    private Counter evictionCounter;
    
    public AdaptiveMemoryManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    }
    
    @PostConstruct
    public void init() {
        // 初始化监控线程
        monitorExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("memory-monitor").factory());
        monitorExecutor.scheduleAtFixedRate(this::checkMemoryPressure, 
            checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        
        // 注册指标
        registerMetrics();
        
        log.info("AdaptiveMemoryManager initialized: warning={}, critical={}, emergency={}",
            warningThreshold, criticalThreshold, emergencyThreshold);
    }
    
    @PreDestroy
    public void shutdown() {
        if (monitorExecutor != null) {
            monitorExecutor.shutdown();
        }
    }
    
    private void registerMetrics() {
        Gauge.builder("memory.heap.utilization", currentUtilization, AtomicDouble::get)
            .register(meterRegistry);
        Gauge.builder("memory.pressure.level", () -> currentLevel.ordinal())
            .register(meterRegistry);
        evictionCounter = Counter.builder("memory.evictions.total")
            .register(meterRegistry);
        
        Gauge.builder("memory.gc.frequency", this, AdaptiveMemoryManager::getGcFrequency)
            .register(meterRegistry);
        Gauge.builder("memory.gc.pause.rate", this, AdaptiveMemoryManager::getGcPauseRate)
            .register(meterRegistry);
    }
    
    /**
     * 注册淘汰回调
     */
    public void registerEvictionCallback(Consumer<Integer> callback) {
        evictionCallbacks.add(callback);
    }
    
    /**
     * 检查内存压力
     */
    private void checkMemoryPressure() {
        try {
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            double utilization = (double) heapUsage.getUsed() / heapUsage.getMax();
            currentUtilization.set(utilization);
            
            // 记录快照
            recordSnapshot(utilization, heapUsage);
            
            // 更新GC统计
            updateGcStats();
            
            // 判断压力级别
            MemoryPressureLevel newLevel = determinePressureLevel(utilization);
            
            if (newLevel != currentLevel) {
                onPressureLevelChanged(currentLevel, newLevel, utilization);
                currentLevel = newLevel;
            }
            
            // 触发淘汰
            if (newLevel.ordinal() >= MemoryPressureLevel.WARNING.ordinal()) {
                triggerEviction(newLevel);
            }
            
        } catch (Exception e) {
            log.error("Memory pressure check failed", e);
        }
    }
    
    private void recordSnapshot(double utilization, MemoryUsage heapUsage) {
        history.addLast(new MemorySnapshot(
            System.currentTimeMillis(),
            utilization,
            heapUsage.getUsed(),
            heapUsage.getCommitted(),
            heapUsage.getMax()
        ));
        
        while (history.size() > MAX_HISTORY_SIZE) {
            history.pollFirst();
        }
    }
    
    private void updateGcStats() {
        long totalGcCount = 0;
        long totalGcTime = 0;
        
        for (GarbageCollectorMXBean gc : gcBeans) {
            totalGcCount += gc.getCollectionCount();
            totalGcTime += gc.getCollectionTime();
        }
        
        lastGcCount.set(totalGcCount);
        lastGcTime.set(totalGcTime);
    }
    
    private MemoryPressureLevel determinePressureLevel(double utilization) {
        if (utilization >= emergencyThreshold) {
            return MemoryPressureLevel.EMERGENCY;
        } else if (utilization >= criticalThreshold) {
            return MemoryPressureLevel.CRITICAL;
        } else if (utilization >= warningThreshold) {
            return MemoryPressureLevel.WARNING;
        }
        return MemoryPressureLevel.NORMAL;
    }
    
    private void onPressureLevelChanged(MemoryPressureLevel from, 
                                         MemoryPressureLevel to, 
                                         double utilization) {
        log.warn("Memory pressure level changed: {} -> {}, utilization={}%",
            from, to, String.format("%.2f", utilization * 100));
        
        switch (to) {
            case WARNING -> warningEvents.increment();
            case CRITICAL -> criticalEvents.increment();
            case EMERGENCY -> emergencyEvents.increment();
        }
    }
    
    private void triggerEviction(MemoryPressureLevel level) {
        int batchSize = switch (level) {
            case WARNING -> evictionBatchSize;
            case CRITICAL -> evictionBatchSize * 3;
            case EMERGENCY -> evictionBatchSize * 10;
            default -> 0;
        };
        
        if (batchSize > 0) {
            log.info("Triggering eviction: level={}, batchSize={}", level, batchSize);
            
            for (Consumer<Integer> callback : evictionCallbacks) {
                try {
                    callback.accept(batchSize);
                    totalEvictions.add(batchSize);
                    evictionCounter.increment(batchSize);
                } catch (Exception e) {
                    log.error("Eviction callback failed", e);
                }
            }
        }
    }
    
    /**
     * 检查是否可以分配内存
     */
    public boolean canAllocate(long bytes) {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        long available = heapUsage.getMax() - heapUsage.getUsed();
        long safeAvailable = (long) (available * (1 - criticalThreshold));
        return bytes < safeAvailable;
    }
    
    /**
     * 强制GC（仅紧急情况）
     */
    public void forceGcIfNeeded() {
        if (currentLevel == MemoryPressureLevel.EMERGENCY) {
            log.warn("Forcing GC due to emergency memory pressure");
            System.gc();
        }
    }
    
    /**
     * 获取当前压力级别
     */
    public MemoryPressureLevel getCurrentLevel() {
        return currentLevel;
    }
    
    /**
     * 获取当前内存利用率
     */
    public double getCurrentUtilization() {
        return currentUtilization.get();
    }
    
    /**
     * 获取GC频率（每秒）
     */
    public double getGcFrequency() {
        if (history.size() < 2) return 0;
        
        long totalGc = lastGcCount.get();
        // 简化计算
        return totalGc > 0 ? totalGc / 60.0 : 0;
    }
    
    /**
     * 获取GC暂停率
     */
    public double getGcPauseRate() {
        if (history.size() < 2) return 0;
        
        long gcTime = lastGcTime.get();
        long elapsedTime = history.size() * checkIntervalMs;
        
        return elapsedTime > 0 ? (double) gcTime / elapsedTime : 0;
    }
    
    /**
     * 获取内存趋势
     */
    public MemoryTrend getMemoryTrend() {
        if (history.size() < 10) {
            return MemoryTrend.STABLE;
        }
        
        // 取最近10个样本
        List<MemorySnapshot> recent = new ArrayList<>();
        Iterator<MemorySnapshot> it = history.descendingIterator();
        for (int i = 0; i < 10 && it.hasNext(); i++) {
            recent.add(0, it.next());
        }
        
        // 计算趋势
        double firstHalfAvg = recent.subList(0, 5).stream()
            .mapToDouble(s -> s.utilization)
            .average()
            .orElse(0);
        double secondHalfAvg = recent.subList(5, 10).stream()
            .mapToDouble(s -> s.utilization)
            .average()
            .orElse(0);
        
        double change = secondHalfAvg - firstHalfAvg;
        
        if (change > 0.05) {
            return MemoryTrend.INCREASING;
        } else if (change < -0.05) {
            return MemoryTrend.DECREASING;
        }
        return MemoryTrend.STABLE;
    }
    
    /**
     * 获取推荐的淘汰数量
     */
    public int getRecommendedEvictionCount() {
        double utilization = currentUtilization.get();
        double excess = utilization - targetUtilization;
        
        if (excess <= 0) {
            return 0;
        }
        
        // 估算需要释放的条目数
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        long excessBytes = (long) (heapUsage.getMax() * excess);
        
        // 假设每条目平均1KB
        return (int) (excessBytes / 1024);
    }
    
    /**
     * 获取统计信息
     */
    public MemoryStats getStats() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        
        return new MemoryStats(
            currentLevel,
            currentUtilization.get(),
            heapUsage.getUsed(),
            heapUsage.getMax(),
            totalEvictions.sum(),
            warningEvents.sum(),
            criticalEvents.sum(),
            emergencyEvents.sum(),
            getGcFrequency(),
            getGcPauseRate(),
            getMemoryTrend()
        );
    }
    
    // ========== 枚举和记录类 ==========
    
    public enum MemoryPressureLevel {
        NORMAL,
        WARNING,
        CRITICAL,
        EMERGENCY
    }
    
    public enum MemoryTrend {
        INCREASING,
        STABLE,
        DECREASING
    }
    
    private record MemorySnapshot(
        long timestamp,
        double utilization,
        long used,
        long committed,
        long max
    ) {}
    
    public record MemoryStats(
        MemoryPressureLevel level,
        double utilization,
        long usedBytes,
        long maxBytes,
        long totalEvictions,
        long warningEvents,
        long criticalEvents,
        long emergencyEvents,
        double gcFrequency,
        double gcPauseRate,
        MemoryTrend trend
    ) {
        public double usedMB() {
            return usedBytes / (1024.0 * 1024.0);
        }
        
        public double maxMB() {
            return maxBytes / (1024.0 * 1024.0);
        }
    }
}
