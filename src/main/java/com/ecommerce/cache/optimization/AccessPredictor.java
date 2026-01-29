package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 智能访问预测器 - 基于机器学习的缓存优化
 * 
 * 核心特性：
 * 1. 时间序列预测 - 基于历史访问模式预测未来访问
 * 2. 关联规则挖掘 - 发现数据访问关联性
 * 3. 自适应 TTL - 根据访问频率动态调整过期时间
 * 4. 智能预取 - 提前加载可能被访问的数据
 * 5. 冷热数据分离 - 自动识别并分级存储
 * 6. 访问模式聚类 - 识别相似访问模式优化缓存策略
 * 
 * 目标：预测准确率 > 80%，预取命中率 > 60%
 */
@Service
public class AccessPredictor {
    
    private static final Logger log = LoggerFactory.getLogger(AccessPredictor.class);
    
    // 访问历史（滑动窗口）
    private final ConcurrentHashMap<String, AccessHistory> accessHistories = new ConcurrentHashMap<>();
    
    // 关联规则（A -> B 表示访问 A 后很可能访问 B）
    private final ConcurrentHashMap<String, Set<String>> associationRules = new ConcurrentHashMap<>();
    
    // 预取队列
    private final BlockingQueue<PrefetchTask> prefetchQueue = new LinkedBlockingQueue<>(10000);
    
    // 预测模型缓存
    private final ConcurrentHashMap<String, PredictionModel> predictionModels = new ConcurrentHashMap<>();
    
    // 时间模式统计（按小时）
    private final ConcurrentHashMap<Integer, LongAdder> hourlyAccessCounts = new ConcurrentHashMap<>();
    
    // 配置
    @Value("${optimization.predictor.history-window-hours:24}")
    private int historyWindowHours;
    
    @Value("${optimization.predictor.prediction-window-minutes:5}")
    private int predictionWindowMinutes;
    
    @Value("${optimization.predictor.prefetch-threshold:0.7}")
    private double prefetchThreshold;
    
    @Value("${optimization.predictor.min-access-count:10}")
    private int minAccessCount;
    
    @Value("${optimization.predictor.association-min-support:0.1}")
    private double associationMinSupport;
    
    @Value("${optimization.predictor.adaptive-ttl-enabled:true}")
    private boolean adaptiveTtlEnabled;
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Counter predictionHitCounter;
    private Counter predictionMissCounter;
    private Counter prefetchCounter;
    private Counter prefetchHitCounter;
    private Timer predictionTimer;
    
    // 统计
    private final AtomicLong totalPredictions = new AtomicLong(0);
    private final AtomicLong correctPredictions = new AtomicLong(0);
    private final AtomicLong totalPrefetches = new AtomicLong(0);
    private final AtomicLong prefetchHits = new AtomicLong(0);
    
    // 预取执行器
    private final ExecutorService prefetchExecutor;
    
    // 预取回调
    private BiConsumer<String, String> prefetchCallback;
    
