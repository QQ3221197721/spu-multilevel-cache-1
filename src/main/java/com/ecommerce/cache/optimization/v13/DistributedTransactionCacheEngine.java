package com.ecommerce.cache.optimization.v13;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * V13分布式事务缓存引擎
 * 支持2PC/SAGA/TCC事务模式、死锁检测、乐观锁
 */
@Component
public class DistributedTransactionCacheEngine {

    private static final Logger log = LoggerFactory.getLogger(DistributedTransactionCacheEngine.class);

    private final OptimizationV13Properties properties;
    private final MeterRegistry meterRegistry;

    // 事务存储
    private final ConcurrentMap<String, CacheTransaction> activeTransactions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TransactionLog> transactionLogs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, VersionedValue> dataStore = new ConcurrentHashMap<>();
    
    // 锁管理
    private final ConcurrentMap<String, LockInfo> lockTable = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> waitForGraph = new ConcurrentHashMap<>();
    
    // 调度器
    private ScheduledExecutorService scheduler;
    private ExecutorService transactionExecutor;
    
    // 计数器
    private final AtomicLong transactionIdGenerator = new AtomicLong(0);
    private final AtomicLong versionGenerator = new AtomicLong(0);
    
    // 指标
    private Counter transactionsStarted;
    private Counter transactionsCommitted;
    private Counter transactionsRolledBack;
    private Counter deadlocksDetected;
    private Timer transactionLatency;

    public DistributedTransactionCacheEngine(OptimizationV13Properties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        if (!properties.getDistributedTransaction().isEnabled()) {
            log.info("V13分布式事务缓存引擎已禁用");
            return;
        }

        initializeMetrics();
        initializeExecutors();
        startBackgroundTasks();

        log.info("V13分布式事务缓存引擎初始化完成 - 最大并发事务: {}, 超时: {}ms",
                properties.getDistributedTransaction().getMaxConcurrentTransactions(),
                properties.getDistributedTransaction().getTransactionTimeoutMs());
    }

    private void initializeMetrics() {
        transactionsStarted = Counter.builder("v13.tx.started")
                .description("启动的事务数").register(meterRegistry);
        transactionsCommitted = Counter.builder("v13.tx.committed")
                .description("提交的事务数").register(meterRegistry);
        transactionsRolledBack = Counter.builder("v13.tx.rolledback")
                .description("回滚的事务数").register(meterRegistry);
        deadlocksDetected = Counter.builder("v13.tx.deadlocks")
                .description("检测到的死锁数").register(meterRegistry);
        transactionLatency = Timer.builder("v13.tx.latency")
                .description("事务延迟").register(meterRegistry);
    }

