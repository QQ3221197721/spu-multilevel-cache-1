package com.ecommerce.cache.optimization.v15;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * V15生物神经元缓存引擎
 * 基于生物神经网络原理的自学习缓存系统
 */
@Component
public class BioNeuralNetworkCacheEngine {

    private static final Logger log = LoggerFactory.getLogger(BioNeuralNetworkCacheEngine.class);

    private final OptimizationV15Properties properties;
    private final MeterRegistry meterRegistry;

    // 神经元网络
    private final ConcurrentMap<String, Neuron> neuralNetwork = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Synapse> synapses = new ConcurrentHashMap<>();
    
    // 突触可塑性
    private final ConcurrentMap<String, Double> synapticWeights = new ConcurrentHashMap<>();
    
    // 长期增强(LTP)
    private final ConcurrentMap<String, LongTermMemory> longTermMemory = new ConcurrentHashMap<>();
    
    // 神经路径
    private final ConcurrentMap<String, List<String>> neuralPaths = new ConcurrentHashMap<>();
    
    // 统计信息
    private final AtomicLong totalNeuralActivations = new AtomicLong(0);
    private final AtomicLong totalLearningCycles = new AtomicLong(0);
    private final AtomicLong totalMemoryConsolidations = new AtomicLong(0);
    
    // 读写锁
    private final ReadWriteLock networkLock = new ReentrantReadWriteLock();
    
    // 执行器
    private ScheduledExecutorService bioScheduler;
    private ExecutorService neuralExecutor;
    
    // 指标
    private Counter neuralActivations;
    private Counter learningCycles;
    private Counter memoryConsolidations;
    private Timer neuralAccessLatency;

    public BioNeuralNetworkCacheEngine(OptimizationV15Properties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        if (!properties.getBioNeuralNetworkCache().isEnabled()) {
            log.info("V15生物神经元缓存引擎已禁用");
            return;
        }

        initializeMetrics();
        initializeExecutors();
        initializeNeuralNetwork();
        startBackgroundTasks();

        log.info("V15生物神经元缓存引擎初始化完成 - 神经元: {}, 突触: {}",
                properties.getBioNeuralNetworkCache().getNeuronCount(),
                properties.getBioNeuralNetworkCache().getSynapseCount());
    }

    private void initializeMetrics() {
        neuralActivations = Counter.builder("v15.bio.neural.activations")
                .description("神经元激活次数").register(meterRegistry);
        learningCycles = Counter.builder("v15.bio.learning.cycles")
                .description("学习循环次数").register(meterRegistry);
        memoryConsolidations = Counter.builder("v15.bio.memory.consolidations")
                .description("记忆巩固次数").register(meterRegistry);
        neuralAccessLatency = Timer.builder("v15.bio.access.latency")
                .description("神经访问延迟").register(meterRegistry);
        
        Gauge.builder("v15.bio.neuron.count", neuralNetwork, map -> map.size())
                .description("神经元数量").register(meterRegistry);
    }

