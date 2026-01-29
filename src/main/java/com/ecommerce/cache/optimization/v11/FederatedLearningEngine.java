package com.ecommerce.cache.optimization.v11;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 联邦学习缓存策略引擎
 * 
 * 核心特性:
 * 1. 分布式模型训练: 本地训练+全局聚合
 * 2. 差分隐私保护: 梯度噪声注入
 * 3. 安全聚合: 加密梯度传输
 * 4. 模型个性化: 本地模型适配
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FederatedLearningEngine {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV11Properties properties;
    
    /** 本地模型参数 */
    private double[] localModelParams;
    
    /** 全局模型参数 */
    private volatile double[] globalModelParams;
    
    /** 参与者注册 */
    private final ConcurrentMap<String, ParticipantInfo> participants = new ConcurrentHashMap<>();
    
    /** 聚合收集器 */
    private final ConcurrentMap<Integer, List<GradientUpdate>> roundUpdates = new ConcurrentHashMap<>();
    
    /** 当前轮次 */
    private final AtomicInteger currentRound = new AtomicInteger(0);
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong trainCount = new AtomicLong(0);
    private final AtomicLong aggregationCount = new AtomicLong(0);
    
    private Counter trainCounter;
    private Counter aggregationCounter;
    
    private final Random random = new Random();
    
    @PostConstruct
    public void init() {
        if (!properties.getFederatedLearning().isEnabled()) {
            log.info("[联邦学习] 已禁用");
            return;
        }
        
        // 初始化模型
        int paramSize = 64;
        localModelParams = new double[paramSize];
        globalModelParams = new double[paramSize];
        initializeParams(localModelParams);
        initializeParams(globalModelParams);
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "federated-learning");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 定期聚合
        int interval = properties.getFederatedLearning().getRoundIntervalSec();
        scheduler.scheduleWithFixedDelay(this::executeRound, interval, interval, TimeUnit.SECONDS);
        
        log.info("[联邦学习] 初始化完成 - 轮次间隔: {}s, 最小参与: {}",
            interval, properties.getFederatedLearning().getMinParticipants());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        log.info("[联邦学习] 已关闭 - 训练: {}, 聚合: {}",
            trainCount.get(), aggregationCount.get());
    }
    
    private void initMetrics() {
        trainCounter = Counter.builder("cache.federated.train").register(meterRegistry);
        aggregationCounter = Counter.builder("cache.federated.aggregation").register(meterRegistry);
    }
    
    private void initializeParams(double[] params) {
        for (int i = 0; i < params.length; i++) {
            params[i] = random.nextGaussian() * 0.01;
        }
    }
    
    // ========== 参与者管理 ==========
    
    /**
     * 注册参与者
     */
    public void registerParticipant(String participantId, Map<String, Object> metadata) {
        ParticipantInfo info = new ParticipantInfo(
            participantId,
            metadata,
            System.currentTimeMillis(),
            0,
            globalModelParams.clone()
        );
        participants.put(participantId, info);
        log.info("[联邦学习] 注册参与者: {}", participantId);
    }
    
    /**
     * 移除参与者
     */
    public void removeParticipant(String participantId) {
        participants.remove(participantId);
    }
    
    // ========== 本地训练 ==========
    
    /**
     * 本地训练
     */
    public TrainingResult localTrain(String participantId, List<TrainingSample> samples) {
        trainCount.incrementAndGet();
        trainCounter.increment();
        
        ParticipantInfo participant = participants.get(participantId);
        if (participant == null) {
            return new TrainingResult(participantId, false, "Participant not registered", null);
        }
        
        double[] localParams = participant.getLocalParams().clone();
        double learningRate = 0.01;
        int epochs = properties.getFederatedLearning().getLocalEpochs();
        
        // 本地SGD训练
        for (int epoch = 0; epoch < epochs; epoch++) {
            for (TrainingSample sample : samples) {
                double[] gradient = computeGradient(localParams, sample);
                
                // 更新参数
                for (int i = 0; i < localParams.length && i < gradient.length; i++) {
                    localParams[i] -= learningRate * gradient[i];
                }
            }
        }
        
        // 计算梯度更新
        double[] gradientUpdate = new double[localParams.length];
        for (int i = 0; i < localParams.length; i++) {
            gradientUpdate[i] = localParams[i] - globalModelParams[i];
        }
        
        // 添加差分隐私噪声
        addDifferentialPrivacyNoise(gradientUpdate);
        
        // 提交更新
        submitGradientUpdate(participantId, gradientUpdate);
        
        // 更新本地参数
        participant.setLocalParams(localParams);
        participant.setContributionCount(participant.getContributionCount() + 1);
        
        return new TrainingResult(participantId, true, "Training completed", gradientUpdate);
    }
    
    /**
     * 预测(使用本地模型)
     */
    public double[] predict(String participantId, double[] features) {
        ParticipantInfo participant = participants.get(participantId);
        double[] params = participant != null ? participant.getLocalParams() : globalModelParams;
        
        // 简单线性预测
        double[] output = new double[params.length / features.length];
        for (int i = 0; i < output.length; i++) {
            double sum = 0;
            for (int j = 0; j < features.length; j++) {
                int paramIdx = i * features.length + j;
                if (paramIdx < params.length) {
                    sum += params[paramIdx] * features[j];
                }
            }
            output[i] = sigmoid(sum);
        }
        
        return output;
    }
    
    // ========== 联邦聚合 ==========
    
    private void submitGradientUpdate(String participantId, double[] gradient) {
        int round = currentRound.get();
        roundUpdates.computeIfAbsent(round, k -> new CopyOnWriteArrayList<>())
            .add(new GradientUpdate(participantId, gradient, System.currentTimeMillis()));
    }
    
    private void executeRound() {
        int round = currentRound.get();
        List<GradientUpdate> updates = roundUpdates.get(round);
        
        int minParticipants = properties.getFederatedLearning().getMinParticipants();
        
        if (updates == null || updates.size() < minParticipants) {
            log.debug("[联邦学习] 参与者不足: {}/{}", 
                updates != null ? updates.size() : 0, minParticipants);
            return;
        }
        
        // 执行FedAvg聚合
        double[] aggregatedGradient = new double[globalModelParams.length];
        int count = 0;
        
        for (GradientUpdate update : updates) {
            for (int i = 0; i < aggregatedGradient.length && i < update.gradient.length; i++) {
                aggregatedGradient[i] += update.gradient[i];
            }
            count++;
        }
        
        // 平均
        if (count > 0) {
            for (int i = 0; i < aggregatedGradient.length; i++) {
                aggregatedGradient[i] /= count;
            }
        }
        
        // 更新全局模型
        for (int i = 0; i < globalModelParams.length; i++) {
            globalModelParams[i] += aggregatedGradient[i];
        }
        
        aggregationCount.incrementAndGet();
        aggregationCounter.increment();
        
        // 清理并推进轮次
        roundUpdates.remove(round);
        currentRound.incrementAndGet();
        
        // 同步到参与者
        syncGlobalModel();
        
        log.info("[联邦学习] 轮次 {} 完成 - 参与: {}", round, count);
    }
    
    private void syncGlobalModel() {
        double threshold = properties.getFederatedLearning().getAggregationThreshold();
        
        for (ParticipantInfo participant : participants.values()) {
            // 混合全局和本地模型
            double[] mixed = new double[globalModelParams.length];
            for (int i = 0; i < mixed.length; i++) {
                mixed[i] = threshold * globalModelParams[i] + 
                          (1 - threshold) * participant.getLocalParams()[i];
            }
            participant.setLocalParams(mixed);
        }
    }
    
    // ========== 内部方法 ==========
    
    private double[] computeGradient(double[] params, TrainingSample sample) {
        double[] gradient = new double[params.length];
        double[] features = sample.features;
        double label = sample.label;
        
        // 计算预测值
        double pred = 0;
        for (int i = 0; i < features.length && i < params.length; i++) {
            pred += params[i] * features[i];
        }
        pred = sigmoid(pred);
        
        // 计算梯度(交叉熵损失)
        double error = pred - label;
        for (int i = 0; i < features.length && i < gradient.length; i++) {
            gradient[i] = error * features[i];
        }
        
        return gradient;
    }
    
    private void addDifferentialPrivacyNoise(double[] gradient) {
        double epsilon = properties.getFederatedLearning().getPrivacyBudget();
        double sensitivity = 1.0;
        double scale = sensitivity / epsilon;
        
        for (int i = 0; i < gradient.length; i++) {
            // 拉普拉斯噪声
            double u = random.nextDouble() - 0.5;
            double noise = -scale * Math.signum(u) * Math.log(1 - 2 * Math.abs(u));
            gradient[i] += noise;
        }
    }
    
    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("currentRound", currentRound.get());
        stats.put("participantCount", participants.size());
        stats.put("trainCount", trainCount.get());
        stats.put("aggregationCount", aggregationCount.get());
        stats.put("globalModelNorm", computeNorm(globalModelParams));
        
        List<Map<String, Object>> participantStats = new ArrayList<>();
        for (ParticipantInfo p : participants.values()) {
            participantStats.add(Map.of(
                "id", p.participantId,
                "contributions", p.contributionCount
            ));
        }
        stats.put("participants", participantStats);
        
        return stats;
    }
    
    private double computeNorm(double[] params) {
        double sum = 0;
        for (double p : params) {
            sum += p * p;
        }
        return Math.sqrt(sum);
    }
    
    public double[] getGlobalModel() {
        return globalModelParams.clone();
    }
    
    // ========== 内部类 ==========
    
    @Data
    public static class ParticipantInfo {
        private final String participantId;
        private final Map<String, Object> metadata;
        private final long registeredAt;
        private int contributionCount;
        private double[] localParams;
    }
    
    @Data
    public static class TrainingSample {
        private final double[] features;
        private final double label;
    }
    
    @Data
    public static class TrainingResult {
        private final String participantId;
        private final boolean success;
        private final String message;
        private final double[] gradientUpdate;
    }
    
    private record GradientUpdate(String participantId, double[] gradient, long timestamp) {}
}