    private void initializeExecutors() {
        int maxTx = properties.getDistributedTransaction().getMaxConcurrentTransactions();
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "v13-tx-scheduler");
            t.setDaemon(true);
            return t;
        });
        transactionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private void startBackgroundTasks() {
        // 死锁检测
        if (properties.getDistributedTransaction().isDeadlockDetectionEnabled()) {
            scheduler.scheduleAtFixedRate(this::detectDeadlocks, 100, 100, TimeUnit.MILLISECONDS);
        }
        // 超时事务清理
        scheduler.scheduleAtFixedRate(this::cleanupTimeoutTransactions, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (transactionExecutor != null) {
            transactionExecutor.shutdown();
        }
    }

    // ==================== 事务API ====================

    /**
     * 开始新事务
     */
    public String beginTransaction() {
        return beginTransaction(TransactionMode.TWO_PHASE_COMMIT);
    }

    public String beginTransaction(TransactionMode mode) {
        if (activeTransactions.size() >= properties.getDistributedTransaction().getMaxConcurrentTransactions()) {
            throw new TransactionException("达到最大并发事务限制");
        }

        String txId = generateTransactionId();
        CacheTransaction tx = new CacheTransaction(txId, mode);
        tx.setIsolationLevel(IsolationLevel.valueOf(properties.getDistributedTransaction().getIsolationLevel()));
        activeTransactions.put(txId, tx);
        
        transactionsStarted.increment();
        log.debug("开始事务: {} 模式: {}", txId, mode);
        
        return txId;
    }

    /**
     * 事务内读取
     */
    public <T> T transactionalGet(String txId, String key, Class<T> type) {
        CacheTransaction tx = getActiveTransaction(txId);
        
        // 检查写集合（读己写）
        if (tx.getWriteSet().containsKey(key)) {
            return type.cast(tx.getWriteSet().get(key));
        }
        
        // 获取共享锁
        acquireLock(txId, key, LockMode.SHARED);
        
        VersionedValue vv = dataStore.get(key);
        if (vv == null) {
            return null;
        }
        
        // MVCC读取
        if (tx.getIsolationLevel() == IsolationLevel.REPEATABLE_READ || 
            tx.getIsolationLevel() == IsolationLevel.SERIALIZABLE) {
            tx.getReadSet().put(key, vv.getVersion());
        }
        
        return type.cast(vv.getValue());
    }

    /**
     * 事务内写入
     */
    public void transactionalPut(String txId, String key, Object value) {
        CacheTransaction tx = getActiveTransaction(txId);
        
        // 获取排他锁
        acquireLock(txId, key, LockMode.EXCLUSIVE);
        
        // 乐观锁检查
        if (properties.getDistributedTransaction().isOptimisticLockingEnabled()) {
            VersionedValue current = dataStore.get(key);
            if (current != null && tx.getReadSet().containsKey(key)) {
                long readVersion = tx.getReadSet().get(key);
                if (current.getVersion() != readVersion) {
                    throw new OptimisticLockException("乐观锁冲突: key=" + key);
                }
            }
        }
        
        tx.getWriteSet().put(key, value);
        tx.getOldValues().putIfAbsent(key, dataStore.get(key));
        
        log.debug("事务写入: tx={}, key={}", txId, key);
    }

    /**
     * 事务内删除
     */
    public void transactionalDelete(String txId, String key) {
        CacheTransaction tx = getActiveTransaction(txId);
        acquireLock(txId, key, LockMode.EXCLUSIVE);
        
        tx.getDeleteSet().add(key);
        tx.getOldValues().putIfAbsent(key, dataStore.get(key));
    }

    /**
     * 提交事务
     */
    public boolean commit(String txId) {
        CacheTransaction tx = getActiveTransaction(txId);
        
        return transactionLatency.record(() -> {
            try {
                boolean success = switch (tx.getMode()) {
                    case TWO_PHASE_COMMIT -> commitTwoPhase(tx);
                    case SAGA -> commitSaga(tx);
                    case TCC -> commitTcc(tx);
                };
                
                if (success) {
                    transactionsCommitted.increment();
                    log.debug("事务提交成功: {}", txId);
                } else {
                    transactionsRolledBack.increment();
                    log.warn("事务提交失败: {}", txId);
                }
                
                return success;
            } finally {
                cleanup(txId);
            }
        });
    }

    /**
     * 回滚事务
     */
    public void rollback(String txId) {
        CacheTransaction tx = activeTransactions.get(txId);
        if (tx == null) {
            return;
        }
        
        try {
            // 恢复旧值
            for (Map.Entry<String, VersionedValue> entry : tx.getOldValues().entrySet()) {
                if (entry.getValue() != null) {
                    dataStore.put(entry.getKey(), entry.getValue());
                } else {
                    dataStore.remove(entry.getKey());
                }
            }
            
            transactionsRolledBack.increment();
            log.debug("事务回滚: {}", txId);
        } finally {
            cleanup(txId);
        }
    }

    // ==================== 2PC实现 ====================

    private boolean commitTwoPhase(CacheTransaction tx) {
        // Phase 1: Prepare
        if (!prepare(tx)) {
            log.warn("2PC准备阶段失败: {}", tx.getId());
            return false;
        }
        
        // Phase 2: Commit
        return doCommit(tx);
    }

    private boolean prepare(CacheTransaction tx) {
        tx.setState(TransactionState.PREPARING);
        
        // 验证所有写入
        for (String key : tx.getWriteSet().keySet()) {
            if (!validateWrite(tx, key)) {
                return false;
            }
        }
        
        // 记录准备日志
        TransactionLog log = new TransactionLog(tx.getId(), TransactionState.PREPARED);
        log.setWriteSet(new HashMap<>(tx.getWriteSet()));
        log.setDeleteSet(new HashSet<>(tx.getDeleteSet()));
        transactionLogs.put(tx.getId(), log);
        
        tx.setState(TransactionState.PREPARED);
        return true;
    }

    private boolean doCommit(CacheTransaction tx) {
        tx.setState(TransactionState.COMMITTING);
        
        long newVersion = versionGenerator.incrementAndGet();
        
        // 应用写入
        for (Map.Entry<String, Object> entry : tx.getWriteSet().entrySet()) {
            dataStore.put(entry.getKey(), new VersionedValue(entry.getValue(), newVersion));
        }
        
        // 应用删除
        for (String key : tx.getDeleteSet()) {
            dataStore.remove(key);
        }
        
        tx.setState(TransactionState.COMMITTED);
        
        // 更新日志
        TransactionLog txLog = transactionLogs.get(tx.getId());
        if (txLog != null) {
            txLog.setState(TransactionState.COMMITTED);
        }
        
        return true;
    }

    private boolean validateWrite(CacheTransaction tx, String key) {
        if (!properties.getDistributedTransaction().isOptimisticLockingEnabled()) {
            return true;
        }
        
        VersionedValue current = dataStore.get(key);
        if (current == null) {
            return true;
        }
        
        Long readVersion = tx.getReadSet().get(key);
        return readVersion == null || current.getVersion() == readVersion;
    }

    // ==================== SAGA实现 ====================

    private boolean commitSaga(CacheTransaction tx) {
        List<SagaStep> steps = buildSagaSteps(tx);
        int completedSteps = 0;
        
        try {
            for (SagaStep step : steps) {
                step.execute();
                completedSteps++;
            }
            return true;
        } catch (Exception e) {
            // 补偿已完成的步骤
            for (int i = completedSteps - 1; i >= 0; i--) {
                try {
                    steps.get(i).compensate();
                } catch (Exception ce) {
                    log.error("SAGA补偿失败: step={}", i, ce);
                }
            }
            return false;
        }
    }

    private List<SagaStep> buildSagaSteps(CacheTransaction tx) {
        List<SagaStep> steps = new ArrayList<>();
        
        // 写入步骤
        for (Map.Entry<String, Object> entry : tx.getWriteSet().entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();
            VersionedValue oldValue = tx.getOldValues().get(key);
            
            steps.add(new SagaStep(
                    () -> dataStore.put(key, new VersionedValue(newValue, versionGenerator.incrementAndGet())),
                    () -> {
                        if (oldValue != null) {
                            dataStore.put(key, oldValue);
                        } else {
                            dataStore.remove(key);
                        }
                    }
            ));
        }
        
        // 删除步骤
        for (String key : tx.getDeleteSet()) {
            VersionedValue oldValue = tx.getOldValues().get(key);
            steps.add(new SagaStep(
                    () -> dataStore.remove(key),
                    () -> {
                        if (oldValue != null) {
                            dataStore.put(key, oldValue);
                        }
                    }
            ));
        }
        
        return steps;
    }

    // ==================== TCC实现 ====================

    private boolean commitTcc(CacheTransaction tx) {
        // Try阶段 - 资源预留
        if (!tryPhase(tx)) {
            cancelPhase(tx);
            return false;
        }
        
        // Confirm阶段 - 确认执行
        return confirmPhase(tx);
    }

    private boolean tryPhase(CacheTransaction tx) {
        tx.setState(TransactionState.TRYING);
        
        // 预留资源（检查并锁定）
        for (String key : tx.getWriteSet().keySet()) {
            if (!reserveResource(tx.getId(), key)) {
                return false;
            }
        }
        
        return true;
    }

    private boolean confirmPhase(CacheTransaction tx) {
        tx.setState(TransactionState.CONFIRMING);
        
        long version = versionGenerator.incrementAndGet();
        
        for (Map.Entry<String, Object> entry : tx.getWriteSet().entrySet()) {
            dataStore.put(entry.getKey(), new VersionedValue(entry.getValue(), version));
        }
        
        for (String key : tx.getDeleteSet()) {
            dataStore.remove(key);
        }
        
        tx.setState(TransactionState.COMMITTED);
        return true;
    }

    private void cancelPhase(CacheTransaction tx) {
        tx.setState(TransactionState.CANCELLING);
        // 释放预留资源
        tx.setState(TransactionState.CANCELLED);
    }

    private boolean reserveResource(String txId, String key) {
        // 简化实现：检查是否可以获取排他锁
        LockInfo lock = lockTable.get(key);
        if (lock != null && !lock.getTxId().equals(txId) && lock.getMode() == LockMode.EXCLUSIVE) {
            return false;
        }
        return true;
    }

    // ==================== 锁管理 ====================

    private void acquireLock(String txId, String key, LockMode mode) {
        long timeout = properties.getDistributedTransaction().getLockWaitTimeoutMs();
        long deadline = System.currentTimeMillis() + timeout;
        
        while (System.currentTimeMillis() < deadline) {
            LockInfo existingLock = lockTable.get(key);
            
            if (existingLock == null) {
                LockInfo newLock = new LockInfo(txId, key, mode);
                if (lockTable.putIfAbsent(key, newLock) == null) {
                    return;
                }
            } else if (existingLock.getTxId().equals(txId)) {
                // 锁升级
                if (mode == LockMode.EXCLUSIVE && existingLock.getMode() == LockMode.SHARED) {
                    existingLock.setMode(LockMode.EXCLUSIVE);
                }
                return;
            } else if (existingLock.getMode() == LockMode.SHARED && mode == LockMode.SHARED) {
                existingLock.addHolder(txId);
                return;
            } else {
                // 记录等待关系
                waitForGraph.computeIfAbsent(txId, k -> ConcurrentHashMap.newKeySet())
                        .add(existingLock.getTxId());
            }
            
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransactionException("锁等待被中断");
            }
        }
        
        throw new LockTimeoutException("获取锁超时: key=" + key);
    }

    private void releaseLocks(String txId) {
        lockTable.entrySet().removeIf(entry -> {
            LockInfo lock = entry.getValue();
            if (lock.getTxId().equals(txId)) {
                return true;
            }
            lock.removeHolder(txId);
            return lock.getHolders().isEmpty();
        });
        
        waitForGraph.remove(txId);
        waitForGraph.values().forEach(set -> set.remove(txId));
    }

    // ==================== 死锁检测 ====================

    private void detectDeadlocks() {
        try {
            Set<String> visited = new HashSet<>();
            Set<String> recursionStack = new HashSet<>();
            
            for (String txId : waitForGraph.keySet()) {
                if (hasCycle(txId, visited, recursionStack)) {
                    handleDeadlock(recursionStack);
                }
            }
        } catch (Exception e) {
            log.error("死锁检测异常", e);
        }
    }

    private boolean hasCycle(String txId, Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(txId)) {
            return true;
        }
        if (visited.contains(txId)) {
            return false;
        }
        
        visited.add(txId);
        recursionStack.add(txId);
        
        Set<String> waitingFor = waitForGraph.get(txId);
        if (waitingFor != null) {
            for (String waitTxId : waitingFor) {
                if (hasCycle(waitTxId, visited, recursionStack)) {
                    return true;
                }
            }
        }
        
        recursionStack.remove(txId);
        return false;
    }

    private void handleDeadlock(Set<String> involvedTransactions) {
        // 选择一个牺牲者（最年轻的事务）
        String victim = involvedTransactions.stream()
                .max(Comparator.comparing(this::getTransactionStartTime))
                .orElse(null);
        
        if (victim != null) {
            log.warn("检测到死锁，回滚事务: {}", victim);
            deadlocksDetected.increment();
            rollback(victim);
        }
    }

    private long getTransactionStartTime(String txId) {
        CacheTransaction tx = activeTransactions.get(txId);
        return tx != null ? tx.getStartTime() : 0;
    }

    // ==================== 辅助方法 ====================

    private String generateTransactionId() {
        long id = transactionIdGenerator.incrementAndGet();
        long timestamp = System.currentTimeMillis();
        String raw = timestamp + "-" + id + "-" + Thread.currentThread().getId();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return "TX-" + sb;
        } catch (Exception e) {
            return "TX-" + timestamp + "-" + id;
        }
    }

    private CacheTransaction getActiveTransaction(String txId) {
        CacheTransaction tx = activeTransactions.get(txId);
        if (tx == null) {
            throw new TransactionException("事务不存在或已结束: " + txId);
        }
        if (tx.isExpired(properties.getDistributedTransaction().getTransactionTimeoutMs())) {
            rollback(txId);
            throw new TransactionException("事务已超时: " + txId);
        }
        return tx;
    }

    private void cleanup(String txId) {
        activeTransactions.remove(txId);
        releaseLocks(txId);
    }

    private void cleanupTimeoutTransactions() {
        long timeout = properties.getDistributedTransaction().getTransactionTimeoutMs();
        activeTransactions.forEach((txId, tx) -> {
            if (tx.isExpired(timeout)) {
                log.warn("清理超时事务: {}", txId);
                rollback(txId);
            }
        });
    }

    // ==================== 执行模板 ====================

    /**
     * 在事务中执行操作
     */
    public <T> T executeInTransaction(Supplier<T> action) {
        return executeInTransaction(action, TransactionMode.TWO_PHASE_COMMIT);
    }

    public <T> T executeInTransaction(Supplier<T> action, TransactionMode mode) {
        String txId = beginTransaction(mode);
        try {
            T result = action.get();
            commit(txId);
            return result;
        } catch (Exception e) {
            rollback(txId);
            throw e;
        }
    }

    // ==================== 统计信息 ====================

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activeTransactions", activeTransactions.size());
        stats.put("dataStoreSize", dataStore.size());
        stats.put("lockedKeys", lockTable.size());
        stats.put("transactionLogs", transactionLogs.size());
        return stats;
    }

    // ==================== 内部类 ====================

    public enum TransactionMode {
        TWO_PHASE_COMMIT, SAGA, TCC
    }

    public enum TransactionState {
        ACTIVE, PREPARING, PREPARED, COMMITTING, COMMITTED, 
        TRYING, CONFIRMING, CANCELLING, CANCELLED, ROLLED_BACK
    }

    public enum IsolationLevel {
        READ_UNCOMMITTED, READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE
    }

    public enum LockMode {
        SHARED, EXCLUSIVE
    }

    private static class CacheTransaction {
        private final String id;
        private final TransactionMode mode;
        private final long startTime;
        private TransactionState state;
        private IsolationLevel isolationLevel;
        private final Map<String, Long> readSet = new ConcurrentHashMap<>();
        private final Map<String, Object> writeSet = new ConcurrentHashMap<>();
        private final Set<String> deleteSet = ConcurrentHashMap.newKeySet();
        private final Map<String, VersionedValue> oldValues = new ConcurrentHashMap<>();

        CacheTransaction(String id, TransactionMode mode) {
            this.id = id;
            this.mode = mode;
            this.startTime = System.currentTimeMillis();
            this.state = TransactionState.ACTIVE;
            this.isolationLevel = IsolationLevel.READ_COMMITTED;
        }

        String getId() { return id; }
        TransactionMode getMode() { return mode; }
        long getStartTime() { return startTime; }
        TransactionState getState() { return state; }
        void setState(TransactionState state) { this.state = state; }
        IsolationLevel getIsolationLevel() { return isolationLevel; }
        void setIsolationLevel(IsolationLevel level) { this.isolationLevel = level; }
        Map<String, Long> getReadSet() { return readSet; }
        Map<String, Object> getWriteSet() { return writeSet; }
        Set<String> getDeleteSet() { return deleteSet; }
        Map<String, VersionedValue> getOldValues() { return oldValues; }
        
        boolean isExpired(long timeout) {
            return System.currentTimeMillis() - startTime > timeout;
        }
    }

    private static class VersionedValue {
        private final Object value;
        private final long version;

        VersionedValue(Object value, long version) {
            this.value = value;
            this.version = version;
        }

        Object getValue() { return value; }
        long getVersion() { return version; }
    }

    private static class LockInfo {
        private final String txId;
        private final String key;
        private volatile LockMode mode;
        private final Set<String> holders = ConcurrentHashMap.newKeySet();

        LockInfo(String txId, String key, LockMode mode) {
            this.txId = txId;
            this.key = key;
            this.mode = mode;
            this.holders.add(txId);
        }

        String getTxId() { return txId; }
        String getKey() { return key; }
        LockMode getMode() { return mode; }
        void setMode(LockMode mode) { this.mode = mode; }
        Set<String> getHolders() { return holders; }
        void addHolder(String txId) { holders.add(txId); }
        void removeHolder(String txId) { holders.remove(txId); }
    }

    private static class TransactionLog {
        private final String txId;
        private TransactionState state;
        private Map<String, Object> writeSet;
        private Set<String> deleteSet;

        TransactionLog(String txId, TransactionState state) {
            this.txId = txId;
            this.state = state;
        }

        String getTxId() { return txId; }
        TransactionState getState() { return state; }
        void setState(TransactionState state) { this.state = state; }
        Map<String, Object> getWriteSet() { return writeSet; }
        void setWriteSet(Map<String, Object> writeSet) { this.writeSet = writeSet; }
        Set<String> getDeleteSet() { return deleteSet; }
        void setDeleteSet(Set<String> deleteSet) { this.deleteSet = deleteSet; }
    }

    private static class SagaStep {
        private final Runnable execute;
        private final Runnable compensate;

        SagaStep(Runnable execute, Runnable compensate) {
            this.execute = execute;
            this.compensate = compensate;
        }

        void execute() { execute.run(); }
        void compensate() { compensate.run(); }
    }

    // 异常类
    public static class TransactionException extends RuntimeException {
        public TransactionException(String message) { super(message); }
    }

    public static class OptimisticLockException extends TransactionException {
        public OptimisticLockException(String message) { super(message); }
    }

    public static class LockTimeoutException extends TransactionException {
        public LockTimeoutException(String message) { super(message); }
    }
}
