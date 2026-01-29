package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.lang.management.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/**
 * 内存压力感知淘汰策略 - GC友好的智能缓存淘汰
 * 
 * 核心特性:
 * 1. 内存压力监控 - 实时感知JVM内存状态
 * 2. 自适应淘汰 - 根据压力等级动态调整淘汰速率
 * 3. 代际感知 - 区分新生代和老年代压力
 * 4. GC频率监控 - 预测性淘汰避免Full GC
 * 5. 软引用降级 - 压力大时自动降级
 * 6. 分层淘汰 - LRU + LFU + 大小感知
 * 
 * 目标: 避免OOM, 减少Full GC频率50%+, GC停顿时间减少30%+
 */
@Service
public class MemoryPressureEvictionPolicy {
    
    private static final Logger log = LoggerFactory.getLogger(MemoryPressureEvictionPolicy.class);
    
    // ========== 配置参数 ==========
    @Value("${optimization.eviction.enabled:true}")
    private boolean evictionEnabled;
    
    @Value("${optimization.eviction.high-pressure-threshold:0.85}")
    private double highPressureThreshold;
    
    @Value("${optimization.eviction.critical-pressure-threshold:0.95}")
    private double criticalPressureThreshold;
    
    @Value("${optimization.eviction.target-utilization:0.70}")
    private double targetUtilization;
    
    @Value("${optimization.eviction.soft-reference-threshold:0.80}")
    private double softReferenceThreshold;
    
    @Value("${optimization.eviction.check-interval-ms:1000}")
    private long checkIntervalMs;
    
    @Value("${optimization.eviction.batch-size:100}")
    private int evictionBatchSize;
    
    // ========== JVM监控 ==========
    private final MemoryMXBean memoryMXBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final List<MemoryPoolMXBean> memoryPoolBeans;
    
    // ========== 数据结构 ==========
    
    // 缓存条目元数据
    private final ConcurrentHashMap<String, CacheEntryMeta> entryMetadata = new ConcurrentHashMap<>();
    
    // LRU访问顺序
    private final ConcurrentLinkedDeque<String> lruQueue = new ConcurrentLinkedDeque<>();
    
    // LFU频率计数
    private final ConcurrentHashMap<String, LongAdder> lfuCounters = new ConcurrentHashMap<>();
    
    // 软引用缓存（压力大时自动清理）
    private final ConcurrentHashMap<String, SoftReference<byte[]>> softReferenceCache = new ConcurrentHashMap<>();
    
    // GC统计
    private final AtomicLong lastGcCount = new AtomicLong(0);
    private final AtomicLong lastGcTime = new AtomicLong(0);
    private volatile long lastYoungGcCount = 0;
    private volatile long lastOldGcCount = 0;
    
    // 压力等级
    private volatile PressureLevel currentPressureLevel = PressureLevel.LOW;
    
    // 淘汰回调
    private volatile Function<String, Boolean> evictionCallback;
    
    // 执行器
    private final ScheduledExecutorService monitorExecutor;
    private final ExecutorService evictionExecutor;
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Counter evictionCounter;
    private Counter pressureEvictionCounter;
    private Counter softRefEvictionCounter;
    
    // 统计
    private final LongAdder totalEvictions = new LongAdder();
    private final LongAdder pressureEvictions = new LongAdder();
    private final AtomicLong lastEvictionTime = new AtomicLong(0);
    
