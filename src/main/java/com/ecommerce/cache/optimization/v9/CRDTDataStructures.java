package com.ecommerce.cache.optimization.v9;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CRDT (无冲突复制数据类型) 实现
 * 
 * 支持的CRDT类型:
 * 1. G-Counter: 只增计数器
 * 2. PN-Counter: 加减计数器
 * 3. G-Set: 只增集合
 * 4. OR-Set: 观察-移除集合
 * 5. LWW-Register: 最后写入获胜寄存器
 * 6. MV-Register: 多值寄存器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CRDTDataStructures {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV9Properties properties;
    
    /** CRDT存储 */
    private final ConcurrentMap<String, CRDT<?>> crdtStore = new ConcurrentHashMap<>();
    
    /** 节点ID */
    private String nodeId;
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong mergeCount = new AtomicLong(0);
    private final AtomicLong operationCount = new AtomicLong(0);
    
    private Counter mergeCounter;
    private Counter opCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getCrdt().isEnabled()) {
            log.info("[CRDT] 已禁用");
            return;
        }
        
        nodeId = UUID.randomUUID().toString().substring(0, 8);
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "crdt-gc");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 定期GC
        int gcInterval = properties.getCrdt().getGcIntervalSec();
        scheduler.scheduleWithFixedDelay(this::garbageCollect, gcInterval, gcInterval, TimeUnit.SECONDS);
        
        log.info("[CRDT] 初始化完成 - 节点: {}", nodeId);
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        log.info("[CRDT] 已关闭 - 合并: {}, 操作: {}", mergeCount.get(), operationCount.get());
    }
    
    private void initMetrics() {
        mergeCounter = Counter.builder("cache.crdt.merge").register(meterRegistry);
        opCounter = Counter.builder("cache.crdt.operation").register(meterRegistry);
    }
    
    // ========== G-Counter ==========
    
    public GCounter getOrCreateGCounter(String key) {
        return (GCounter) crdtStore.computeIfAbsent(key, k -> new GCounter(nodeId));
    }
    
    public void incrementGCounter(String key, long delta) {
        GCounter counter = getOrCreateGCounter(key);
        counter.increment(nodeId, delta);
        operationCount.incrementAndGet();
        opCounter.increment();
    }
    
    public long getGCounterValue(String key) {
        GCounter counter = (GCounter) crdtStore.get(key);
        return counter != null ? counter.value() : 0;
    }
    
    // ========== PN-Counter ==========
    
    public PNCounter getOrCreatePNCounter(String key) {
        return (PNCounter) crdtStore.computeIfAbsent(key, k -> new PNCounter(nodeId));
    }
    
    public void incrementPNCounter(String key, long delta) {
        PNCounter counter = getOrCreatePNCounter(key);
        counter.increment(nodeId, delta);
        operationCount.incrementAndGet();
    }
    
    public void decrementPNCounter(String key, long delta) {
        PNCounter counter = getOrCreatePNCounter(key);
        counter.decrement(nodeId, delta);
        operationCount.incrementAndGet();
    }
    
    public long getPNCounterValue(String key) {
        PNCounter counter = (PNCounter) crdtStore.get(key);
        return counter != null ? counter.value() : 0;
    }
    
    // ========== G-Set ==========
    
    @SuppressWarnings("unchecked")
    public <T> GSet<T> getOrCreateGSet(String key) {
        return (GSet<T>) crdtStore.computeIfAbsent(key, k -> new GSet<T>());
    }
    
    public <T> void addToGSet(String key, T element) {
        GSet<T> set = getOrCreateGSet(key);
        set.add(element);
        operationCount.incrementAndGet();
    }
    
    public <T> Set<T> getGSetElements(String key) {
        GSet<T> set = (GSet<T>) crdtStore.get(key);
        return set != null ? set.value() : Collections.emptySet();
    }
    
    // ========== OR-Set ==========
    
    @SuppressWarnings("unchecked")
    public <T> ORSet<T> getOrCreateORSet(String key) {
        return (ORSet<T>) crdtStore.computeIfAbsent(key, k -> new ORSet<T>(nodeId));
    }
    
    public <T> void addToORSet(String key, T element) {
        ORSet<T> set = getOrCreateORSet(key);
        set.add(element);
        operationCount.incrementAndGet();
    }
    
    public <T> void removeFromORSet(String key, T element) {
        ORSet<T> set = getOrCreateORSet(key);
        set.remove(element);
        operationCount.incrementAndGet();
    }
    
    public <T> Set<T> getORSetElements(String key) {
        ORSet<T> set = (ORSet<T>) crdtStore.get(key);
        return set != null ? set.value() : Collections.emptySet();
    }
    
    // ========== LWW-Register ==========
    
    @SuppressWarnings("unchecked")
    public <T> LWWRegister<T> getOrCreateLWWRegister(String key) {
        return (LWWRegister<T>) crdtStore.computeIfAbsent(key, k -> new LWWRegister<T>());
    }
    
    public <T> void setLWWRegister(String key, T value) {
        LWWRegister<T> register = getOrCreateLWWRegister(key);
        register.set(value);
        operationCount.incrementAndGet();
    }
    
    public <T> T getLWWRegisterValue(String key) {
        LWWRegister<T> register = (LWWRegister<T>) crdtStore.get(key);
        return register != null ? register.value() : null;
    }
    
    // ========== 合并操作 ==========
    
    @SuppressWarnings("unchecked")
    public void merge(String key, CRDT<?> remote) {
        CRDT<?> local = crdtStore.get(key);
        if (local == null) {
            crdtStore.put(key, remote);
        } else {
            ((CRDT<Object>) local).merge((CRDT<Object>) remote);
        }
        mergeCount.incrementAndGet();
        mergeCounter.increment();
    }
    
    public byte[] serialize(String key) {
        CRDT<?> crdt = crdtStore.get(key);
        if (crdt == null) return null;
        
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(crdt);
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("[CRDT] 序列化失败: {}", key, e);
            return null;
        }
    }
    
    public CRDT<?> deserialize(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (CRDT<?>) ois.readObject();
        } catch (Exception e) {
            log.error("[CRDT] 反序列化失败", e);
            return null;
        }
    }
    
    private void garbageCollect() {
        int removed = 0;
        for (var entry : crdtStore.entrySet()) {
            if (entry.getValue() instanceof ORSet<?> orSet) {
                removed += orSet.gc(properties.getCrdt().getMaxTombstones());
            }
        }
        if (removed > 0) {
            log.debug("[CRDT] GC完成，清理墓碑: {}", removed);
        }
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodeId", nodeId);
        stats.put("crdtCount", crdtStore.size());
        stats.put("mergeCount", mergeCount.get());
        stats.put("operationCount", operationCount.get());
        
        Map<String, Integer> typeCount = new LinkedHashMap<>();
        for (CRDT<?> crdt : crdtStore.values()) {
            String type = crdt.getClass().getSimpleName();
            typeCount.merge(type, 1, Integer::sum);
        }
        stats.put("typeDistribution", typeCount);
        return stats;
    }
    
    // ========== CRDT接口 ==========
    
    public interface CRDT<T> extends Serializable {
        T value();
        void merge(CRDT<T> other);
    }
    
    // ========== G-Counter ==========
    
    @Data
    public static class GCounter implements CRDT<Long> {
        private final Map<String, Long> counts = new ConcurrentHashMap<>();
        
        GCounter(String nodeId) {
            counts.put(nodeId, 0L);
        }
        
        public void increment(String nodeId, long delta) {
            counts.merge(nodeId, delta, Long::sum);
        }
        
        @Override
        public Long value() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }
        
        @Override
        public void merge(CRDT<Long> other) {
            if (other instanceof GCounter gc) {
                for (var entry : gc.counts.entrySet()) {
                    counts.merge(entry.getKey(), entry.getValue(), Math::max);
                }
            }
        }
    }
    
    // ========== PN-Counter ==========
    
    @Data
    public static class PNCounter implements CRDT<Long> {
        private final GCounter positive;
        private final GCounter negative;
        
        PNCounter(String nodeId) {
            positive = new GCounter(nodeId);
            negative = new GCounter(nodeId);
        }
        
        public void increment(String nodeId, long delta) {
            positive.increment(nodeId, delta);
        }
        
        public void decrement(String nodeId, long delta) {
            negative.increment(nodeId, delta);
        }
        
        @Override
        public Long value() {
            return positive.value() - negative.value();
        }
        
        @Override
        public void merge(CRDT<Long> other) {
            if (other instanceof PNCounter pn) {
                positive.merge(pn.positive);
                negative.merge(pn.negative);
            }
        }
    }
    
    // ========== G-Set ==========
    
    public static class GSet<T> implements CRDT<Set<T>> {
        private final Set<T> elements = ConcurrentHashMap.newKeySet();
        
        public void add(T element) {
            elements.add(element);
        }
        
        public boolean contains(T element) {
            return elements.contains(element);
        }
        
        @Override
        public Set<T> value() {
            return new HashSet<>(elements);
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public void merge(CRDT<Set<T>> other) {
            if (other instanceof GSet<?> gs) {
                elements.addAll((Set<T>) gs.elements);
            }
        }
    }
    
    // ========== OR-Set ==========
    
    @Data
    public static class ORSet<T> implements CRDT<Set<T>> {
        private final Map<T, Set<String>> elements = new ConcurrentHashMap<>();
        private final Map<T, Set<String>> tombstones = new ConcurrentHashMap<>();
        private final String nodeId;
        private long counter = 0;
        
        ORSet(String nodeId) {
            this.nodeId = nodeId;
        }
        
        public synchronized void add(T element) {
            String tag = nodeId + ":" + (++counter);
            elements.computeIfAbsent(element, k -> ConcurrentHashMap.newKeySet()).add(tag);
        }
        
        public void remove(T element) {
            Set<String> tags = elements.get(element);
            if (tags != null) {
                tombstones.computeIfAbsent(element, k -> ConcurrentHashMap.newKeySet()).addAll(tags);
                elements.remove(element);
            }
        }
        
        @Override
        public Set<T> value() {
            Set<T> result = new HashSet<>();
            for (var entry : elements.entrySet()) {
                Set<String> tombs = tombstones.getOrDefault(entry.getKey(), Collections.emptySet());
                Set<String> live = new HashSet<>(entry.getValue());
                live.removeAll(tombs);
                if (!live.isEmpty()) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public void merge(CRDT<Set<T>> other) {
            if (other instanceof ORSet<?> or) {
                for (var entry : ((ORSet<T>) or).elements.entrySet()) {
                    elements.computeIfAbsent(entry.getKey(), k -> ConcurrentHashMap.newKeySet())
                        .addAll(entry.getValue());
                }
                for (var entry : ((ORSet<T>) or).tombstones.entrySet()) {
                    tombstones.computeIfAbsent(entry.getKey(), k -> ConcurrentHashMap.newKeySet())
                        .addAll(entry.getValue());
                }
            }
        }
        
        public int gc(int maxTombstones) {
            int removed = 0;
            if (tombstones.size() > maxTombstones) {
                int toRemove = tombstones.size() - maxTombstones;
                Iterator<T> it = tombstones.keySet().iterator();
                while (it.hasNext() && removed < toRemove) {
                    it.next();
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }
    }
    
    // ========== LWW-Register ==========
    
    @Data
    public static class LWWRegister<T> implements CRDT<T> {
        private T value;
        private long timestamp;
        
        public synchronized void set(T newValue) {
            this.value = newValue;
            this.timestamp = System.nanoTime();
        }
        
        @Override
        public T value() {
            return value;
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public void merge(CRDT<T> other) {
            if (other instanceof LWWRegister<?> lww) {
                if (lww.timestamp > this.timestamp) {
                    this.value = (T) lww.value;
                    this.timestamp = lww.timestamp;
                }
            }
        }
    }
}
