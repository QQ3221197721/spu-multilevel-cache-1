package com.ecommerce.cache.optimization.v13;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * V13多活数据中心缓存引擎
 * 支持Raft选举、跨区复制、脑裂保护、冲突解决
 */
@Component
public class MultiActiveDataCenterEngine {

    private static final Logger log = LoggerFactory.getLogger(MultiActiveDataCenterEngine.class);

    private final OptimizationV13Properties properties;
    private final MeterRegistry meterRegistry;

    // 数据中心信息
    private final String localDataCenterId;
    private final ConcurrentMap<String, DataCenterNode> dataCenters = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentLeader = new AtomicReference<>();
    
    // 数据存储
    private final ConcurrentMap<String, ReplicatedEntry> localStore = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> versionVectors = new ConcurrentHashMap<>();
    
    // Raft状态
    private volatile RaftState raftState = RaftState.FOLLOWER;
    private final AtomicLong currentTerm = new AtomicLong(0);
    private volatile String votedFor = null;
    private final List<LogEntry> raftLog = new CopyOnWriteArrayList<>();
    private volatile long commitIndex = 0;
    private volatile long lastApplied = 0;
    
    // 复制队列
    private final BlockingQueue<ReplicationTask> replicationQueue = new LinkedBlockingQueue<>(100000);
    private final ConcurrentMap<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> matchIndex = new ConcurrentHashMap<>();
    
    // 执行器
    private ScheduledExecutorService scheduler;
    private ExecutorService replicationExecutor;
    
    // 选举计时
    private volatile long lastHeartbeat = System.currentTimeMillis();
    private final Random random = new Random();
    
    // 指标
    private Counter replicationSuccess;
    private Counter replicationFailure;
    private Counter conflictsResolved;
    private Counter leaderElections;
    private Timer replicationLatency;

    public MultiActiveDataCenterEngine(OptimizationV13Properties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.localDataCenterId = generateDataCenterId();
    }

    @PostConstruct
    public void init() {
        if (!properties.getMultiActiveDataCenter().isEnabled()) {
            log.info("V13多活数据中心引擎已禁用");
            return;
        }

        initializeMetrics();
        initializeExecutors();
        initializeDataCenters();
        startBackgroundTasks();

        log.info("V13多活数据中心引擎初始化完成 - 本地DC: {}, 数据中心数: {}",
                localDataCenterId, properties.getMultiActiveDataCenter().getDataCenterCount());
    }

    private void initializeMetrics() {
        replicationSuccess = Counter.builder("v13.dc.replication.success")
                .description("复制成功数").register(meterRegistry);
        replicationFailure = Counter.builder("v13.dc.replication.failure")
                .description("复制失败数").register(meterRegistry);
        conflictsResolved = Counter.builder("v13.dc.conflicts")
                .description("解决的冲突数").register(meterRegistry);
        leaderElections = Counter.builder("v13.dc.elections")
                .description("选举次数").register(meterRegistry);
        replicationLatency = Timer.builder("v13.dc.replication.latency")
                .description("复制延迟").register(meterRegistry);
        
        Gauge.builder("v13.dc.raft.term", currentTerm, AtomicLong::get)
                .description("当前Raft任期").register(meterRegistry);
    }

