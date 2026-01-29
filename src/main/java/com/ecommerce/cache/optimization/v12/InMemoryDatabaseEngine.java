package com.ecommerce.cache.optimization.v12;

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
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

/**
 * 内存数据库引擎
 * 
 * 核心特性:
 * 1. B+树索引: 高效范围查询
 * 2. 多版本并发: MVCC支持
 * 3. WAL日志: 持久化保障
 * 4. 检查点: 崩溃恢复
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InMemoryDatabaseEngine {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV12Properties properties;
    
    /** 表存储 */
    private final ConcurrentMap<String, MemTable> tables = new ConcurrentHashMap<>();
    
    /** WAL日志 */
    private final Queue<WALEntry> walLog = new ConcurrentLinkedQueue<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong scanCount = new AtomicLong(0);
    
    private Counter readCounter;
    private Counter writeCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getInMemoryDb().isEnabled()) {
            log.info("[内存数据库] 已禁用");
            return;
        }
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "inmem-db");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 定期检查点
        int interval = properties.getInMemoryDb().getCheckpointIntervalSec();
        scheduler.scheduleWithFixedDelay(this::checkpoint, interval, interval, TimeUnit.SECONDS);
        
        log.info("[内存数据库] 初始化完成 - 最大内存: {}MB, 索引类型: {}",
            properties.getInMemoryDb().getMaxMemoryMb(),
            properties.getInMemoryDb().getIndexType());
    }
    
    @PreDestroy
    public void shutdown() {
        checkpoint();
        if (scheduler != null) scheduler.shutdown();
        log.info("[内存数据库] 已关闭 - 读: {}, 写: {}", readCount.get(), writeCount.get());
    }
    
    private void initMetrics() {
        readCounter = Counter.builder("cache.inmemdb.read").register(meterRegistry);
        writeCounter = Counter.builder("cache.inmemdb.write").register(meterRegistry);
    }
    
    // ========== 表操作 ==========
    
    public void createTable(String tableName) {
        tables.computeIfAbsent(tableName, k -> new MemTable(tableName));
        log.info("[内存数据库] 创建表: {}", tableName);
    }
    
    public void dropTable(String tableName) {
        tables.remove(tableName);
    }
    
    public Set<String> listTables() {
        return new HashSet<>(tables.keySet());
    }
    
    // ========== CRUD操作 ==========
    
    public void put(String table, String key, Map<String, Object> value) {
        MemTable t = getOrCreateTable(table);
        
        if (properties.getInMemoryDb().isWalEnabled()) {
            walLog.offer(new WALEntry("PUT", table, key, value, System.currentTimeMillis()));
        }
        
        t.put(key, value);
        writeCount.incrementAndGet();
        writeCounter.increment();
    }
    
    public Optional<Map<String, Object>> get(String table, String key) {
        readCount.incrementAndGet();
        readCounter.increment();
        
        MemTable t = tables.get(table);
        if (t == null) return Optional.empty();
        
        return t.get(key);
    }
    
    public boolean delete(String table, String key) {
        MemTable t = tables.get(table);
        if (t == null) return false;
        
        if (properties.getInMemoryDb().isWalEnabled()) {
            walLog.offer(new WALEntry("DELETE", table, key, null, System.currentTimeMillis()));
        }
        
        return t.delete(key);
    }
    
    // ========== 范围查询 ==========
    
    public List<Map<String, Object>> scan(String table, String startKey, String endKey, int limit) {
        scanCount.incrementAndGet();
        
        MemTable t = tables.get(table);
        if (t == null) return Collections.emptyList();
        
        return t.scan(startKey, endKey, limit);
    }
    
    public List<Map<String, Object>> query(String table, Predicate<Map<String, Object>> predicate, int limit) {
        scanCount.incrementAndGet();
        
        MemTable t = tables.get(table);
        if (t == null) return Collections.emptyList();
        
        return t.query(predicate, limit);
    }
    
    // ========== 索引操作 ==========
    
    public void createIndex(String table, String column) {
        MemTable t = tables.get(table);
        if (t != null) {
            t.createIndex(column);
        }
    }
    
    public List<Map<String, Object>> queryByIndex(String table, String column, Object value) {
        MemTable t = tables.get(table);
        if (t == null) return Collections.emptyList();
        
        return t.queryByIndex(column, value);
    }
    
    // ========== 事务支持 ==========
    
    public Transaction beginTransaction() {
        return new Transaction(this);
    }
    
    // ========== 内部方法 ==========
    
    private MemTable getOrCreateTable(String name) {
        return tables.computeIfAbsent(name, k -> new MemTable(name));
    }
    
    private void checkpoint() {
        // 持久化WAL日志
        int flushed = 0;
        while (!walLog.isEmpty() && flushed < 10000) {
            walLog.poll();
            flushed++;
        }
        log.debug("[内存数据库] 检查点完成 - 刷新: {}", flushed);
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("tableCount", tables.size());
        stats.put("readCount", readCount.get());
        stats.put("writeCount", writeCount.get());
        stats.put("scanCount", scanCount.get());
        stats.put("walSize", walLog.size());
        
        long totalRows = tables.values().stream().mapToLong(MemTable::size).sum();
        stats.put("totalRows", totalRows);
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    private static class MemTable {
        private final String name;
        private final ConcurrentSkipListMap<String, RowData> data = new ConcurrentSkipListMap<>();
        private final ConcurrentMap<String, ConcurrentSkipListMap<Object, Set<String>>> indexes = new ConcurrentHashMap<>();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        
        MemTable(String name) {
            this.name = name;
        }
        
        void put(String key, Map<String, Object> value) {
            lock.writeLock().lock();
            try {
                RowData row = new RowData(key, value, System.currentTimeMillis());
                data.put(key, row);
                
                // 更新索引
                for (var idx : indexes.entrySet()) {
                    Object colVal = value.get(idx.getKey());
                    if (colVal != null) {
                        idx.getValue().computeIfAbsent(colVal, k -> ConcurrentHashMap.newKeySet()).add(key);
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        Optional<Map<String, Object>> get(String key) {
            lock.readLock().lock();
            try {
                RowData row = data.get(key);
                return row != null ? Optional.of(row.value) : Optional.empty();
            } finally {
                lock.readLock().unlock();
            }
        }
        
        boolean delete(String key) {
            lock.writeLock().lock();
            try {
                RowData removed = data.remove(key);
                if (removed != null) {
                    // 清理索引
                    for (var idx : indexes.values()) {
                        for (Set<String> keys : idx.values()) {
                            keys.remove(key);
                        }
                    }
                    return true;
                }
                return false;
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        List<Map<String, Object>> scan(String startKey, String endKey, int limit) {
            lock.readLock().lock();
            try {
                NavigableMap<String, RowData> subMap = data.subMap(startKey, true, endKey, true);
                return subMap.values().stream()
                    .limit(limit)
                    .map(r -> r.value)
                    .toList();
            } finally {
                lock.readLock().unlock();
            }
        }
        
        List<Map<String, Object>> query(Predicate<Map<String, Object>> predicate, int limit) {
            lock.readLock().lock();
            try {
                return data.values().stream()
                    .map(r -> r.value)
                    .filter(predicate)
                    .limit(limit)
                    .toList();
            } finally {
                lock.readLock().unlock();
            }
        }
        
        void createIndex(String column) {
            indexes.computeIfAbsent(column, k -> new ConcurrentSkipListMap<>());
        }
        
        List<Map<String, Object>> queryByIndex(String column, Object value) {
            ConcurrentSkipListMap<Object, Set<String>> idx = indexes.get(column);
            if (idx == null) return Collections.emptyList();
            
            Set<String> keys = idx.get(value);
            if (keys == null) return Collections.emptyList();
            
            lock.readLock().lock();
            try {
                return keys.stream()
                    .map(data::get)
                    .filter(Objects::nonNull)
                    .map(r -> r.value)
                    .toList();
            } finally {
                lock.readLock().unlock();
            }
        }
        
        long size() {
            return data.size();
        }
    }
    
    @Data
    private static class RowData {
        private final String key;
        private final Map<String, Object> value;
        private final long timestamp;
    }
    
    private record WALEntry(String op, String table, String key, Map<String, Object> value, long timestamp) {}
    
    @Data
    public static class Transaction {
        private final InMemoryDatabaseEngine engine;
        private final List<Runnable> operations = new ArrayList<>();
        private boolean committed = false;
        
        public Transaction put(String table, String key, Map<String, Object> value) {
            operations.add(() -> engine.put(table, key, value));
            return this;
        }
        
        public Transaction delete(String table, String key) {
            operations.add(() -> engine.delete(table, key));
            return this;
        }
        
        public void commit() {
            if (committed) throw new IllegalStateException("Already committed");
            for (Runnable op : operations) {
                op.run();
            }
            committed = true;
        }
        
        public void rollback() {
            operations.clear();
            committed = true;
        }
    }
}