    private void initializeExecutors() {
        bioScheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "v15-bio-scheduler");
            t.setDaemon(true);
            return t;
        });
        neuralExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private void initializeNeuralNetwork() {
        // 初始化基本神经元结构
        for (int i = 0; i < Math.min(1000, properties.getBioNeuralNetworkCache().getNeuronCount()); i++) {
            String neuronId = "neuron_" + i;
            Neuron neuron = new Neuron(neuronId);
            neuralNetwork.put(neuronId, neuron);
        }
        
        // 创建初始突触连接
        for (int i = 0; i < Math.min(10000, properties.getBioNeuralNetworkCache().getSynapseCount()); i++) {
            String synapseId = "synapse_" + i;
            String fromNeuron = "neuron_" + (i % neuralNetwork.size());
            String toNeuron = "neuron_" + ((i + 1) % neuralNetwork.size());
            
            Synapse synapse = new Synapse(synapseId, fromNeuron, toNeuron);
            synapses.put(synapseId, synapse);
            synapticWeights.put(synapseId, 0.5); // 初始权重
        }
    }

    private void startBackgroundTasks() {
        // 神经活动维持
        bioScheduler.scheduleAtFixedRate(this::maintainNeuralActivity, 1, 1, TimeUnit.MILLISECONDS);
        
        // 突触可塑性调整
        if (properties.getBioNeuralNetworkCache().isPlasticityEnabled()) {
            bioScheduler.scheduleAtFixedRate(this::adjustSynapticPlasticity, 
                    10, 10, TimeUnit.MILLISECONDS);
        }
        
        // 记忆巩固
        bioScheduler.scheduleAtFixedRate(this::performMemoryConsolidation, 
                properties.getBioNeuralNetworkCache().getMemoryConsolidationCycles(),
                properties.getBioNeuralNetworkCache().getMemoryConsolidationCycles(),
                TimeUnit.MILLISECONDS);
        
        // 神经网络学习
        bioScheduler.scheduleAtFixedRate(this::performLearningCycle, 100, 100, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (bioScheduler != null) bioScheduler.shutdown();
        if (neuralExecutor != null) neuralExecutor.shutdown();
    }

    // ==================== 生物神经缓存API ====================

    /**
     * 创建神经元连接
     */
    public String createNeuralConnection(String key, Object value) {
        return neuralAccessLatency.record(() -> {
            String neuronId = generateNeuronId(key);
            
            Neuron neuron = neuralNetwork.computeIfAbsent(neuronId, Neuron::new);
            neuron.setValue(value);
            neuron.setLastAccessed(System.currentTimeMillis());
            
            // 创建相关的突触连接
            createSynapticConnections(neuronId, key);
            
            log.debug("创建神经元连接: key={}, neuron={}, value_type={}", 
                    key, neuronId, value != null ? value.getClass().getSimpleName() : "null");
            
            return neuronId;
        });
    }

    /**
     * 神经元存储
     */
    public void storeInNeuron(String key, Object value) {
        neuralAccessLatency.record(() -> {
            networkLock.writeLock().lock();
            try {
                String neuronId = findOrCreateNeuron(key);
                Neuron neuron = neuralNetwork.get(neuronId);
                
                if (neuron != null) {
                    neuron.setValue(value);
                    neuron.setLastAccessed(System.currentTimeMillis());
                    neuron.incrementActivationCount();
                    
                    // 激活相关神经路径
                    activateNeuralPath(neuronId, key);
                    
                    totalNeuralActivations.incrementAndGet();
                    neuralActivations.increment();
                    
                    // 学习：调整突触权重
                    updateSynapticWeights(neuronId, 0.1);
                }
                
            } finally {
                networkLock.writeLock().unlock();
            }
        });
    }

    /**
     * 神经元检索
     */
    public <T> T retrieveFromNeuron(String key, Class<T> type) {
        return neuralAccessLatency.record(() -> {
            networkLock.readLock().lock();
            try {
                String neuronId = findNeuron(key);
                if (neuronId == null) {
                    return null;
                }
                
                Neuron neuron = neuralNetwork.get(neuronId);
                if (neuron != null) {
                    neuron.setLastAccessed(System.currentTimeMillis());
                    neuron.incrementActivationCount();
                    
                    // 激活神经路径
                    activateNeuralPath(neuronId, key);
                    
                    totalNeuralActivations.incrementAndGet();
                    neuralActivations.increment();
                    
                    // 学习：调整突触权重
                    updateSynapticWeights(neuronId, 0.05);
                    
                    return type.cast(neuron.getValue());
                }
                
                return null;
                
            } finally {
                networkLock.readLock().unlock();
            }
        });
    }

    /**
     * 长期记忆存储
     */
    public void storeInLongTermMemory(String key, Object value) {
        neuralAccessLatency.record(() -> {
            LongTermMemory ltm = new LongTermMemory(key, value, System.currentTimeMillis());
            longTermMemory.put(key, ltm);
            
            // 通过反复激活将其转化为长期记忆
            for (int i = 0; i < 5; i++) { // 模拟重复激活
                storeInNeuron(key, value);
            }
            
            log.debug("存储到长期记忆: key={}", key);
        });
    }

    /**
     * 长期记忆检索
     */
    public <T> T retrieveFromLongTermMemory(String key, Class<T> type) {
        LongTermMemory ltm = longTermMemory.get(key);
        if (ltm != null) {
            ltm.setLastAccessed(System.currentTimeMillis());
            return type.cast(ltm.getValue());
        }
        return null;
    }

    /**
     * 神经路径查找
     */
    public List<String> findNeuralPath(String startKey, String endKey) {
        String startNeuron = findNeuron(startKey);
        String endNeuron = findNeuron(endKey);
        
        if (startNeuron == null || endNeuron == null) {
            return Collections.emptyList();
        }
        
        // 简化的路径查找算法
        return neuralPaths.computeIfAbsent(startNeuron + "->" + endNeuron, 
                k -> findShortestPath(startNeuron, endNeuron));
    }

    /**
     * 神经网络学习
     */
    public void learnFromAccessPattern(List<String> accessSequence) {
        neuralAccessLatency.record(() -> {
            for (int i = 0; i < accessSequence.size() - 1; i++) {
                String currentKey = accessSequence.get(i);
                String nextKey = accessSequence.get(i + 1);
                
                String currentNeuron = findOrCreateNeuron(currentKey);
                String nextNeuron = findOrCreateNeuron(nextKey);
                
                // 增加强化学习信号
                String synapseId = currentNeuron + "->" + nextNeuron;
                double currentWeight = synapticWeights.getOrDefault(synapseId, 0.5);
                synapticWeights.put(synapseId, Math.min(1.0, currentWeight + 0.1));
            }
            
            totalLearningCycles.incrementAndGet();
            learningCycles.increment();
        });
    }

    // ==================== 神经网络操作 ====================

    private String findOrCreateNeuron(String key) {
        String neuronId = generateNeuronId(key);
        return neuralNetwork.computeIfAbsent(neuronId, Neuron::new).getId();
    }

    private String findNeuron(String key) {
        String neuronId = generateNeuronId(key);
        return neuralNetwork.containsKey(neuronId) ? neuronId : null;
    }

    private String generateNeuronId(String key) {
        return "neuron_" + key.hashCode() % properties.getBioNeuralNetworkCache().getNeuronCount();
    }

    private void createSynapticConnections(String neuronId, String key) {
        // 创建与相关神经元的突触连接
        for (int i = 0; i < 5; i++) { // 创建5个随机连接
            String connectedNeuron = "neuron_" + (Math.abs(neuronId.hashCode() + i) % neuralNetwork.size());
            if (!connectedNeuron.equals(neuronId)) {
                String synapseId = neuronId + "->" + connectedNeuron;
                if (!synapses.containsKey(synapseId)) {
                    Synapse synapse = new Synapse(synapseId, neuronId, connectedNeuron);
                    synapses.put(synapseId, synapse);
                    synapticWeights.put(synapseId, 0.3); // 初始较低权重
                }
            }
        }
    }

    private void activateNeuralPath(String neuronId, String key) {
        // 激活从输入到输出的神经路径
        List<String> path = new ArrayList<>();
        path.add(neuronId);
        
        // 随机选择下一个神经元（基于突触权重）
        String current = neuronId;
        for (int i = 0; i < properties.getBioNeuralNetworkCache().getNeuralPathwayDepth(); i++) {
            String next = getNextNeuronInPath(current);
            if (next != null) {
                path.add(next);
                current = next;
            } else {
                break;
            }
        }
        
        neuralPaths.put(key, path);
    }

    private String getNextNeuronInPath(String currentNeuron) {
        // 基于突触权重选择下一个神经元
        List<Map.Entry<String, Double>> candidates = synapses.entrySet().stream()
                .filter(entry -> entry.getValue().getFromNeuron().equals(currentNeuron))
                .map(entry -> Map.entry(entry.getValue().getToNeuron(), 
                        synapticWeights.getOrDefault(entry.getKey(), 0.0)))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(3) // 选择权重最高的3个候选
                .toList();
        
        if (candidates.isEmpty()) {
            return null;
        }
        
        // 随机选择（偏向权重高的）
        Random random = new Random();
        double totalWeight = candidates.stream().mapToDouble(Map.Entry::getValue).sum();
        double choice = random.nextDouble() * totalWeight;
        
        double cumulative = 0;
        for (Map.Entry<String, Double> candidate : candidates) {
            cumulative += candidate.getValue();
            if (choice <= cumulative) {
                return candidate.getKey();
            }
        }
        
        return candidates.get(0).getKey();
    }

    private List<String> findShortestPath(String start, String end) {
        // 简化的最短路径查找
        List<String> path = new ArrayList<>();
        path.add(start);
        
        String current = start;
        for (int i = 0; i < 10 && !current.equals(end); i++) {
            String next = getNextNeuronInPath(current);
            if (next != null) {
                path.add(next);
                current = next;
            } else {
                break;
            }
        }
        
        if (!path.contains(end)) {
            path.add(end); // 直接添加终点
        }
        
        return path;
    }

    private void updateSynapticWeights(String neuronId, double learningRate) {
        // Hebbian学习规则："一起激活的神经元会连接在一起"
        synapses.entrySet().stream()
                .filter(entry -> entry.getValue().getFromNeuron().equals(neuronId))
                .forEach(entry -> {
                    String synapseId = entry.getKey();
                    double currentWeight = synapticWeights.getOrDefault(synapseId, 0.5);
                    double newWeight = currentWeight + learningRate * (1.0 - currentWeight);
                    synapticWeights.put(synapseId, Math.min(1.0, newWeight));
                });
    }

    private void maintainNeuralActivity() {
        // 维持神经元的基本活动水平
        neuralNetwork.values().forEach(Neuron::decayActivity);
    }

    private void adjustSynapticPlasticity() {
        // 调整突触可塑性
        synapticWeights.replaceAll((id, weight) -> {
            Neuron from = neuralNetwork.get(synapses.get(id).getFromNeuron());
            Neuron to = neuralNetwork.get(synapses.get(id).getToNeuron());
            
            if (from != null && to != null) {
                // 基于神经元活动水平调整权重
                double activityFactor = (from.getActivityLevel() + to.getActivityLevel()) / 2.0;
                return weight * 0.99 + activityFactor * 0.01; // 缓慢衰减并受活动影响
            }
            return weight;
        });
    }

    private void performMemoryConsolidation() {
        // 记忆巩固：将短期记忆转化为长期记忆
        for (Neuron neuron : neuralNetwork.values()) {
            if (neuron.getActivationCount() > 10 && neuron.getValue() != null) { // 激活超过10次
                longTermMemory.put(neuron.getId(), 
                        new LongTermMemory(neuron.getId(), neuron.getValue(), System.currentTimeMillis()));
            }
        }
        
        totalMemoryConsolidations.incrementAndGet();
        memoryConsolidations.increment();
    }

    private void performLearningCycle() {
        // 执行学习循环
        totalLearningCycles.incrementAndGet();
        learningCycles.increment();
    }

    // ==================== 状态查询 ====================

    public Map<String, Object> getBioStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalNeuralActivations", totalNeuralActivations.get());
        stats.put("totalLearningCycles", totalLearningCycles.get());
        stats.put("totalMemoryConsolidations", totalMemoryConsolidations.get());
        stats.put("neurons", neuralNetwork.size());
        stats.put("synapses", synapses.size());
        stats.put("longTermMemories", longTermMemory.size());
        stats.put("averageSynapticWeight", synapticWeights.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0));
        stats.put("neuralPathways", neuralPaths.size());
        
        return stats;
    }

    public List<String> getActiveNeurons() {
        return neuralNetwork.values().stream()
                .filter(neuron -> neuron.getActivityLevel() > 0.1)
                .map(Neuron::getId)
                .toList();
    }

    // ==================== 内部类 ====================

    private static class Neuron {
        private final String id;
        private Object value;
        private volatile double activityLevel;
        private volatile long lastAccessed;
        private volatile int activationCount;
        private final long creationTime;

        Neuron(String id) {
            this.id = id;
            this.activityLevel = 0.1; // 基础活动水平
            this.lastAccessed = System.currentTimeMillis();
            this.activationCount = 0;
            this.creationTime = System.currentTimeMillis();
        }

        String getId() { return id; }
        Object getValue() { return value; }
        void setValue(Object value) { this.value = value; }
        double getActivityLevel() { return activityLevel; }
        void setActivityLevel(double activityLevel) { this.activityLevel = activityLevel; }
        long getLastAccessed() { return lastAccessed; }
        void setLastAccessed(long lastAccessed) { this.lastAccessed = lastAccessed; }
        int getActivationCount() { return activationCount; }
        void incrementActivationCount() { this.activationCount++; }
        long getCreationTime() { return creationTime; }

        void decayActivity() {
            // 活动水平随时间衰减
            long timeSinceAccess = System.currentTimeMillis() - lastAccessed;
            if (timeSinceAccess > 1000) { // 1秒后开始衰减
                this.activityLevel = Math.max(0.01, this.activityLevel * 0.99);
            }
        }
    }

    private static class Synapse {
        private final String id;
        private final String fromNeuron;
        private final String toNeuron;
        private final long creationTime;

        Synapse(String id, String fromNeuron, String toNeuron) {
            this.id = id;
            this.fromNeuron = fromNeuron;
            this.toNeuron = toNeuron;
            this.creationTime = System.currentTimeMillis();
        }

        String getId() { return id; }
        String getFromNeuron() { return fromNeuron; }
        String getToNeuron() { return toNeuron; }
        long getCreationTime() { return creationTime; }
    }

    private static class LongTermMemory {
        private final String key;
        private final Object value;
        private volatile long lastAccessed;
        private final long creationTime;

        LongTermMemory(String key, Object value, long creationTime) {
            this.key = key;
            this.value = value;
            this.creationTime = creationTime;
            this.lastAccessed = creationTime;
        }

        String getKey() { return key; }
        Object getValue() { return value; }
        long getLastAccessed() { return lastAccessed; }
        void setLastAccessed(long lastAccessed) { this.lastAccessed = lastAccessed; }
        long getCreationTime() { return creationTime; }
    }
}
