package com.ecommerce.cache.optimization.v9;

import io.micrometer.core.instrument.Counter;
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
 * 向量时钟版本控制系统
 * 
 * 核心特性:
 * 1. 因果一致性: 精确追踪事件因果关系
 * 2. 并发检测: 检测并发写入冲突
 * 3. 版本比较: 支持happens-before关系判断
 * 4. 快照管理: 定期快照与恢复
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorClockManager {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV9Properties properties;
    
    /** 本地节点ID */
    private String localNodeId;
    
    /** 向量时钟存储 */
    private final ConcurrentMap<String, VectorClock> clockStore = new ConcurrentHashMap<>();
    
    /** 版本数据存储 */
    private final ConcurrentMap<String, VersionedValue<?>> versionedData = new ConcurrentHashMap<>();
    
    /** 快照存储 */
    private final Queue<ClockSnapshot> snapshots = new ConcurrentLinkedQueue<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong tickCount = new AtomicLong(0);
    private final AtomicLong mergeCount = new AtomicLong(0);
    private final AtomicLong conflictCount = new AtomicLong(0);
    
    private Counter tickCounter;
    private Counter mergeCounter;
    private Counter conflictCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getVectorClock().isEnabled()) {
            log.info("[向量时钟] 已禁用");
            return;
        }
        
        localNodeId = "node-" + UUID.randomUUID().toString().substring(0, 8);
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vclock-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 定期快照
        int interval = properties.getVectorClock().getSnapshotIntervalSec();
        scheduler.scheduleWithFixedDelay(this::takeSnapshot, interval, interval, TimeUnit.SECONDS);
        
        // 定期修剪
        scheduler.scheduleWithFixedDelay(this::pruneClocks, 60, 60, TimeUnit.SECONDS);
        
        log.info("[向量时钟] 初始化完成 - 节点: {}", localNodeId);
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        log.info("[向量时钟] 已关闭 - tick: {}, merge: {}, conflict: {}",
            tickCount.get(), mergeCount.get(), conflictCount.get());
    }
    
    private void initMetrics() {
        tickCounter = Counter.builder("cache.vclock.tick").register(meterRegistry);
        mergeCounter = Counter.builder("cache.vclock.merge").register(meterRegistry);
        conflictCounter = Counter.builder("cache.vclock.conflict").register(meterRegistry);
    }
    
    // ========== 核心API ==========
    
    /**
     * 获取或创建向量时钟
     */
    public VectorClock getOrCreate(String key) {
        return clockStore.computeIfAbsent(key, k -> new VectorClock());
    }
    
    /**
     * 本地事件发生(tick)
     */
    public VectorClock tick(String key) {
        VectorClock clock = getOrCreate(key);
        clock.increment(localNodeId);
        tickCount.incrementAndGet();
        tickCounter.increment();
        return clock;
    }
    
    /**
     * 发送事件(返回当前时钟副本)
     */
    public VectorClock send(String key) {
        VectorClock clock = tick(key);
        return clock.copy();
    }
    
    /**
     * 接收事件(合并远程时钟)
     */
    public VectorClock receive(String key, VectorClock remote) {
        VectorClock local = getOrCreate(key);
        local.merge(remote);
        local.increment(localNodeId);
        mergeCount.incrementAndGet();
        mergeCounter.increment();
        return local;
    }
    
    /**
     * 比较两个向量时钟
     */
    public CompareResult compare(String key1, String key2) {
        VectorClock c1 = clockStore.get(key1);
        VectorClock c2 = clockStore.get(key2);
        
        if (c1 == null || c2 == null) {
            return CompareResult.INCOMPARABLE;
        }
        
        return c1.compare(c2);
    }
    
    /**
     * 检测并发冲突
     */
    public boolean detectConflict(String key, VectorClock remote) {
        VectorClock local = clockStore.get(key);
        if (local == null) return false;
        
        CompareResult result = local.compare(remote);
        boolean conflict = result == CompareResult.CONCURRENT;
        
        if (conflict) {
            conflictCount.incrementAndGet();
            conflictCounter.increment();
        }
        
        return conflict;
    }
    
    /**
     * 设置版本化数据
     */
    public <T> void setVersioned(String key, T value) {
        VectorClock clock = tick(key);
        versionedData.put(key, new VersionedValue<>(value, clock.copy(), System.currentTimeMillis()));
    }
    
    /**
     * 获取版本化数据
     */
    @SuppressWarnings("unchecked")
    public <T> VersionedValue<T> getVersioned(String key) {
        return (VersionedValue<T>) versionedData.get(key);
    }
    
    /**
     * 条件更新(仅当本地版本更旧时更新)
     */
    public <T> boolean compareAndSet(String key, VectorClock expectedClock, T newValue) {
        VectorClock local = clockStore.get(key);
        
        if (local == null || local.compare(expectedClock) == CompareResult.BEFORE) {
            VectorClock newClock = tick(key);
            versionedData.put(key, new VersionedValue<>(newValue, newClock.copy(), System.currentTimeMillis()));
            return true;
        }
        
        return false;
    }
    
    /**
     * 获取因果历史
     */
    public List<String> getCausalHistory(String key) {
        List<String> history = new ArrayList<>();
        VectorClock clock = clockStore.get(key);
        
        if (clock != null) {
            for (var entry : clock.getClock().entrySet()) {
                history.add(entry.getKey() + ":" + entry.getValue());
            }
        }
        
        return history;
    }
    
    // ========== 内部方法 ==========
    
    private void takeSnapshot() {
        Map<String, VectorClock> snapshot = new HashMap<>();
        for (var entry : clockStore.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().copy());
        }
        
        snapshots.offer(new ClockSnapshot(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            snapshot
        ));
        
        // 限制快照数量
        while (snapshots.size() > 10) {
            snapshots.poll();
        }
    }
    
    private void pruneClocks() {
        int threshold = properties.getVectorClock().getPruneThreshold();
        int maxNodes = properties.getVectorClock().getMaxNodes();
        
        for (VectorClock clock : clockStore.values()) {
            if (clock.size() > maxNodes) {
                clock.prune(threshold);
            }
        }
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("localNodeId", localNodeId);
        stats.put("clockCount", clockStore.size());
        stats.put("versionedDataCount", versionedData.size());
        stats.put("snapshotCount", snapshots.size());
        stats.put("tickCount", tickCount.get());
        stats.put("mergeCount", mergeCount.get());
        stats.put("conflictCount", conflictCount.get());
        return stats;
    }
    
    // ========== 内部类 ==========
    
    public enum CompareResult {
        BEFORE,      // 发生在之前
        AFTER,       // 发生在之后
        CONCURRENT,  // 并发(冲突)
        EQUAL,       // 相等
        INCOMPARABLE // 无法比较
    }
    
    @Data
    public static class VectorClock {
        private final Map<String, Long> clock = new ConcurrentHashMap<>();
        
        public void increment(String nodeId) {
            clock.merge(nodeId, 1L, Long::sum);
        }
        
        public void merge(VectorClock other) {
            for (var entry : other.clock.entrySet()) {
                clock.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        
        public VectorClock copy() {
            VectorClock copy = new VectorClock();
            copy.clock.putAll(this.clock);
            return copy;
        }
        
        public CompareResult compare(VectorClock other) {
            boolean thisLessOrEqual = true;
            boolean otherLessOrEqual = true;
            
            Set<String> allKeys = new HashSet<>(clock.keySet());
            allKeys.addAll(other.clock.keySet());
            
            for (String key : allKeys) {
                long thisVal = clock.getOrDefault(key, 0L);
                long otherVal = other.clock.getOrDefault(key, 0L);
                
                if (thisVal > otherVal) otherLessOrEqual = false;
                if (otherVal > thisVal) thisLessOrEqual = false;
            }
            
            if (thisLessOrEqual && otherLessOrEqual) return CompareResult.EQUAL;
            if (thisLessOrEqual) return CompareResult.BEFORE;
            if (otherLessOrEqual) return CompareResult.AFTER;
            return CompareResult.CONCURRENT;
        }
        
        public int size() {
            return clock.size();
        }
        
        public void prune(int keepTop) {
            if (clock.size() <= keepTop) return;
            
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(clock.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            
            clock.clear();
            for (int i = 0; i < Math.min(keepTop, sorted.size()); i++) {
                clock.put(sorted.get(i).getKey(), sorted.get(i).getValue());
            }
        }
    }
    
    @Data
    public static class VersionedValue<T> {
        private final T value;
        private final VectorClock clock;
        private final long timestamp;
    }
    
    @Data
    private static class ClockSnapshot {
        private final String id;
        private final long timestamp;
        private final Map<String, VectorClock> clocks;
    }
}