    private void initializeExecutors() {
        scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "v13-dc-scheduler");
            t.setDaemon(true);
            return t;
        });
        replicationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private void initializeDataCenters() {
        int dcCount = properties.getMultiActiveDataCenter().getDataCenterCount();
        
        // 注册本地数据中心
        DataCenterNode localNode = new DataCenterNode(localDataCenterId, "localhost", 8080);
        localNode.setStatus(NodeStatus.ACTIVE);
        dataCenters.put(localDataCenterId, localNode);
        
        // 模拟其他数据中心
        for (int i = 1; i < dcCount; i++) {
            String dcId = "DC-" + i;
            DataCenterNode node = new DataCenterNode(dcId, "dc" + i + ".cluster.local", 8080 + i);
            node.setStatus(NodeStatus.ACTIVE);
            dataCenters.put(dcId, node);
            nextIndex.put(dcId, 0L);
            matchIndex.put(dcId, 0L);
        }
    }

    private void startBackgroundTasks() {
        // 选举超时检测
        scheduler.scheduleAtFixedRate(this::checkElectionTimeout, 150, 150, TimeUnit.MILLISECONDS);
        
        // 心跳发送（Leader）
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, 50, 50, TimeUnit.MILLISECONDS);
        
        // 复制任务处理
        scheduler.scheduleAtFixedRate(this::processReplicationQueue, 10, 10, TimeUnit.MILLISECONDS);
        
        // 同步检查
        scheduler.scheduleAtFixedRate(this::syncCheck, 
                properties.getMultiActiveDataCenter().getSyncIntervalMs(),
                properties.getMultiActiveDataCenter().getSyncIntervalMs(),
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (replicationExecutor != null) replicationExecutor.shutdown();
    }

    // ==================== 数据操作API ====================

    /**
     * 写入数据（自动复制）
     */
    public void put(String key, Object value) {
        put(key, value, -1);
    }

    public void put(String key, Object value, long ttlMs) {
        long version = System.currentTimeMillis();
        ReplicatedEntry entry = new ReplicatedEntry(key, value, version, localDataCenterId, ttlMs);
        
        // 本地写入
        ReplicatedEntry existing = localStore.get(key);
        if (existing != null) {
            entry = resolveConflict(existing, entry);
        }
        localStore.put(key, entry);
        
        // 更新版本向量
        versionVectors.put(key, version);
        
        // 添加到Raft日志
        if (raftState == RaftState.LEADER) {
            appendLogEntry(new LogEntry(currentTerm.get(), key, value, LogOperation.PUT));
        }
        
        // 异步复制到其他数据中心
        if (properties.getMultiActiveDataCenter().isCrossRegionReplicationEnabled()) {
            replicateToOtherDataCenters(entry);
        }
        
        log.debug("数据写入: key={}, version={}, dc={}", key, version, localDataCenterId);
    }

    /**
     * 读取数据
     */
    public Object get(String key) {
        return get(key, ConsistencyLevel.valueOf(properties.getMultiActiveDataCenter().getConsistencyMode()));
    }

    public Object get(String key, ConsistencyLevel consistency) {
        ReplicatedEntry entry = localStore.get(key);
        
        if (entry == null) {
            return null;
        }
        
        if (entry.isExpired()) {
            localStore.remove(key);
            return null;
        }
        
        // 强一致性读取需要确认Leader
        if (consistency == ConsistencyLevel.STRONG) {
            if (raftState != RaftState.LEADER && !confirmLeaderHasLatest(key)) {
                return null;
            }
        }
        
        return entry.getValue();
    }

    /**
     * 删除数据
     */
    public void delete(String key) {
        ReplicatedEntry tombstone = new ReplicatedEntry(key, null, System.currentTimeMillis(), 
                localDataCenterId, -1);
        tombstone.setDeleted(true);
        
        localStore.put(key, tombstone);
        
        if (raftState == RaftState.LEADER) {
            appendLogEntry(new LogEntry(currentTerm.get(), key, null, LogOperation.DELETE));
        }
        
        replicateToOtherDataCenters(tombstone);
    }

    // ==================== Raft选举 ====================

    private void checkElectionTimeout() {
        if (raftState == RaftState.LEADER) {
            return;
        }
        
        long electionTimeout = 150 + random.nextInt(150); // 150-300ms
        if (System.currentTimeMillis() - lastHeartbeat > electionTimeout) {
            startElection();
        }
    }

    private synchronized void startElection() {
        if (raftState == RaftState.LEADER) {
            return;
        }
        
        currentTerm.incrementAndGet();
        raftState = RaftState.CANDIDATE;
        votedFor = localDataCenterId;
        lastHeartbeat = System.currentTimeMillis();
        
        log.info("开始选举 - term={}, candidate={}", currentTerm.get(), localDataCenterId);
        leaderElections.increment();
        
        int votesReceived = 1; // 自己的票
        int quorum = properties.getMultiActiveDataCenter().getQuorumSize();
        
        // 请求其他节点投票
        for (DataCenterNode node : dataCenters.values()) {
            if (!node.getId().equals(localDataCenterId) && node.getStatus() == NodeStatus.ACTIVE) {
                if (requestVote(node)) {
                    votesReceived++;
                }
            }
        }
        
        // 检查是否获得多数票
        if (votesReceived >= quorum) {
            becomeLeader();
        } else {
            raftState = RaftState.FOLLOWER;
        }
    }

    private boolean requestVote(DataCenterNode node) {
        // 模拟投票请求
        // 实际实现需要RPC调用
        long lastLogIndex = raftLog.isEmpty() ? 0 : raftLog.size() - 1;
        long lastLogTerm = raftLog.isEmpty() ? 0 : raftLog.get(raftLog.size() - 1).getTerm();
        
        // 模拟50%概率投票
        return random.nextBoolean();
    }

    private void becomeLeader() {
        raftState = RaftState.LEADER;
        currentLeader.set(localDataCenterId);
        
        // 初始化nextIndex和matchIndex
        long lastLogIndex = raftLog.size();
        for (String dcId : dataCenters.keySet()) {
            if (!dcId.equals(localDataCenterId)) {
                nextIndex.put(dcId, lastLogIndex);
                matchIndex.put(dcId, 0L);
            }
        }
        
        log.info("成为Leader - term={}, node={}", currentTerm.get(), localDataCenterId);
        
        // 立即发送心跳
        sendHeartbeats();
    }

    private void sendHeartbeats() {
        if (raftState != RaftState.LEADER) {
            return;
        }
        
        for (DataCenterNode node : dataCenters.values()) {
            if (!node.getId().equals(localDataCenterId) && node.getStatus() == NodeStatus.ACTIVE) {
                replicationExecutor.submit(() -> sendAppendEntries(node));
            }
        }
    }

    private void sendAppendEntries(DataCenterNode node) {
        // 模拟AppendEntries RPC
        // 实际实现需要网络通信
        long prevLogIndex = nextIndex.getOrDefault(node.getId(), 0L) - 1;
        long prevLogTerm = prevLogIndex >= 0 && prevLogIndex < raftLog.size() 
                ? raftLog.get((int) prevLogIndex).getTerm() : 0;
        
        List<LogEntry> entries = new ArrayList<>();
        long nextIdx = nextIndex.getOrDefault(node.getId(), 0L);
        for (int i = (int) nextIdx; i < raftLog.size(); i++) {
            entries.add(raftLog.get(i));
        }
        
        // 模拟成功
        if (!entries.isEmpty()) {
            nextIndex.put(node.getId(), nextIdx + entries.size());
            matchIndex.put(node.getId(), nextIdx + entries.size() - 1);
        }
    }

    public void receiveHeartbeat(String leaderId, long term) {
        if (term >= currentTerm.get()) {
            currentTerm.set(term);
            currentLeader.set(leaderId);
            raftState = RaftState.FOLLOWER;
            lastHeartbeat = System.currentTimeMillis();
        }
    }

    // ==================== 复制与冲突解决 ====================

    private void replicateToOtherDataCenters(ReplicatedEntry entry) {
        for (DataCenterNode node : dataCenters.values()) {
            if (!node.getId().equals(localDataCenterId) && node.getStatus() == NodeStatus.ACTIVE) {
                ReplicationTask task = new ReplicationTask(entry, node.getId());
                if (!replicationQueue.offer(task)) {
                    log.warn("复制队列已满，丢弃任务: key={}", entry.getKey());
                }
            }
        }
    }

    private void processReplicationQueue() {
        List<ReplicationTask> batch = new ArrayList<>();
        replicationQueue.drainTo(batch, 100);
        
        if (batch.isEmpty()) {
            return;
        }
        
        // 按目标数据中心分组
        Map<String, List<ReplicationTask>> grouped = batch.stream()
                .collect(Collectors.groupingBy(ReplicationTask::getTargetDcId));
        
        for (Map.Entry<String, List<ReplicationTask>> e : grouped.entrySet()) {
            replicationExecutor.submit(() -> replicateBatch(e.getKey(), e.getValue()));
        }
    }

    private void replicateBatch(String targetDcId, List<ReplicationTask> tasks) {
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            DataCenterNode node = dataCenters.get(targetDcId);
            if (node == null || node.getStatus() != NodeStatus.ACTIVE) {
                replicationFailure.increment(tasks.size());
                return;
            }
            
            // 模拟批量复制
            for (ReplicationTask task : tasks) {
                // 实际实现需要网络调用
                simulateReplication(task.getEntry(), targetDcId);
            }
            
            replicationSuccess.increment(tasks.size());
            sample.stop(replicationLatency);
            
        } catch (Exception e) {
            replicationFailure.increment(tasks.size());
            log.error("复制到 {} 失败", targetDcId, e);
        }
    }

    private void simulateReplication(ReplicatedEntry entry, String targetDcId) {
        // 模拟网络延迟
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 接收来自其他数据中心的复制数据
     */
    public void receiveReplication(ReplicatedEntry remoteEntry) {
        String key = remoteEntry.getKey();
        ReplicatedEntry localEntry = localStore.get(key);
        
        if (localEntry == null) {
            localStore.put(key, remoteEntry);
        } else {
            ReplicatedEntry resolved = resolveConflict(localEntry, remoteEntry);
            localStore.put(key, resolved);
        }
        
        versionVectors.put(key, Math.max(
                versionVectors.getOrDefault(key, 0L),
                remoteEntry.getVersion()));
    }

    private ReplicatedEntry resolveConflict(ReplicatedEntry local, ReplicatedEntry remote) {
        int strategy = properties.getMultiActiveDataCenter().getConflictResolutionStrategy();
        
        ReplicatedEntry winner;
        switch (strategy) {
            case 1: // Last Write Wins
                winner = local.getVersion() > remote.getVersion() ? local : remote;
                break;
            case 2: // Version Vector
                winner = compareVersionVectors(local, remote) > 0 ? local : remote;
                break;
            case 3: // Custom (合并)
                winner = mergeEntries(local, remote);
                break;
            default:
                winner = local.getVersion() > remote.getVersion() ? local : remote;
        }
        
        if (!winner.equals(local) || !winner.equals(remote)) {
            conflictsResolved.increment();
            log.debug("解决冲突: key={}, winner={}", local.getKey(), winner.getSourceDcId());
        }
        
        return winner;
    }

    private int compareVersionVectors(ReplicatedEntry e1, ReplicatedEntry e2) {
        // 简化版本向量比较
        return Long.compare(e1.getVersion(), e2.getVersion());
    }

    private ReplicatedEntry mergeEntries(ReplicatedEntry local, ReplicatedEntry remote) {
        // 简化合并：选择较新的
        return local.getVersion() > remote.getVersion() ? local : remote;
    }

    // ==================== 脑裂保护 ====================

    private boolean confirmLeaderHasLatest(String key) {
        if (!properties.getMultiActiveDataCenter().isSplitBrainProtectionEnabled()) {
            return true;
        }
        
        String leader = currentLeader.get();
        if (leader == null) {
            return false;
        }
        
        // 检查是否能联系到quorum个节点
        int reachable = 1; // 自己
        for (DataCenterNode node : dataCenters.values()) {
            if (!node.getId().equals(localDataCenterId) && node.getStatus() == NodeStatus.ACTIVE) {
                reachable++;
            }
        }
        
        return reachable >= properties.getMultiActiveDataCenter().getQuorumSize();
    }

    private void appendLogEntry(LogEntry entry) {
        raftLog.add(entry);
        
        // 尝试提交
        tryCommitEntries();
    }

    private void tryCommitEntries() {
        if (raftState != RaftState.LEADER) {
            return;
        }
        
        int quorum = properties.getMultiActiveDataCenter().getQuorumSize();
        
        for (long n = commitIndex + 1; n < raftLog.size(); n++) {
            if (raftLog.get((int) n).getTerm() != currentTerm.get()) {
                continue;
            }
            
            int count = 1; // 自己
            for (Long matched : matchIndex.values()) {
                if (matched >= n) {
                    count++;
                }
            }
            
            if (count >= quorum) {
                commitIndex = n;
            }
        }
        
        // 应用已提交的条目
        applyCommittedEntries();
    }

    private void applyCommittedEntries() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = raftLog.get((int) lastApplied);
            applyLogEntry(entry);
        }
    }

    private void applyLogEntry(LogEntry entry) {
        switch (entry.getOperation()) {
            case PUT:
                ReplicatedEntry re = new ReplicatedEntry(
                        entry.getKey(), entry.getValue(), 
                        System.currentTimeMillis(), localDataCenterId, -1);
                localStore.put(entry.getKey(), re);
                break;
            case DELETE:
                localStore.remove(entry.getKey());
                break;
        }
    }

    // ==================== 同步检查 ====================

    private void syncCheck() {
        // 检查复制延迟
        long maxLag = properties.getMultiActiveDataCenter().getMaxReplicationLagMs();
        long now = System.currentTimeMillis();
        
        for (ReplicatedEntry entry : localStore.values()) {
            if (!entry.isDeleted() && now - entry.getVersion() > maxLag * 10) {
                // 数据太旧，触发重新同步
                log.debug("数据过期，需要同步: key={}", entry.getKey());
            }
        }
    }

    // ==================== 辅助方法 ====================

    private String generateDataCenterId() {
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((hostname + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
            return String.format("DC-%02x%02x%02x%02x", 
                    digest[0] & 0xff, digest[1] & 0xff, digest[2] & 0xff, digest[3] & 0xff);
        } catch (Exception e) {
            return "DC-" + System.nanoTime();
        }
    }

    // ==================== 状态查询 ====================

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("localDcId", localDataCenterId);
        status.put("raftState", raftState);
        status.put("currentTerm", currentTerm.get());
        status.put("currentLeader", currentLeader.get());
        status.put("commitIndex", commitIndex);
        status.put("lastApplied", lastApplied);
        status.put("logSize", raftLog.size());
        status.put("dataCount", localStore.size());
        status.put("replicationQueueSize", replicationQueue.size());
        
        Map<String, Object> dcStatus = new LinkedHashMap<>();
        for (DataCenterNode node : dataCenters.values()) {
            dcStatus.put(node.getId(), Map.of(
                    "status", node.getStatus(),
                    "nextIndex", nextIndex.getOrDefault(node.getId(), 0L),
                    "matchIndex", matchIndex.getOrDefault(node.getId(), 0L)
            ));
        }
        status.put("dataCenters", dcStatus);
        
        return status;
    }

    public String getLocalDataCenterId() {
        return localDataCenterId;
    }

    public boolean isLeader() {
        return raftState == RaftState.LEADER;
    }

    // ==================== 内部类 ====================

    public enum RaftState {
        FOLLOWER, CANDIDATE, LEADER
    }

    public enum NodeStatus {
        ACTIVE, INACTIVE, SYNCING
    }

    public enum ConsistencyLevel {
        EVENTUAL, STRONG, LINEARIZABLE
    }

    public enum LogOperation {
        PUT, DELETE
    }

    private static class DataCenterNode {
        private final String id;
        private final String host;
        private final int port;
        private volatile NodeStatus status;
        private volatile long lastSeen;

        DataCenterNode(String id, String host, int port) {
            this.id = id;
            this.host = host;
            this.port = port;
            this.status = NodeStatus.INACTIVE;
            this.lastSeen = System.currentTimeMillis();
        }

        String getId() { return id; }
        String getHost() { return host; }
        int getPort() { return port; }
        NodeStatus getStatus() { return status; }
        void setStatus(NodeStatus status) { this.status = status; }
        long getLastSeen() { return lastSeen; }
        void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    }

    private static class ReplicatedEntry {
        private final String key;
        private final Object value;
        private final long version;
        private final String sourceDcId;
        private final long ttlMs;
        private final long createTime;
        private volatile boolean deleted;

        ReplicatedEntry(String key, Object value, long version, String sourceDcId, long ttlMs) {
            this.key = key;
            this.value = value;
            this.version = version;
            this.sourceDcId = sourceDcId;
            this.ttlMs = ttlMs;
            this.createTime = System.currentTimeMillis();
            this.deleted = false;
        }

        String getKey() { return key; }
        Object getValue() { return value; }
        long getVersion() { return version; }
        String getSourceDcId() { return sourceDcId; }
        boolean isDeleted() { return deleted; }
        void setDeleted(boolean deleted) { this.deleted = deleted; }
        
        boolean isExpired() {
            if (ttlMs <= 0) return false;
            return System.currentTimeMillis() - createTime > ttlMs;
        }
    }

    private static class ReplicationTask {
        private final ReplicatedEntry entry;
        private final String targetDcId;
        private final long createTime;

        ReplicationTask(ReplicatedEntry entry, String targetDcId) {
            this.entry = entry;
            this.targetDcId = targetDcId;
            this.createTime = System.currentTimeMillis();
        }

        ReplicatedEntry getEntry() { return entry; }
        String getTargetDcId() { return targetDcId; }
        long getCreateTime() { return createTime; }
    }

    private static class LogEntry {
        private final long term;
        private final String key;
        private final Object value;
        private final LogOperation operation;

        LogEntry(long term, String key, Object value, LogOperation operation) {
            this.term = term;
            this.key = key;
            this.value = value;
            this.operation = operation;
        }

        long getTerm() { return term; }
        String getKey() { return key; }
        Object getValue() { return value; }
        LogOperation getOperation() { return operation; }
    }
}