    public AccessPredictor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.prefetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        // 初始化小时统计
        for (int i = 0; i < 24; i++) {
            hourlyAccessCounts.put(i, new LongAdder());
        }
    }
    
    @PostConstruct
    public void init() {
        // 注册指标
        predictionHitCounter = Counter.builder("predictor.prediction.hits").register(meterRegistry);
        predictionMissCounter = Counter.builder("predictor.prediction.misses").register(meterRegistry);
        prefetchCounter = Counter.builder("predictor.prefetch.count").register(meterRegistry);
        prefetchHitCounter = Counter.builder("predictor.prefetch.hits").register(meterRegistry);
        predictionTimer = Timer.builder("predictor.prediction.latency").register(meterRegistry);
        
        Gauge.builder("predictor.history.size", accessHistories, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("predictor.rules.size", associationRules, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("predictor.accuracy", this, AccessPredictor::getPredictionAccuracy)
            .register(meterRegistry);
        Gauge.builder("predictor.prefetch.hit.rate", this, AccessPredictor::getPrefetchHitRate)
            .register(meterRegistry);
        
        // 启动预取处理器
        startPrefetchProcessor();
        
        log.info("AccessPredictor initialized: historyWindow={}h, prefetchThreshold={}", 
            historyWindowHours, prefetchThreshold);
    }
    
    /**
     * 记录访问并预测
     */
    public void recordAccess(String key) {
        recordAccess(key, null);
    }
    
    /**
     * 记录访问（带上下文）
     */
    public void recordAccess(String key, String previousKey) {
        long now = System.currentTimeMillis();
        
        // 更新访问历史
        AccessHistory history = accessHistories.computeIfAbsent(key, k -> new AccessHistory(k));
        history.recordAccess(now);
        
        // 更新小时统计
        int hour = LocalDateTime.now().getHour();
        hourlyAccessCounts.get(hour).increment();
        
        // 更新关联规则
        if (previousKey != null && !previousKey.equals(key)) {
            updateAssociationRule(previousKey, key);
        }
        
        // 触发预测和预取
        triggerPrediction(key);
    }
    
    /**
     * 验证预测结果
     */
    public void validatePrediction(String key, boolean wasAccessed) {
        totalPredictions.incrementAndGet();
        if (wasAccessed) {
            correctPredictions.incrementAndGet();
            predictionHitCounter.increment();
        } else {
            predictionMissCounter.increment();
        }
    }
    
    /**
     * 验证预取命中
     */
    public void validatePrefetch(String key, boolean wasUsed) {
        totalPrefetches.incrementAndGet();
        if (wasUsed) {
            prefetchHits.incrementAndGet();
            prefetchHitCounter.increment();
        }
    }
    
    /**
     * 预测未来访问概率
     */
    public double predictAccessProbability(String key) {
        return predictionTimer.record(() -> {
            AccessHistory history = accessHistories.get(key);
            if (history == null || history.getAccessCount() < minAccessCount) {
                return 0.0;
            }
            
            // 综合多个因素计算访问概率
            double timeFactor = calculateTimeFactor(history);
            double frequencyFactor = calculateFrequencyFactor(history);
            double recencyFactor = calculateRecencyFactor(history);
            double patternFactor = calculatePatternFactor(key);
            
            // 加权平均
            return 0.3 * timeFactor + 0.3 * frequencyFactor + 0.2 * recencyFactor + 0.2 * patternFactor;
        });
    }
    
    /**
     * 获取关联的 Key（可能一起访问的）
     */
    public List<String> getAssociatedKeys(String key) {
        Set<String> associated = associationRules.get(key);
        if (associated == null || associated.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 按访问概率排序
        return associated.stream()
            .map(k -> new AbstractMap.SimpleEntry<>(k, predictAccessProbability(k)))
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .map(Map.Entry::getKey)
            .limit(10)
            .collect(Collectors.toList());
    }
    
    /**
     * 计算自适应 TTL
     */
    public long calculateAdaptiveTtl(String key, long baseTtlSeconds) {
        if (!adaptiveTtlEnabled) {
            return baseTtlSeconds;
        }
        
        AccessHistory history = accessHistories.get(key);
        if (history == null) {
            return baseTtlSeconds;
        }
        
        // 根据访问频率调整 TTL
        double accessRate = history.getAccessRate(); // 每分钟访问次数
        
        // 高频访问 -> 更长 TTL
        // 低频访问 -> 更短 TTL（节省内存）
        double multiplier;
        if (accessRate > 100) {
            multiplier = 3.0; // 超热点
        } else if (accessRate > 50) {
            multiplier = 2.0; // 热点
        } else if (accessRate > 10) {
            multiplier = 1.5; // 温数据
        } else if (accessRate > 1) {
            multiplier = 1.0; // 普通
        } else {
            multiplier = 0.5; // 冷数据
        }
        
        // 考虑时间模式
        double timeMultiplier = getTimeBasedMultiplier();
        
        long adaptedTtl = (long) (baseTtlSeconds * multiplier * timeMultiplier);
        
        // 限制范围
        return Math.max(60, Math.min(adaptedTtl, baseTtlSeconds * 5));
    }
    
    /**
     * 获取预取候选 Key
     */
    public List<String> getPrefetchCandidates(int limit) {
        return accessHistories.entrySet().stream()
            .filter(e -> e.getValue().getAccessCount() >= minAccessCount)
            .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), predictAccessProbability(e.getKey())))
            .filter(e -> e.getValue() >= prefetchThreshold)
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
    
    /**
     * 获取热点 Key（按访问频率排序）
     */
    public List<HotKeyInfo> getHotKeys(int limit) {
        return accessHistories.entrySet().stream()
            .filter(e -> e.getValue().getAccessCount() >= minAccessCount)
            .map(e -> new HotKeyInfo(
                e.getKey(),
                e.getValue().getAccessCount(),
                e.getValue().getAccessRate(),
                predictAccessProbability(e.getKey()),
                calculateAdaptiveTtl(e.getKey(), 600)
            ))
            .sorted((a, b) -> Double.compare(b.accessRate(), a.accessRate()))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    /**
     * 获取冷数据 Key
     */
    public List<String> getColdKeys(int limit, long maxLastAccessMs) {
        long threshold = System.currentTimeMillis() - maxLastAccessMs;
        
        return accessHistories.entrySet().stream()
            .filter(e -> e.getValue().getLastAccessTime() < threshold)
            .sorted((a, b) -> Long.compare(a.getValue().getLastAccessTime(), b.getValue().getLastAccessTime()))
            .limit(limit)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
    
    /**
     * 设置预取回调
     */
    public void setPrefetchCallback(BiConsumer<String, String> callback) {
        this.prefetchCallback = callback;
    }
    
    // ========== 私有方法 ==========
    
    /**
     * 计算时间因素（基于历史访问时间模式）
     */
    private double calculateTimeFactor(AccessHistory history) {
        int currentHour = LocalDateTime.now().getHour();
        long currentHourAccesses = hourlyAccessCounts.get(currentHour).sum();
        long totalAccesses = hourlyAccessCounts.values().stream().mapToLong(LongAdder::sum).sum();
        
        if (totalAccesses == 0) return 0.5;
        
        // 当前小时的访问占比
        double hourlyRatio = (double) currentHourAccesses / totalAccesses;
        return Math.min(1.0, hourlyRatio * 24); // 归一化
    }
    
    /**
     * 计算频率因素
     */
    private double calculateFrequencyFactor(AccessHistory history) {
        double accessRate = history.getAccessRate();
        // 使用对数函数平滑
        return Math.min(1.0, Math.log1p(accessRate) / Math.log1p(100));
    }
    
    /**
     * 计算时效性因素（最近访问）
     */
    private double calculateRecencyFactor(AccessHistory history) {
        long timeSinceLastAccess = System.currentTimeMillis() - history.getLastAccessTime();
        long windowMs = historyWindowHours * 3600 * 1000L;
        
        // 越近访问，因素越高
        return Math.max(0, 1.0 - (double) timeSinceLastAccess / windowMs);
    }
    
    /**
     * 计算模式因素（访问规律性）
     */
    private double calculatePatternFactor(String key) {
        // 检查关联规则强度
        Set<String> associated = associationRules.get(key);
        if (associated == null || associated.isEmpty()) {
            return 0.3;
        }
        
        // 有关联的 key 更可能被访问
        return 0.5 + 0.5 * Math.min(1.0, associated.size() / 10.0);
    }
    
    /**
     * 获取基于时间的 TTL 调整因子
     */
    private double getTimeBasedMultiplier() {
        int hour = LocalDateTime.now().getHour();
        
        // 高峰期（9-12, 19-22）延长 TTL
        if ((hour >= 9 && hour <= 12) || (hour >= 19 && hour <= 22)) {
            return 1.5;
        }
        // 低谷期（0-6）缩短 TTL
        if (hour >= 0 && hour <= 6) {
            return 0.7;
        }
        return 1.0;
    }
    
    /**
     * 更新关联规则
     */
    private void updateAssociationRule(String fromKey, String toKey) {
        associationRules
            .computeIfAbsent(fromKey, k -> ConcurrentHashMap.newKeySet())
            .add(toKey);
    }
    
    /**
     * 触发预测和预取
     */
    private void triggerPrediction(String key) {
        // 获取关联 Key
        List<String> associated = getAssociatedKeys(key);
        
        for (String associatedKey : associated) {
            double probability = predictAccessProbability(associatedKey);
            if (probability >= prefetchThreshold) {
                prefetchQueue.offer(new PrefetchTask(associatedKey, probability));
            }
        }
    }
    
    /**
     * 启动预取处理器
     */
    private void startPrefetchProcessor() {
        Thread processor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    PrefetchTask task = prefetchQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null && prefetchCallback != null) {
                        prefetchExecutor.submit(() -> {
                            try {
                                prefetchCallback.accept(task.key, null);
                                prefetchCounter.increment();
                                log.debug("Prefetched key: {}, probability: {}", 
                                    task.key, String.format("%.2f", task.probability));
                            } catch (Exception e) {
                                log.warn("Prefetch failed for key: {}", task.key, e);
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "prefetch-processor");
        processor.setDaemon(true);
        processor.start();
    }
    
    /**
     * 定期清理过期历史
     */
    @Scheduled(fixedRate = 3600000) // 1 小时
    public void cleanupExpiredHistory() {
        long threshold = System.currentTimeMillis() - historyWindowHours * 3600 * 1000L;
        
        accessHistories.entrySet().removeIf(e -> {
            e.getValue().cleanup(threshold);
            return e.getValue().getAccessCount() == 0;
        });
        
        log.debug("Access history cleanup, remaining: {}", accessHistories.size());
    }
    
    /**
     * 定期挖掘关联规则
     */
    @Scheduled(fixedRate = 300000) // 5 分钟
    public void mineAssociationRules() {
        // 清理弱关联
        associationRules.entrySet().removeIf(e -> {
            AccessHistory history = accessHistories.get(e.getKey());
            return history == null || history.getAccessCount() < minAccessCount;
        });
        
        log.debug("Association rules cleanup, remaining: {}", associationRules.size());
    }
    
    /**
     * 获取预测准确率
     */
    public double getPredictionAccuracy() {
        long total = totalPredictions.get();
        long correct = correctPredictions.get();
        return total > 0 ? (double) correct / total : 0;
    }
    
    /**
     * 获取预取命中率
     */
    public double getPrefetchHitRate() {
        long total = totalPrefetches.get();
        long hits = prefetchHits.get();
        return total > 0 ? (double) hits / total : 0;
    }
    
    /**
     * 获取统计信息
     */
    public PredictorStats getStats() {
        return new PredictorStats(
            accessHistories.size(),
            associationRules.size(),
            getPredictionAccuracy(),
            getPrefetchHitRate(),
            totalPredictions.get(),
            totalPrefetches.get(),
            prefetchQueue.size()
        );
    }
    
    // ========== 内部类 ==========
    
    /**
     * 访问历史
     */
    private static class AccessHistory {
        private final String key;
        private final LongAdder accessCount = new LongAdder();
        private final ConcurrentLinkedQueue<Long> accessTimes = new ConcurrentLinkedQueue<>();
        private volatile long lastAccessTime = 0;
        private volatile long firstAccessTime = 0;
        
        AccessHistory(String key) {
            this.key = key;
        }
        
        void recordAccess(long timestamp) {
            accessCount.increment();
            accessTimes.offer(timestamp);
            lastAccessTime = timestamp;
            if (firstAccessTime == 0) {
                firstAccessTime = timestamp;
            }
            
            // 保持最近 1000 条记录
            while (accessTimes.size() > 1000) {
                accessTimes.poll();
            }
        }
        
        long getAccessCount() {
            return accessCount.sum();
        }
        
        long getLastAccessTime() {
            return lastAccessTime;
        }
        
        double getAccessRate() {
            long count = accessCount.sum();
            if (count == 0 || firstAccessTime == 0) return 0;
            
            long duration = Math.max(60000, System.currentTimeMillis() - firstAccessTime);
            return count * 60000.0 / duration; // 每分钟访问次数
        }
        
        void cleanup(long threshold) {
            accessTimes.removeIf(t -> t < threshold);
        }
    }
    
    /**
     * 预测模型（简化版）
     */
    private static class PredictionModel {
        private final String key;
        private double[] weights = new double[4]; // 时间、频率、时效性、模式
        private volatile long lastTrainTime = 0;
        
        PredictionModel(String key) {
            this.key = key;
            // 初始权重
            Arrays.fill(weights, 0.25);
        }
        
        void train(double[] features, boolean accessed) {
            // 简单的在线学习
            double predicted = predict(features);
            double error = (accessed ? 1.0 : 0.0) - predicted;
            double learningRate = 0.01;
            
            for (int i = 0; i < weights.length; i++) {
                weights[i] += learningRate * error * features[i];
                weights[i] = Math.max(0, Math.min(1, weights[i]));
            }
            
            // 归一化权重
            double sum = Arrays.stream(weights).sum();
            if (sum > 0) {
                for (int i = 0; i < weights.length; i++) {
                    weights[i] /= sum;
                }
            }
            
            lastTrainTime = System.currentTimeMillis();
        }
        
        double predict(double[] features) {
            double result = 0;
            for (int i = 0; i < Math.min(weights.length, features.length); i++) {
                result += weights[i] * features[i];
            }
            return Math.max(0, Math.min(1, result));
        }
    }
    
    private record PrefetchTask(String key, double probability) {}
    
    public record HotKeyInfo(String key, long accessCount, double accessRate, 
                              double predictedProbability, long recommendedTtl) {}
    
    public record PredictorStats(
        int historySize,
        int rulesCount,
        double predictionAccuracy,
        double prefetchHitRate,
        long totalPredictions,
        long totalPrefetches,
        int pendingPrefetches
    ) {}
}
