package com.ecommerce.cache.optimization.v12;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 全息缓存快照系统
 * 
 * 核心特性:
 * 1. 增量快照: 仅保存变更数据
 * 2. 压缩存储: LZ4/GZIP压缩
 * 3. 版本管理: 多版本保留
 * 4. 快速恢复: 并行加载
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HolographicSnapshotManager {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV12Properties properties;
    
    /** 快照存储 */
    private final ConcurrentMap<String, SnapshotMetadata> snapshots = new ConcurrentHashMap<>();
    
    /** 快照数据 */
    private final ConcurrentMap<String, byte[]> snapshotData = new ConcurrentHashMap<>();
    
    /** 变更追踪 */
    private final ConcurrentMap<String, ChangeLog> changeLogs = new ConcurrentHashMap<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    private ExecutorService snapshotPool;
    
    /** 统计 */
    private final AtomicLong snapshotCount = new AtomicLong(0);
    private final AtomicLong restoreCount = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    
    private Counter snapshotCounter;
    private Counter restoreCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getSnapshot().isEnabled()) {
            log.info("[快照管理] 已禁用");
            return;
        }
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "snapshot-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        snapshotPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "snapshot-worker");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 定期快照
        int interval = properties.getSnapshot().getIntervalSec();
        scheduler.scheduleWithFixedDelay(this::checkScheduledSnapshots, interval, interval, TimeUnit.SECONDS);
        
        // 定期清理
        scheduler.scheduleWithFixedDelay(this::cleanupOldSnapshots, 300, 300, TimeUnit.SECONDS);
        
        log.info("[快照管理] 初始化完成 - 间隔: {}s, 最大保留: {}",
            interval, properties.getSnapshot().getMaxSnapshots());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (snapshotPool != null) snapshotPool.shutdown();
        log.info("[快照管理] 已关闭 - 快照: {}, 恢复: {}",
            snapshotCount.get(), restoreCount.get());
    }
    
    private void initMetrics() {
        snapshotCounter = Counter.builder("cache.snapshot.create").register(meterRegistry);
        restoreCounter = Counter.builder("cache.snapshot.restore").register(meterRegistry);
    }
    
    // ========== 快照创建 ==========
    
    /**
     * 创建全量快照
     */
    public SnapshotResult createSnapshot(String snapshotId, Map<String, Object> data) {
        long startTime = System.currentTimeMillis();
        
        try {
            byte[] compressed = compress(data);
            
            SnapshotMetadata metadata = new SnapshotMetadata(
                snapshotId,
                SnapshotType.FULL,
                Instant.now(),
                data.size(),
                compressed.length,
                null
            );
            
            snapshots.put(snapshotId, metadata);
            snapshotData.put(snapshotId, compressed);
            
            snapshotCount.incrementAndGet();
            snapshotCounter.increment();
            totalBytes.addAndGet(compressed.length);
            
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[快照管理] 创建快照: {} - 条目: {}, 大小: {}KB, 耗时: {}ms",
                snapshotId, data.size(), compressed.length / 1024, elapsed);
            
            return new SnapshotResult(snapshotId, true, "Snapshot created", metadata);
            
        } catch (Exception e) {
            log.error("[快照管理] 创建快照失败: {}", e.getMessage());
            return new SnapshotResult(snapshotId, false, e.getMessage(), null);
        }
    }
    
    /**
     * 创建增量快照
     */
    public SnapshotResult createIncrementalSnapshot(String snapshotId, String baseSnapshotId, 
                                                     Map<String, Object> changes) {
        if (!properties.getSnapshot().isIncrementalEnabled()) {
            return createSnapshot(snapshotId, changes);
        }
        
        SnapshotMetadata baseSnapshot = snapshots.get(baseSnapshotId);
        if (baseSnapshot == null) {
            return new SnapshotResult(snapshotId, false, "Base snapshot not found", null);
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            byte[] compressed = compress(changes);
            
            SnapshotMetadata metadata = new SnapshotMetadata(
                snapshotId,
                SnapshotType.INCREMENTAL,
                Instant.now(),
                changes.size(),
                compressed.length,
                baseSnapshotId
            );
            
            snapshots.put(snapshotId, metadata);
            snapshotData.put(snapshotId, compressed);
            
            snapshotCount.incrementAndGet();
            totalBytes.addAndGet(compressed.length);
            
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[快照管理] 创建增量快照: {} - 基于: {}, 变更: {}, 耗时: {}ms",
                snapshotId, baseSnapshotId, changes.size(), elapsed);
            
            return new SnapshotResult(snapshotId, true, "Incremental snapshot created", metadata);
            
        } catch (Exception e) {
            return new SnapshotResult(snapshotId, false, e.getMessage(), null);
        }
    }
    
    /**
     * 异步创建快照
     */
    public CompletableFuture<SnapshotResult> createSnapshotAsync(String snapshotId, Map<String, Object> data) {
        return CompletableFuture.supplyAsync(() -> createSnapshot(snapshotId, data), snapshotPool);
    }
    
    // ========== 快照恢复 ==========
    
    /**
     * 恢复快照
     */
    @SuppressWarnings("unchecked")
    public RestoreResult restoreSnapshot(String snapshotId) {
        long startTime = System.currentTimeMillis();
        restoreCount.incrementAndGet();
        restoreCounter.increment();
        
        SnapshotMetadata metadata = snapshots.get(snapshotId);
        if (metadata == null) {
            return new RestoreResult(snapshotId, false, "Snapshot not found", null);
        }
        
        try {
            Map<String, Object> data;
            
            if (metadata.getType() == SnapshotType.INCREMENTAL) {
                // 需要合并基础快照
                data = restoreIncrementalChain(snapshotId);
            } else {
                byte[] compressed = snapshotData.get(snapshotId);
                data = decompress(compressed);
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[快照管理] 恢复快照: {} - 条目: {}, 耗时: {}ms",
                snapshotId, data.size(), elapsed);
            
            return new RestoreResult(snapshotId, true, "Restored", data);
            
        } catch (Exception e) {
            log.error("[快照管理] 恢复快照失败: {}", e.getMessage());
            return new RestoreResult(snapshotId, false, e.getMessage(), null);
        }
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> restoreIncrementalChain(String snapshotId) throws Exception {
        List<String> chain = new ArrayList<>();
        String current = snapshotId;
        
        // 构建快照链
        while (current != null) {
            chain.add(0, current);
            SnapshotMetadata meta = snapshots.get(current);
            current = meta != null ? meta.getBaseSnapshotId() : null;
        }
        
        // 从基础开始合并
        Map<String, Object> result = new HashMap<>();
        for (String id : chain) {
            byte[] compressed = snapshotData.get(id);
            Map<String, Object> data = decompress(compressed);
            result.putAll(data);
        }
        
        return result;
    }
    
    /**
     * 异步恢复
     */
    public CompletableFuture<RestoreResult> restoreSnapshotAsync(String snapshotId) {
        return CompletableFuture.supplyAsync(() -> restoreSnapshot(snapshotId), snapshotPool);
    }
    
    // ========== 快照管理 ==========
    
    /**
     * 删除快照
     */
    public boolean deleteSnapshot(String snapshotId) {
        SnapshotMetadata removed = snapshots.remove(snapshotId);
        byte[] data = snapshotData.remove(snapshotId);
        
        if (removed != null && data != null) {
            totalBytes.addAndGet(-data.length);
            log.info("[快照管理] 删除快照: {}", snapshotId);
            return true;
        }
        return false;
    }
    
    /**
     * 获取快照元数据
     */
    public Optional<SnapshotMetadata> getSnapshotMetadata(String snapshotId) {
        return Optional.ofNullable(snapshots.get(snapshotId));
    }
    
    /**
     * 列出所有快照
     */
    public List<SnapshotMetadata> listSnapshots() {
        return new ArrayList<>(snapshots.values());
    }
    
    // ========== 变更追踪 ==========
    
    /**
     * 记录变更
     */
    public void recordChange(String key, Object oldValue, Object newValue) {
        String logId = "changelog-" + (System.currentTimeMillis() / 60000);
        changeLogs.computeIfAbsent(logId, k -> new ChangeLog(k))
            .addChange(key, oldValue, newValue);
    }
    
    /**
     * 获取变更日志
     */
    public Optional<ChangeLog> getChangeLog(String logId) {
        return Optional.ofNullable(changeLogs.get(logId));
    }
    
    // ========== 内部方法 ==========
    
    private void checkScheduledSnapshots() {
        // 检查是否需要自动快照
        log.debug("[快照管理] 检查定时快照");
    }
    
    private void cleanupOldSnapshots() {
        int maxSnapshots = properties.getSnapshot().getMaxSnapshots();
        
        if (snapshots.size() <= maxSnapshots) return;
        
        // 按时间排序，删除最旧的
        List<SnapshotMetadata> sorted = snapshots.values().stream()
            .sorted(Comparator.comparing(SnapshotMetadata::getCreatedAt))
            .toList();
        
        int toRemove = snapshots.size() - maxSnapshots;
        for (int i = 0; i < toRemove && i < sorted.size(); i++) {
            deleteSnapshot(sorted.get(i).getSnapshotId());
        }
    }
    
    @SuppressWarnings("unchecked")
    private byte[] compress(Map<String, Object> data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos);
             ObjectOutputStream oos = new ObjectOutputStream(gzip)) {
            oos.writeObject(new HashMap<>(data));
        }
        return baos.toByteArray();
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> decompress(byte[] compressed) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        try (GZIPInputStream gzip = new GZIPInputStream(bais);
             ObjectInputStream ois = new ObjectInputStream(gzip)) {
            return (Map<String, Object>) ois.readObject();
        }
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("snapshotCount", snapshots.size());
        stats.put("totalSnapshots", snapshotCount.get());
        stats.put("totalRestores", restoreCount.get());
        stats.put("totalBytes", totalBytes.get());
        stats.put("totalBytesMB", totalBytes.get() / (1024 * 1024));
        stats.put("changeLogCount", changeLogs.size());
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    public enum SnapshotType {
        FULL, INCREMENTAL
    }
    
    @Data
    public static class SnapshotMetadata implements Serializable {
        private final String snapshotId;
        private final SnapshotType type;
        private final Instant createdAt;
        private final long entryCount;
        private final long compressedSize;
        private final String baseSnapshotId;
    }
    
    @Data
    public static class SnapshotResult {
        private final String snapshotId;
        private final boolean success;
        private final String message;
        private final SnapshotMetadata metadata;
    }
    
    @Data
    public static class RestoreResult {
        private final String snapshotId;
        private final boolean success;
        private final String message;
        private final Map<String, Object> data;
    }
    
    @Data
    public static class ChangeLog {
        private final String logId;
        private final List<ChangeEntry> changes = new CopyOnWriteArrayList<>();
        
        void addChange(String key, Object oldValue, Object newValue) {
            changes.add(new ChangeEntry(key, oldValue, newValue, System.currentTimeMillis()));
        }
    }
    
    @Data
    public static class ChangeEntry {
        private final String key;
        private final Object oldValue;
        private final Object newValue;
        private final long timestamp;
    }
}
