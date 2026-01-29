package com.ecommerce.cache.optimization.v8;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Gossip协议集群通信引擎
 * 
 * 核心特性:
 * 1. 去中心化: 无需中心节点
 * 2. 最终一致性: 信息最终传播到所有节点
 * 3. 故障检测: 自动检测节点故障
 * 4. 消息广播: 高效的消息传播
 * 5. 成员管理: 动态节点加入/离开
 * 6. 压缩传输: 减少网络开销
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GossipProtocolEngine {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV8Properties properties;
    
    // ========== 集群状态 ==========
    
    /** 本地节点信息 */
    private GossipNode localNode;
    
    /** 已知节点列表 */
    private final ConcurrentMap<String, GossipNode> members = new ConcurrentHashMap<>();
    
    /** 消息缓冲(防重复) */
    private final ConcurrentMap<String, Long> messageCache = new ConcurrentHashMap<>();
    
    /** 消息监听器 */
    private final List<GossipMessageListener> listeners = new CopyOnWriteArrayList<>();
    
    /** UDP套接字 */
    private DatagramSocket socket;
    
    /** 执行器 */
    private ScheduledExecutorService scheduler;
    private ExecutorService receiverExecutor;
    
    /** 统计 */
    private final AtomicLong sentCount = new AtomicLong(0);
    private final AtomicLong receivedCount = new AtomicLong(0);
    private final AtomicLong gossipRounds = new AtomicLong(0);
    
    /** 运行状态 */
    private volatile boolean running = false;
    
    // ========== 常量 ==========
    
    private static final int MAX_MESSAGE_SIZE = 65507;
    private static final int MESSAGE_CACHE_TTL = 60000;
    
    // ========== 指标 ==========
    
    private Counter sentCounter;
    private Counter receivedCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getGossip().isEnabled()) {
            log.info("[Gossip协议] 已禁用");
            return;
        }
        
        try {
            int port = properties.getGossip().getPort();
            socket = new DatagramSocket(port);
            
            // 初始化本地节点
            localNode = new GossipNode(
                UUID.randomUUID().toString(),
                InetAddress.getLocalHost().getHostAddress(),
                port,
                System.currentTimeMillis(),
                NodeState.ALIVE
            );
            members.put(localNode.id, localNode);
            
            scheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "gossip-scheduler");
                t.setDaemon(true);
                return t;
            });
            
            receiverExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "gossip-receiver");
                t.setDaemon(true);
                return t;
            });
            
            initMetrics();
            
            running = true;
            
            // 启动接收线程
            receiverExecutor.submit(this::receiveLoop);
            
            // 启动Gossip循环
            int interval = properties.getGossip().getIntervalMs();
            scheduler.scheduleWithFixedDelay(
                this::gossipRound,
                interval,
                interval,
                TimeUnit.MILLISECONDS
            );
            
            // 启动节点检查
            scheduler.scheduleWithFixedDelay(
                this::checkNodes,
                5000,
                5000,
                TimeUnit.MILLISECONDS
            );
            
            // 启动消息缓存清理
            scheduler.scheduleWithFixedDelay(
                this::cleanMessageCache,
                30000,
                30000,
                TimeUnit.MILLISECONDS
            );
            
            // 连接种子节点
            joinSeeds();
            
            log.info("[Gossip协议] 初始化完成 - 本地节点: {}:{}", 
                localNode.address, localNode.port);
            
        } catch (Exception e) {
            log.error("[Gossip协议] 初始化失败", e);
        }
    }
    
    @PreDestroy
    public void shutdown() {
        running = false;
        
        // 广播离开消息
        broadcastLeave();
        
        if (socket != null) {
            socket.close();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (receiverExecutor != null) {
            receiverExecutor.shutdown();
        }
        
        log.info("[Gossip协议] 已关闭 - 发送: {}, 接收: {}, 轮次: {}",
            sentCount.get(), receivedCount.get(), gossipRounds.get());
    }
    
    private void initMetrics() {
        sentCounter = Counter.builder("cache.gossip.sent")
            .description("Gossip消息发送次数")
            .register(meterRegistry);
        
        receivedCounter = Counter.builder("cache.gossip.received")
            .description("Gossip消息接收次数")
            .register(meterRegistry);
    }
    
    // ========== 核心API ==========
    
    /**
     * 广播消息
     */
    public void broadcast(String type, byte[] payload) {
        GossipMessage message = new GossipMessage(
            UUID.randomUUID().toString(),
            type,
            localNode.id,
            payload,
            properties.getGossip().getMessageTtl(),
            System.currentTimeMillis()
        );
        
        spreadMessage(message);
    }
    
    /**
     * 广播字符串消息
     */
    public void broadcast(String type, String content) {
        broadcast(type, content.getBytes());
    }
    
    /**
     * 发送点对点消息
     */
    public boolean sendTo(String nodeId, String type, byte[] payload) {
        GossipNode target = members.get(nodeId);
        if (target == null) {
            return false;
        }
        
        GossipMessage message = new GossipMessage(
            UUID.randomUUID().toString(),
            type,
            localNode.id,
            payload,
            1,
            System.currentTimeMillis()
        );
        
        return sendDirect(target, message);
    }
    
    /**
     * 注册消息监听器
     */
    public void addListener(GossipMessageListener listener) {
        listeners.add(listener);
    }
    
    /**
     * 移除监听器
     */
    public void removeListener(GossipMessageListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * 获取所有成员
     */
    public List<GossipNode> getMembers() {
        return new ArrayList<>(members.values());
    }
    
    /**
     * 获取存活成员
     */
    public List<GossipNode> getAliveMembers() {
        return members.values().stream()
            .filter(n -> n.state == NodeState.ALIVE)
            .toList();
    }
    
    /**
     * 获取成员数量
     */
    public int getMemberCount() {
        return members.size();
    }
    
    /**
     * 加入集群
     */
    public void join(String address, int port) {
        GossipNode seed = new GossipNode(
            address + ":" + port,
            address,
            port,
            System.currentTimeMillis(),
            NodeState.UNKNOWN
        );
        
        // 发送加入消息
        GossipMessage joinMsg = new GossipMessage(
            UUID.randomUUID().toString(),
            "JOIN",
            localNode.id,
            serializeNode(localNode),
            1,
            System.currentTimeMillis()
        );
        
        sendDirect(seed, joinMsg);
    }
    
    /**
     * 离开集群
     */
    public void leave() {
        broadcastLeave();
        members.clear();
        members.put(localNode.id, localNode);
    }
    
    // ========== Gossip核心逻辑 ==========
    
    private void gossipRound() {
        if (!running || members.size() <= 1) {
            return;
        }
        
        gossipRounds.incrementAndGet();
        
        // 选择随机节点
        List<GossipNode> targets = selectRandomNodes(properties.getGossip().getFanout());
        
        // 准备成员信息
        byte[] memberData = serializeMembers();
        
        GossipMessage gossip = new GossipMessage(
            UUID.randomUUID().toString(),
            "GOSSIP",
            localNode.id,
            memberData,
            1,
            System.currentTimeMillis()
        );
        
        // 发送给选中的节点
        for (GossipNode target : targets) {
            sendDirect(target, gossip);
        }
    }
    
    private List<GossipNode> selectRandomNodes(int count) {
        List<GossipNode> others = members.values().stream()
            .filter(n -> !n.id.equals(localNode.id) && n.state == NodeState.ALIVE)
            .toList();
        
        if (others.size() <= count) {
            return new ArrayList<>(others);
        }
        
        List<GossipNode> selected = new ArrayList<>(others);
        Collections.shuffle(selected);
        return selected.subList(0, count);
    }
    
    private void spreadMessage(GossipMessage message) {
        if (message.ttl <= 0) {
            return;
        }
        
        // 缓存消息ID防重复
        if (messageCache.putIfAbsent(message.id, System.currentTimeMillis()) != null) {
            return;
        }
        
        // 选择传播目标
        List<GossipNode> targets = selectRandomNodes(properties.getGossip().getFanout());
        
        // 递减TTL后发送
        GossipMessage forward = new GossipMessage(
            message.id,
            message.type,
            message.sourceId,
            message.payload,
            message.ttl - 1,
            message.timestamp
        );
        
        for (GossipNode target : targets) {
            sendDirect(target, forward);
        }
    }
    
    private boolean sendDirect(GossipNode target, GossipMessage message) {
        try {
            byte[] data = serializeMessage(message);
            
            // 压缩
            if (properties.getGossip().isCompressionEnabled()) {
                data = compress(data);
            }
            
            DatagramPacket packet = new DatagramPacket(
                data,
                data.length,
                InetAddress.getByName(target.address),
                target.port
            );
            
            socket.send(packet);
            sentCount.incrementAndGet();
            sentCounter.increment();
            
            return true;
        } catch (Exception e) {
            log.warn("[Gossip协议] 发送失败: {} -> {}", target.id, e.getMessage());
            return false;
        }
    }
    
    private void receiveLoop() {
        byte[] buffer = new byte[MAX_MESSAGE_SIZE];
        
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                
                // 解压
                if (properties.getGossip().isCompressionEnabled()) {
                    data = decompress(data);
                }
                
                GossipMessage message = deserializeMessage(data);
                handleMessage(message, packet.getAddress().getHostAddress(), packet.getPort());
                
                receivedCount.incrementAndGet();
                receivedCounter.increment();
                
            } catch (SocketException e) {
                if (running) {
                    log.warn("[Gossip协议] Socket异常: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.warn("[Gossip协议] 接收异常: {}", e.getMessage());
            }
        }
    }
    
    private void handleMessage(GossipMessage message, String fromAddress, int fromPort) {
        // 检查重复
        if (messageCache.putIfAbsent(message.id, System.currentTimeMillis()) != null) {
            return;
        }
        
        switch (message.type) {
            case "JOIN":
                handleJoin(message, fromAddress, fromPort);
                break;
            case "LEAVE":
                handleLeave(message);
                break;
            case "GOSSIP":
                handleGossip(message);
                break;
            case "PING":
                handlePing(message, fromAddress, fromPort);
                break;
            case "ACK":
                handleAck(message);
                break;
            default:
                // 用户自定义消息
                notifyListeners(message);
                
                // 继续传播
                if (message.ttl > 0) {
                    spreadMessage(message);
                }
        }
    }
    
    private void handleJoin(GossipMessage message, String fromAddress, int fromPort) {
        GossipNode node = deserializeNode(message.payload);
        if (node != null) {
            node.state = NodeState.ALIVE;
            node.lastSeen = System.currentTimeMillis();
            members.put(node.id, node);
            
            // 回复成员列表
            byte[] memberData = serializeMembers();
            GossipMessage reply = new GossipMessage(
                UUID.randomUUID().toString(),
                "GOSSIP",
                localNode.id,
                memberData,
                1,
                System.currentTimeMillis()
            );
            
            GossipNode replyTarget = new GossipNode(node.id, fromAddress, fromPort, 
                System.currentTimeMillis(), NodeState.ALIVE);
            sendDirect(replyTarget, reply);
            
            log.info("[Gossip协议] 新节点加入: {}", node.id);
        }
    }
    
    private void handleLeave(GossipMessage message) {
        GossipNode node = deserializeNode(message.payload);
        if (node != null) {
            members.remove(node.id);
            log.info("[Gossip协议] 节点离开: {}", node.id);
        }
    }
    
    private void handleGossip(GossipMessage message) {
        List<GossipNode> nodes = deserializeMembers(message.payload);
        for (GossipNode node : nodes) {
            if (node.id.equals(localNode.id)) {
                continue;
            }
            
            GossipNode existing = members.get(node.id);
            if (existing == null || node.lastSeen > existing.lastSeen) {
                node.state = NodeState.ALIVE;
                members.put(node.id, node);
            }
        }
    }
    
    private void handlePing(GossipMessage message, String fromAddress, int fromPort) {
        // 回复ACK
        GossipMessage ack = new GossipMessage(
            UUID.randomUUID().toString(),
            "ACK",
            localNode.id,
            message.id.getBytes(),
            1,
            System.currentTimeMillis()
        );
        
        GossipNode target = members.get(message.sourceId);
        if (target == null) {
            target = new GossipNode(message.sourceId, fromAddress, fromPort,
                System.currentTimeMillis(), NodeState.ALIVE);
        }
        
        sendDirect(target, ack);
    }
    
    private void handleAck(GossipMessage message) {
        GossipNode node = members.get(message.sourceId);
        if (node != null) {
            node.state = NodeState.ALIVE;
            node.lastSeen = System.currentTimeMillis();
        }
    }
    
    private void notifyListeners(GossipMessage message) {
        for (GossipMessageListener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (Exception e) {
                log.warn("[Gossip协议] 监听器处理失败", e);
            }
        }
    }
    
    private void checkNodes() {
        long now = System.currentTimeMillis();
        int timeout = properties.getGossip().getNodeTimeoutSec() * 1000;
        
        for (GossipNode node : members.values()) {
            if (node.id.equals(localNode.id)) {
                continue;
            }
            
            if (now - node.lastSeen > timeout) {
                if (node.state == NodeState.ALIVE) {
                    node.state = NodeState.SUSPECT;
                    log.warn("[Gossip协议] 节点可疑: {}", node.id);
                } else if (node.state == NodeState.SUSPECT) {
                    node.state = NodeState.DEAD;
                    log.warn("[Gossip协议] 节点失效: {}", node.id);
                }
            }
        }
        
        // 清理死亡节点
        members.entrySet().removeIf(e -> 
            e.getValue().state == NodeState.DEAD && 
            now - e.getValue().lastSeen > timeout * 3);
    }
    
    private void cleanMessageCache() {
        long now = System.currentTimeMillis();
        messageCache.entrySet().removeIf(e -> now - e.getValue() > MESSAGE_CACHE_TTL);
    }
    
    private void joinSeeds() {
        for (String seed : properties.getGossip().getSeeds()) {
            try {
                String[] parts = seed.split(":");
                if (parts.length == 2) {
                    join(parts[0], Integer.parseInt(parts[1]));
                }
            } catch (Exception e) {
                log.warn("[Gossip协议] 连接种子节点失败: {}", seed);
            }
        }
    }
    
    private void broadcastLeave() {
        GossipMessage leave = new GossipMessage(
            UUID.randomUUID().toString(),
            "LEAVE",
            localNode.id,
            serializeNode(localNode),
            properties.getGossip().getMessageTtl(),
            System.currentTimeMillis()
        );
        
        spreadMessage(leave);
    }
    
    // ========== 序列化方法 ==========
    
    private byte[] serializeMessage(GossipMessage message) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(message.id);
        dos.writeUTF(message.type);
        dos.writeUTF(message.sourceId);
        dos.writeInt(message.ttl);
        dos.writeLong(message.timestamp);
        dos.writeInt(message.payload.length);
        dos.write(message.payload);
        return baos.toByteArray();
    }
    
    private GossipMessage deserializeMessage(byte[] data) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        return new GossipMessage(
            dis.readUTF(),
            dis.readUTF(),
            dis.readUTF(),
            readBytes(dis),
            dis.readInt(),
            dis.readLong()
        );
    }
    
    private byte[] readBytes(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return bytes;
    }
    
    private byte[] serializeNode(GossipNode node) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeUTF(node.id);
            dos.writeUTF(node.address);
            dos.writeInt(node.port);
            dos.writeLong(node.lastSeen);
            dos.writeUTF(node.state.name());
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
    
    private GossipNode deserializeNode(byte[] data) {
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
            return new GossipNode(
                dis.readUTF(),
                dis.readUTF(),
                dis.readInt(),
                dis.readLong(),
                NodeState.valueOf(dis.readUTF())
            );
        } catch (Exception e) {
            return null;
        }
    }
    
    private byte[] serializeMembers() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(members.size());
            for (GossipNode node : members.values()) {
                byte[] nodeData = serializeNode(node);
                dos.writeInt(nodeData.length);
                dos.write(nodeData);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
    
    private List<GossipNode> deserializeMembers(byte[] data) {
        List<GossipNode> nodes = new ArrayList<>();
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                int len = dis.readInt();
                byte[] nodeData = new byte[len];
                dis.readFully(nodeData);
                GossipNode node = deserializeNode(nodeData);
                if (node != null) {
                    nodes.add(node);
                }
            }
        } catch (Exception e) {
            log.warn("[Gossip协议] 反序列化成员失败");
        }
        return nodes;
    }
    
    private byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        return baos.toByteArray();
    }
    
    private byte[] decompress(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        try (GZIPInputStream gzip = new GZIPInputStream(bais)) {
            return gzip.readAllBytes();
        }
    }
    
    // ========== 统计信息 ==========
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("localNodeId", localNode != null ? localNode.id : "N/A");
        stats.put("memberCount", members.size());
        stats.put("aliveMemberCount", getAliveMembers().size());
        stats.put("sentCount", sentCount.get());
        stats.put("receivedCount", receivedCount.get());
        stats.put("gossipRounds", gossipRounds.get());
        stats.put("messageCacheSize", messageCache.size());
        stats.put("running", running);
        
        // 成员列表
        List<Map<String, Object>> memberList = new ArrayList<>();
        for (GossipNode node : members.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", node.id);
            m.put("address", node.address + ":" + node.port);
            m.put("state", node.state.name());
            m.put("lastSeen", node.lastSeen);
            memberList.add(m);
        }
        stats.put("members", memberList);
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    public enum NodeState {
        UNKNOWN, ALIVE, SUSPECT, DEAD
    }
    
    @Data
    public static class GossipNode {
        private final String id;
        private final String address;
        private final int port;
        private long lastSeen;
        private NodeState state;
        
        GossipNode(String id, String address, int port, long lastSeen, NodeState state) {
            this.id = id;
            this.address = address;
            this.port = port;
            this.lastSeen = lastSeen;
            this.state = state;
        }
    }
    
    @Data
    public static class GossipMessage {
        private final String id;
        private final String type;
        private final String sourceId;
        private final byte[] payload;
        private final int ttl;
        private final long timestamp;
    }
    
    public interface GossipMessageListener {
        void onMessage(GossipMessage message);
    }
}
