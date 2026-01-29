package com.ecommerce.cache.optimization.v10;

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
 * 神经网络缓存策略引擎
 * 
 * 核心特性:
 * 1. 深度学习预测: 预测Key访问模式
 * 2. 自适应淘汰: 智能淘汰冷数据
 * 3. 特征工程: 自动特征提取
 * 4. 在线学习: 实时模型更新
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NeuralCacheStrategy {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV10Properties properties;
    
    /** 神经网络权重 */
    private double[][] weightsInput;
    private double[][] weightsHidden;
    private double[] biasHidden;
    private double[] biasOutput;
    
    /** 特征缓存 */
    private final ConcurrentMap<String, FeatureVector> featureCache = new ConcurrentHashMap<>();
    
    /** 预测缓存 */
    private final ConcurrentMap<String, PredictionResult> predictionCache = new ConcurrentHashMap<>();
    
    /** 训练数据 */
    private final Queue<TrainingSample> trainingQueue = new ConcurrentLinkedQueue<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong predictCount = new AtomicLong(0);
    private final AtomicLong trainCount = new AtomicLong(0);
    private final AtomicLong hitCount = new AtomicLong(0);
    
    private Counter predictCounter;
    private Counter trainCounter;
    
    private final Random random = new Random(42);
    
    @PostConstruct
    public void init() {
        if (!properties.getNeuralCache().isEnabled()) {
            log.info("[神经网络缓存] 已禁用");
            return;
        }
        
        initNetwork();
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "neural-cache");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 定期训练
        scheduler.scheduleWithFixedDelay(this::trainBatch, 60, 60, TimeUnit.SECONDS);
        
        // 定期清理
        scheduler.scheduleWithFixedDelay(this::cleanupCache, 300, 300, TimeUnit.SECONDS);
        
        log.info("[神经网络缓存] 初始化完成 - 输入: {}, 隐藏: {}, 输出: {}",
            properties.getNeuralCache().getInputDim(),
            properties.getNeuralCache().getHiddenDim(),
            properties.getNeuralCache().getOutputDim());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        log.info("[神经网络缓存] 已关闭 - 预测: {}, 训练: {}", predictCount.get(), trainCount.get());
    }
    
    private void initNetwork() {
        int inputDim = properties.getNeuralCache().getInputDim();
        int hiddenDim = properties.getNeuralCache().getHiddenDim();
        int outputDim = properties.getNeuralCache().getOutputDim();
        
        // Xavier初始化
        double scale1 = Math.sqrt(2.0 / (inputDim + hiddenDim));
        double scale2 = Math.sqrt(2.0 / (hiddenDim + outputDim));
        
        weightsInput = new double[inputDim][hiddenDim];
        weightsHidden = new double[hiddenDim][outputDim];
        biasHidden = new double[hiddenDim];
        biasOutput = new double[outputDim];
        
        for (int i = 0; i < inputDim; i++) {
            for (int j = 0; j < hiddenDim; j++) {
                weightsInput[i][j] = random.nextGaussian() * scale1;
            }
        }
        
        for (int i = 0; i < hiddenDim; i++) {
            for (int j = 0; j < outputDim; j++) {
                weightsHidden[i][j] = random.nextGaussian() * scale2;
            }
        }
    }
    
    private void initMetrics() {
        predictCounter = Counter.builder("cache.neural.predict").register(meterRegistry);
        trainCounter = Counter.builder("cache.neural.train").register(meterRegistry);
    }
    
    // ========== 核心API ==========
    
    /**
     * 预测Key的访问概率
     */
    public PredictionResult predict(String key) {
        PredictionResult cached = predictionCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 60000) {
            return cached;
        }
        
        FeatureVector features = extractFeatures(key);
        double[] output = forward(features.toArray());
        
        double accessProb = sigmoid(output[0]);
        double cacheValue = sigmoid(output[1]) if output.length > 1 else accessProb;
        double evictionPriority = 1.0 - accessProb;
        
        PredictionResult result = new PredictionResult(
            key, accessProb, cacheValue, evictionPriority, System.currentTimeMillis()
        );
        
        predictionCache.put(key, result);
        predictCount.incrementAndGet();
        predictCounter.increment();
        
        return result;
    }
    
    /**
     * 批量预测
     */
    public Map<String, PredictionResult> predictBatch(Collection<String> keys) {
        Map<String, PredictionResult> results = new LinkedHashMap<>();
        for (String key : keys) {
            results.put(key, predict(key));
        }
        return results;
    }
    
    /**
     * 获取应淘汰的Key
     */
    public List<String> getEvictionCandidates(Collection<String> keys, int count) {
        Map<String, PredictionResult> predictions = predictBatch(keys);
        
        return predictions.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue().evictionPriority, a.getValue().evictionPriority))
            .limit(count)
            .map(Map.Entry::getKey)
            .toList();
    }
    
    /**
     * 记录访问(用于训练)
     */
    public void recordAccess(String key, boolean hit) {
        FeatureVector features = extractFeatures(key);
        trainingQueue.offer(new TrainingSample(features, hit ? 1.0 : 0.0, System.currentTimeMillis()));
        
        if (hit) hitCount.incrementAndGet();
        
        // 限制队列大小
        while (trainingQueue.size() > 10000) {
            trainingQueue.poll();
        }
    }
    
    /**
     * 触发训练
     */
    public void triggerTraining() {
        trainBatch();
    }
    
    // ========== 神经网络计算 ==========
    
    private double[] forward(double[] input) {
        int hiddenDim = properties.getNeuralCache().getHiddenDim();
        int outputDim = properties.getNeuralCache().getOutputDim();
        
        // 隐藏层
        double[] hidden = new double[hiddenDim];
        for (int j = 0; j < hiddenDim; j++) {
            double sum = biasHidden[j];
            for (int i = 0; i < input.length && i < weightsInput.length; i++) {
                sum += input[i] * weightsInput[i][j];
            }
            hidden[j] = relu(sum);
        }
        
        // Dropout
        if (properties.getNeuralCache().getDropoutRate() > 0) {
            for (int i = 0; i < hidden.length; i++) {
                if (random.nextDouble() < properties.getNeuralCache().getDropoutRate()) {
                    hidden[i] = 0;
                }
            }
        }
        
        // 输出层
        double[] output = new double[outputDim];
        for (int j = 0; j < outputDim; j++) {
            double sum = biasOutput[j];
            for (int i = 0; i < hiddenDim; i++) {
                sum += hidden[i] * weightsHidden[i][j];
            }
            output[j] = sum;
        }
        
        return output;
    }
    
    private void trainBatch() {
        if (trainingQueue.isEmpty()) return;
        
        int batchSize = Math.min(properties.getNeuralCache().getBatchSize(), trainingQueue.size());
        List<TrainingSample> batch = new ArrayList<>();
        
        for (int i = 0; i < batchSize; i++) {
            TrainingSample sample = trainingQueue.poll();
            if (sample != null) batch.add(sample);
        }
        
        if (batch.isEmpty()) return;
        
        double lr = properties.getNeuralCache().getLearningRate();
        
        for (TrainingSample sample : batch) {
            // 前向传播
            double[] input = sample.features.toArray();
            double[] output = forward(input);
            
            // 计算损失
            double target = sample.label;
            double pred = sigmoid(output[0]);
            double error = target - pred;
            
            // 简化的梯度更新
            for (int j = 0; j < biasOutput.length; j++) {
                biasOutput[j] += lr * error * 0.1;
            }
        }
        
        trainCount.addAndGet(batch.size());
        trainCounter.increment();
        
        log.debug("[神经网络缓存] 训练完成 - 样本: {}", batch.size());
    }
    
    private FeatureVector extractFeatures(String key) {
        FeatureVector cached = featureCache.get(key);
        if (cached != null) {
            return cached;
        }
        
        double[] features = new double[properties.getNeuralCache().getInputDim()];
        
        // 基础特征
        features[0] = key.length() / 100.0;
        features[1] = key.hashCode() % 1000 / 1000.0;
        
        // 字符分布
        int[] charCounts = new int[26];
        for (char c : key.toLowerCase().toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                charCounts[c - 'a']++;
            }
        }
        for (int i = 0; i < Math.min(26, features.length - 2); i++) {
            features[i + 2] = charCounts[i] / (double) key.length();
        }
        
        // 时间特征
        long now = System.currentTimeMillis();
        features[28] = (now % 86400000) / 86400000.0; // 一天中的时间
        features[29] = (now % 604800000) / 604800000.0; // 一周中的时间
        
        FeatureVector vector = new FeatureVector(key, features);
        featureCache.put(key, vector);
        return vector;
    }
    
    private void cleanupCache() {
        long now = System.currentTimeMillis();
        
        predictionCache.entrySet().removeIf(e -> now - e.getValue().timestamp > 300000);
        
        if (featureCache.size() > 100000) {
            int toRemove = featureCache.size() - 50000;
            Iterator<String> it = featureCache.keySet().iterator();
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }
    }
    
    // ========== 激活函数 ==========
    
    private double relu(double x) {
        return Math.max(0, x);
    }
    
    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("predictCount", predictCount.get());
        stats.put("trainCount", trainCount.get());
        stats.put("hitCount", hitCount.get());
        stats.put("featureCacheSize", featureCache.size());
        stats.put("predictionCacheSize", predictionCache.size());
        stats.put("trainingQueueSize", trainingQueue.size());
        
        if (predictCount.get() > 0) {
            stats.put("hitRate", String.format("%.2f%%", 
                (double) hitCount.get() / predictCount.get() * 100));
        }
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    @Data
    public static class PredictionResult {
        private final String key;
        private final double accessProbability;
        private final double cacheValue;
        private final double evictionPriority;
        private final long timestamp;
    }
    
    @Data
    private static class FeatureVector {
        private final String key;
        private final double[] features;
        
        double[] toArray() {
            return features.clone();
        }
    }
    
    private record TrainingSample(FeatureVector features, double label, long timestamp) {}
}