    public MemoryPressureEvictionPolicy(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.memoryPoolBeans = ManagementFactory.getMemoryPoolMXBeans();
        
        this.monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-pressure-monitor");
            t.setDaemon(true);
            return t;
        });
        this.evictionExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cache-eviction");
            t.setDaemon(true);
            return t;
        });
    }
    
    @PostConstruct
    public void init() {
        evictionCounter = Counter.builder("cache.eviction.total").register(meterRegistry);
        pressureEvictionCounter = Counter.builder("cache.eviction.pressure").register(meterRegistry);
        softRefEvictionCounter = Counter.builder("cache.eviction.softref").register(meterRegistry);
        
        Gauge.builder("cache.memory.pressure.level", () -> currentPressureLevel.ordinal())
            .register(meterRegistry);
        Gauge.builder("cache.memory.heap.usage", this, MemoryPressureEvictionPolicy::getHeapUsageRatio)
            .register(meterRegistry);
        Gauge.builder("cache.entries.tracked", entryMetadata, ConcurrentHashMap::size)
            .register(meterRegistry);
        
        // 启动内存压力监控
        startPressureMonitor();
        
        // 注册GC通知
        registerGcNotifications();
        
        log.info("MemoryPressureEvictionPolicy initialized: highThreshold={}, criticalThreshold={}",
            highPressureThreshold, criticalPressureThreshold);
    }
    
    /**
     * 注册缓存条目
     */
    public void registerEntry(String key, int sizeBytes) {
        CacheEntryMeta meta = new CacheEntryMeta(
            key, sizeBytes, System.currentTimeMillis(), System.currentTimeMillis()
        );
        entryMetadata.put(key, meta);
        lruQueue.addLast(key);
        lfuCounters.computeIfAbsent(key, k -> new LongAdder());
        
        // 检查是否需要立即淘汰
        if (currentPressureLevel == PressureLevel.CRITICAL) {
            triggerEmergencyEviction();
        }
    }
    
    /**
     * 记录访问
     */
    public void recordAccess(String key) {
        CacheEntryMeta meta = entryMetadata.get(key);
        if (meta != null) {
            meta.lastAccessTime = System.currentTimeMillis();
            meta.accessCount.increment();
        }
        
        LongAdder counter = lfuCounters.get(key);
        if (counter != null) {
            counter.increment();
        }
        
        // 更新LRU顺序
        lruQueue.remove(key);
        lruQueue.addLast(key);
    }
    
    /**
     * 移除条目
     */
    public void removeEntry(String key) {
        entryMetadata.remove(key);
        lruQueue.remove(key);
        lfuCounters.remove(key);
        softReferenceCache.remove(key);
    }
    
    /**
     * 设置淘汰回调
     */
    public void setEvictionCallback(Function<String, Boolean> callback) {
        this.evictionCallback = callback;
    }
    
    /**
     * 启动压力监控
     */
    private void startPressureMonitor() {
        monitorExecutor.scheduleAtFixedRate(() -> {
            try {
                updatePressureLevel();
                handlePressure();
            } catch (Exception e) {
                log.error("Pressure monitor error", e);
            }
        }, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 更新压力等级
     */
    private void updatePressureLevel() {
        double heapUsage = getHeapUsageRatio();
        double oldGenUsage = getOldGenUsageRatio();
        
        // 综合判断压力等级
        double effectiveUsage = Math.max(heapUsage, oldGenUsage);
        
        // 考虑GC频率
        double gcPressure = calculateGcPressure();
        effectiveUsage = Math.max(effectiveUsage, gcPressure);
        
        PressureLevel previousLevel = currentPressureLevel;
        
        if (effectiveUsage >= criticalPressureThreshold) {
            currentPressureLevel = PressureLevel.CRITICAL;
        } else if (effectiveUsage >= highPressureThreshold) {
            currentPressureLevel = PressureLevel.HIGH;
        } else if (effectiveUsage >= targetUtilization) {
            currentPressureLevel = PressureLevel.MEDIUM;
        } else {
            currentPressureLevel = PressureLevel.LOW;
        }
        
        if (currentPressureLevel != previousLevel) {
            log.info("Memory pressure level changed: {} -> {} (heap={:.2f}%, oldGen={:.2f}%, gc={:.2f}%)",
                previousLevel, currentPressureLevel, heapUsage * 100, oldGenUsage * 100, gcPressure * 100);
        }
    }
    
    /**
     * 处理内存压力
     */
    private void handlePressure() {
        switch (currentPressureLevel) {
            case CRITICAL:
                triggerEmergencyEviction();
                break;
            case HIGH:
                triggerAggressiveEviction();
                break;
            case MEDIUM:
                triggerNormalEviction();
                break;
            case LOW:
                // 低压力，可以进行软引用恢复
                recoverSoftReferences();
                break;
        }
    }
    
    /**
     * 紧急淘汰 - 快速释放内存
     */
    private void triggerEmergencyEviction() {
        if (!evictionEnabled) return;
        
        log.warn("Emergency eviction triggered!");
        
        evictionExecutor.submit(() -> {
            int evicted = 0;
            int targetEvict = entryMetadata.size() / 4; // 淘汰25%
            
            // 1. 先清理软引用
            evicted += clearSoftReferences();
            
            // 2. 按大小淘汰（优先淘汰大对象）
            evicted += evictBySize(targetEvict - evicted);
            
            // 3. LRU淘汰
            evicted += evictByLru(targetEvict - evicted);
            
            pressureEvictions.add(evicted);
            pressureEvictionCounter.increment();
            
            // 建议GC
            System.gc();
            
            log.info("Emergency eviction completed: evicted {} entries", evicted);
        });
    }
    
    /**
     * 激进淘汰
     */
    private void triggerAggressiveEviction() {
        if (!evictionEnabled) return;
        
        evictionExecutor.submit(() -> {
            int evicted = 0;
            int targetEvict = evictionBatchSize * 2;
            
            // 降级部分条目到软引用
            demoteToSoftReference(targetEvict / 2);
            
            // LFU淘汰低频条目
            evicted += evictByLfu(targetEvict);
            
            totalEvictions.add(evicted);
            evictionCounter.increment();
        });
    }
    
    /**
     * 正常淘汰
     */
    private void triggerNormalEviction() {
        if (!evictionEnabled) return;
        
        // 检查是否需要淘汰
        double usage = getHeapUsageRatio();
        if (usage < targetUtilization) return;
        
        evictionExecutor.submit(() -> {
            // 组合策略：LRU + LFU
            int evicted = evictByCombinedStrategy(evictionBatchSize);
            totalEvictions.add(evicted);
        });
    }
    
    /**
     * 按大小淘汰
     */
    private int evictBySize(int count) {
        if (count <= 0) return 0;
        
        List<Map.Entry<String, CacheEntryMeta>> sorted = new ArrayList<>(entryMetadata.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().sizeBytes, a.getValue().sizeBytes));
        
        int evicted = 0;
        for (Map.Entry<String, CacheEntryMeta> entry : sorted) {
            if (evicted >= count) break;
            if (doEvict(entry.getKey())) {
                evicted++;
            }
        }
        return evicted;
    }
    
    /**
     * LRU淘汰
     */
    private int evictByLru(int count) {
        if (count <= 0) return 0;
        
        int evicted = 0;
        while (evicted < count && !lruQueue.isEmpty()) {
            String key = lruQueue.pollFirst();
            if (key != null && doEvict(key)) {
                evicted++;
            }
        }
        return evicted;
    }
    
    /**
     * LFU淘汰
     */
    private int evictByLfu(int count) {
        if (count <= 0) return 0;
        
        List<Map.Entry<String, LongAdder>> sorted = new ArrayList<>(lfuCounters.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().sum()));
        
        int evicted = 0;
        for (Map.Entry<String, LongAdder> entry : sorted) {
            if (evicted >= count) break;
            if (doEvict(entry.getKey())) {
                evicted++;
            }
        }
        return evicted;
    }
    
    /**
     * 组合策略淘汰
     */
    private int evictByCombinedStrategy(int count) {
        if (count <= 0) return 0;
        
        // 计算每个条目的淘汰分数
        List<ScoredEntry> scoredEntries = new ArrayList<>();
        
        long now = System.currentTimeMillis();
        long maxAccessTime = entryMetadata.values().stream()
            .mapToLong(m -> m.lastAccessTime).max().orElse(now);
        long maxAccessCount = lfuCounters.values().stream()
            .mapToLong(LongAdder::sum).max().orElse(1);
        
        entryMetadata.forEach((key, meta) -> {
            double recencyScore = 1.0 - ((double)(meta.lastAccessTime - now + maxAccessTime) / maxAccessTime);
            LongAdder lfuCounter = lfuCounters.get(key);
            double frequencyScore = lfuCounter != null ? 
                1.0 - ((double) lfuCounter.sum() / maxAccessCount) : 1.0;
            double sizeScore = (double) meta.sizeBytes / (1024 * 1024); // MB
            
            // 综合分数：越高越应该淘汰
            double score = recencyScore * 0.4 + frequencyScore * 0.4 + sizeScore * 0.2;
            scoredEntries.add(new ScoredEntry(key, score));
        });
        
        // 按分数排序（高分优先淘汰）
        scoredEntries.sort((a, b) -> Double.compare(b.score, a.score));
        
        int evicted = 0;
        for (ScoredEntry entry : scoredEntries) {
            if (evicted >= count) break;
            if (doEvict(entry.key)) {
                evicted++;
            }
        }
        return evicted;
    }
    
    /**
     * 执行淘汰
     */
    private boolean doEvict(String key) {
        if (evictionCallback != null) {
            try {
                if (evictionCallback.apply(key)) {
                    removeEntry(key);
                    return true;
                }
            } catch (Exception e) {
                log.warn("Eviction callback failed for key: {}", key, e);
            }
        } else {
            removeEntry(key);
            return true;
        }
        return false;
    }
    
    /**
     * 清理软引用
     */
    private int clearSoftReferences() {
        int cleared = 0;
        Iterator<Map.Entry<String, SoftReference<byte[]>>> it = softReferenceCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SoftReference<byte[]>> entry = it.next();
            if (entry.getValue().get() == null) {
                it.remove();
                cleared++;
            }
        }
        softRefEvictionCounter.increment();
        return cleared;
    }
    
    /**
     * 降级到软引用
     */
    private void demoteToSoftReference(int count) {
        // 选择低优先级条目降级
        List<String> candidates = new ArrayList<>();
        
        entryMetadata.forEach((key, meta) -> {
            if (!softReferenceCache.containsKey(key) && meta.accessCount.sum() < 10) {
                candidates.add(key);
            }
        });
        
        Collections.shuffle(candidates);
        int demoted = Math.min(count, candidates.size());
        
        for (int i = 0; i < demoted; i++) {
            String key = candidates.get(i);
            CacheEntryMeta meta = entryMetadata.get(key);
            if (meta != null) {
                // 创建软引用占位符
                softReferenceCache.put(key, new SoftReference<>(new byte[meta.sizeBytes]));
            }
        }
        
        log.debug("Demoted {} entries to soft references", demoted);
    }
    
    /**
     * 恢复软引用
     */
    private void recoverSoftReferences() {
        // 低压力时可以将软引用升级回强引用
        // 实际实现中需要重新从缓存源加载数据
    }
    
    /**
     * 注册GC通知
     */
    private void registerGcNotifications() {
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            ((javax.management.NotificationEmitter) gcBean).addNotificationListener(
                (notification, handback) -> {
                    if (notification.getType().equals(
                            com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
                        handleGcNotification(notification);
                    }
                }, null, null);
        }
    }
    
    /**
     * 处理GC通知
     */
    private void handleGcNotification(javax.management.Notification notification) {
        com.sun.management.GarbageCollectionNotificationInfo info = 
            com.sun.management.GarbageCollectionNotificationInfo.from(
                (javax.management.openmbean.CompositeData) notification.getUserData());
        
        String gcAction = info.getGcAction();
        long duration = info.getGcInfo().getDuration();
        
        if (gcAction.contains("major") || gcAction.contains("Old")) {
            lastOldGcCount++;
            if (duration > 200) { // Full GC超过200ms
                log.warn("Long Full GC detected: {}ms", duration);
                // 触发预防性淘汰
                if (currentPressureLevel.ordinal() >= PressureLevel.MEDIUM.ordinal()) {
                    triggerAggressiveEviction();
                }
            }
        } else {
            lastYoungGcCount++;
        }
        
        lastGcCount.incrementAndGet();
        lastGcTime.set(System.currentTimeMillis());
    }
    
    /**
     * 获取堆内存使用率
     */
    public double getHeapUsageRatio() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        return (double) heapUsage.getUsed() / heapUsage.getMax();
    }
    
    /**
     * 获取老年代使用率
     */
    private double getOldGenUsageRatio() {
        for (MemoryPoolMXBean pool : memoryPoolBeans) {
            if (pool.getName().toLowerCase().contains("old") || 
                pool.getName().toLowerCase().contains("tenured")) {
                MemoryUsage usage = pool.getUsage();
                if (usage.getMax() > 0) {
                    return (double) usage.getUsed() / usage.getMax();
                }
            }
        }
        return getHeapUsageRatio();
    }
    
    /**
     * 计算GC压力
     */
    private double calculateGcPressure() {
        long totalGcTime = 0;
        long totalGcCount = 0;
        
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            totalGcTime += gcBean.getCollectionTime();
            totalGcCount += gcBean.getCollectionCount();
        }
        
        // 如果GC占用时间超过5%，认为有压力
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        if (uptime > 0) {
            return Math.min(1.0, (double) totalGcTime / uptime * 20); // 放大GC时间影响
        }
        return 0;
    }
    
    /**
     * 获取淘汰策略统计
     */
    public EvictionStats getStats() {
        return new EvictionStats(
            evictionEnabled,
            currentPressureLevel,
            getHeapUsageRatio(),
            getOldGenUsageRatio(),
            calculateGcPressure(),
            entryMetadata.size(),
            softReferenceCache.size(),
            totalEvictions.sum(),
            pressureEvictions.sum(),
            lastEvictionTime.get()
        );
    }
    
    // ========== 内部类 ==========
    
    public enum PressureLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }
    
    private static class CacheEntryMeta {
        final String key;
        final int sizeBytes;
        final long createTime;
        volatile long lastAccessTime;
        final LongAdder accessCount = new LongAdder();
        
        CacheEntryMeta(String key, int sizeBytes, long createTime, long lastAccessTime) {
            this.key = key;
            this.sizeBytes = sizeBytes;
            this.createTime = createTime;
            this.lastAccessTime = lastAccessTime;
        }
    }
    
    private record ScoredEntry(String key, double score) {}
    
    public record EvictionStats(
        boolean enabled,
        PressureLevel pressureLevel,
        double heapUsage,
        double oldGenUsage,
        double gcPressure,
        int trackedEntries,
        int softRefEntries,
        long totalEvictions,
        long pressureEvictions,
        long lastEvictionTime
    ) {}
}
