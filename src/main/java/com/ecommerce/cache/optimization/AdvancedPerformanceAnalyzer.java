package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * 高级性能分析器
 * 
 * 提供全方位的性能监控、分析和优化建议，支持量子级和传统级分析
 */
@Component
public class AdvancedPerformanceAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AdvancedPerformanceAnalyzer.class);

    // 性能指标
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicLong quantumOperations = new AtomicLong(0);
    private final AtomicLong traditionalOperations = new AtomicLong(0);

    // 延迟统计
    private final AtomicLong totalLatencyNanos = new AtomicLong(0);
    private final AtomicLong minLatencyNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxLatencyNanos = new AtomicLong(0);

    // 历史性能数据
    private final Map<String, PerformanceRecord> recentPerformance = new ConcurrentHashMap<>();
    private final List<PerformanceRecord> performanceHistory = Collections.synchronizedList(new ArrayList<>());

    // 执行器
    private ScheduledExecutorService analyzerExecutor;

    // 指标
    private Counter requestCounter;
    private Counter hitCounter;
    private Counter missCounter;
    private Counter errorCounter;
    private Counter quantumOperationCounter;
    private Counter traditionalOperationCounter;
    private Timer responseTimer;

    public AdvancedPerformanceAnalyzer(MeterRegistry meterRegistry) {
        initializeMetrics(meterRegistry);
    }

    private void initializeMetrics(MeterRegistry meterRegistry) {
        requestCounter = Counter.builder("performance.requests.total")
                .description("总请求数").register(meterRegistry);
        hitCounter = Counter.builder("performance.cache.hits")
                .description("缓存命中数").register(meterRegistry);
        missCounter = Counter.builder("performance.cache.misses")
                .description("缓存未命中数").register(meterRegistry);
        errorCounter = Counter.builder("performance.errors")
                .description("错误数").register(meterRegistry);
        quantumOperationCounter = Counter.builder("performance.quantum.operations")
                .description("量子操作数").register(meterRegistry);
        traditionalOperationCounter = Counter.builder("performance.traditional.operations")
                .description("传统操作数").register(meterRegistry);
        responseTimer = Timer.builder("performance.response.time")
                .description("响应时间").register(meterRegistry);

        Gauge.builder("performance.cache.hit.ratio", () -> {
            long hits = cacheHits.get();
            long misses = cacheMisses.get();
            return (hits + misses) > 0 ? (double) hits / (hits + misses) : 0.0;
        }).description("缓存命中率").register(meterRegistry);

        Gauge.builder("performance.average.latency.ms", () -> {
            long count = totalRequests.get();
            return count > 0 ? (double) totalLatencyNanos.get() / count / 1_000_000 : 0.0;
        }).description("平均延迟(毫秒)").register(meterRegistry);

        Gauge.builder("performance.qps.current", this, analyzer -> analyzer.getCurrentQPS())
                .description("当前QPS").register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        initializeExecutors();
        startBackgroundAnalysis();

        log.info("高级性能分析器初始化完成");
    }

    private void initializeExecutors() {
        analyzerExecutor = Executors.newScheduledThreadPool(3, new ThreadFactory() {
            private int counter = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "advanced-performance-analyzer-" + counter++);
                t.setDaemon(true);
                return t;
            }
        });
    }

    private void startBackgroundAnalysis() {
        // 每秒记录一次性能快照
        analyzerExecutor.scheduleAtFixedRate(this::recordPerformanceSnapshot, 
                1, 1, TimeUnit.SECONDS);
        
        // 每10秒分析性能趋势
        analyzerExecutor.scheduleAtFixedRate(this::analyzePerformanceTrends, 
                10, 10, TimeUnit.SECONDS);
        
        // 每分钟清理历史数据
        analyzerExecutor.scheduleAtFixedRate(this::cleanupHistoricalData, 
                60, 60, TimeUnit.SECONDS);
    }

    /**
     * 记录性能指标
     */
    public void recordRequest(Duration latency, boolean isHit, boolean isQuantumOperation) {
        totalRequests.incrementAndGet();
        requestCounter.increment();
        
        if (isHit) {
            cacheHits.incrementAndGet();
            hitCounter.increment();
        } else {
            cacheMisses.incrementAndGet();
            missCounter.increment();
        }
        
        if (isQuantumOperation) {
            quantumOperations.incrementAndGet();
            quantumOperationCounter.increment();
        } else {
            traditionalOperations.incrementAndGet();
            traditionalOperationCounter.increment();
        }
        
        long latencyNanos = latency.toNanos();
        totalLatencyNanos.addAndGet(latencyNanos);
        
        // 更新最值
        minLatencyNanos.accumulateAndGet(latencyNanos, Math::min);
        maxLatencyNanos.accumulateAndGet(latencyNanos, Math::max);
        
        // 记录本次请求的性能数据
        String requestId = "req_" + System.nanoTime();
        recentPerformance.put(requestId, new PerformanceRecord(
            requestId, latencyNanos, isHit, isQuantumOperation, System.currentTimeMillis()
        ));
    }

    /**
     * 记录错误
     */
    public void recordError() {
        errors.incrementAndGet();
        errorCounter.increment();
    }

    /**
     * 记录性能快照
     */
    private void recordPerformanceSnapshot() {
        PerformanceRecord snapshot = new PerformanceRecord(
            "snapshot_" + System.currentTimeMillis(),
            getCurrentAverageLatency(),
            true, // 作为聚合指标，设为true
            true, // 包含量子操作
            System.currentTimeMillis()
        );
        
        performanceHistory.add(snapshot);
        
        // 限制历史记录数量
        if (performanceHistory.size() > 1000) {
            performanceHistory.remove(0);
        }
        
        log.debug("性能快照记录: reqs={}, hits={}, avg_latency={}ns", 
                totalRequests.get(), cacheHits.get(), getCurrentAverageLatency());
    }

    /**
     * 分析性能趋势
     */
    private void analyzePerformanceTrends() {
        if (performanceHistory.size() < 2) {
            return;
        }
        
        // 分析最近的性能趋势
        List<PerformanceRecord> recent = performanceHistory.subList(
            Math.max(0, performanceHistory.size() - 10),
            performanceHistory.size()
        );
        
        if (recent.size() >= 2) {
            PerformanceTrend trend = analyzeTrend(recent);
            
            log.info("性能趋势分析: direction={}, rate={}%, avg_latency_change={}ns", 
                    trend.direction, trend.rateOfChange, trend.avgLatencyChange);
        }
    }

    /**
     * 清理历史数据
     */
    private void cleanupHistoricalData() {
        long cutoffTime = System.currentTimeMillis() - 3600000; // 1小时前
        
        performanceHistory.removeIf(record -> record.timestamp < cutoffTime);
        recentPerformance.entrySet().removeIf(entry -> 
            System.currentTimeMillis() - entry.getValue().timestamp > 300000); // 5分钟
        
        log.debug("历史数据清理完成: remaining_snapshots={}, recent_records={}", 
                performanceHistory.size(), recentPerformance.size());
    }

    /**
     * 获取当前QPS
     */
    public double getCurrentQPS() {
        // 基于最近1秒的请求量估算QPS
        // 这里简化实现，实际应用中可能需要更精确的滑动窗口算法
        return totalRequests.get() / (System.currentTimeMillis() / 1000.0 + 1);
    }

    /**
     * 获取当前平均延迟（纳秒）
     */
    private long getCurrentAverageLatency() {
        long count = totalRequests.get();
        return count > 0 ? totalLatencyNanos.get() / count : 0;
    }

    /**
     * 分析性能趋势
     */
    private PerformanceTrend analyzeTrend(List<PerformanceRecord> records) {
        if (records.size() < 2) {
            return new PerformanceTrend(PerformanceDirection.STABLE, 0.0, 0);
        }
        
        PerformanceRecord first = records.get(0);
        PerformanceRecord last = records.get(records.size() - 1);
        
        long firstAvgLatency = first.latencyNanos;
        long lastAvgLatency = last.latencyNanos;
        
        double rateOfChange = ((double)(lastAvgLatency - firstAvgLatency) / firstAvgLatency) * 100;
        long avgLatencyChange = lastAvgLatency - firstAvgLatency;
        
        PerformanceDirection direction;
        if (Math.abs(rateOfChange) < 1.0) { // 1%以内视为稳定
            direction = PerformanceDirection.STABLE;
        } else if (rateOfChange > 0) {
            direction = PerformanceDirection.DEGRADE;
        } else {
            direction = PerformanceDirection.IMPROVE;
        }
        
        return new PerformanceTrend(direction, rateOfChange, avgLatencyChange);
    }

    /**
     * 生成性能优化建议
     */
    public PerformanceRecommendation generateRecommendations() {
        PerformanceRecommendation recommendation = new PerformanceRecommendation();
        
        double hitRate = getHitRate();
        long avgLatency = getCurrentAverageLatency();
        double currentQPS = getCurrentQPS();
        long errorCount = errors.get();
        
        // 缓存命中率建议
        if (hitRate < 0.95) {
            recommendation.addRecommendation("缓存命中率较低(" + String.format("%.2f%%", hitRate * 100) + 
                ")，考虑优化缓存策略或增加缓存容量");
        } else if (hitRate > 0.99) {
            recommendation.addRecommendation("缓存命中率优秀(" + String.format("%.2f%%", hitRate * 100) + 
                ")，继续保持当前策略");
        }
        
        // 延迟建议
        if (avgLatency > 1_000_000) { // 1毫秒
            recommendation.addRecommendation("平均延迟较高(" + (avgLatency / 1_000_000) + "ms)，" +
                "考虑优化热点数据访问或使用更高速的缓存层级");
        }
        
        // QPS建议
        if (currentQPS > 100_000_000) { // 1亿QPS
            recommendation.addRecommendation("当前QPS达到" + String.format("%.2fM", currentQPS / 1_000_000) + 
                "，系统负载较高，建议开启量子级优化");
        }
        
        // 错误率建议
        long totalRequestsCount = totalRequests.get();
        if (totalRequestsCount > 0 && (double)errorCount / totalRequestsCount > 0.001) { // 0.1%
            recommendation.addRecommendation("错误率较高(" + String.format("%.3f%%", 
                ((double)errorCount / totalRequestsCount) * 100) + 
                ")，需要检查系统稳定性");
        }
        
        // 量子优化建议
        long quantumOps = quantumOperations.get();
        long traditionalOps = traditionalOperations.get();
        if (quantumOps + traditionalOps > 0) {
            double quantumRatio = (double) quantumOps / (quantumOps + traditionalOps);
            if (quantumRatio < 0.5) {
                recommendation.addRecommendation("量子操作占比较低(" + String.format("%.2f%%", quantumRatio * 100) + 
                    ")，在高负载场景下可考虑增加量子级优化使用");
            }
        }
        
        return recommendation;
    }

    /**
     * 获取缓存命中率
     */
    public double getHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        return (hits + misses) > 0 ? (double) hits / (hits + misses) : 0.0;
    }

    /**
     * 获取性能统计
     */
    public PerformanceMetrics getMetrics() {
        PerformanceMetrics metrics = new PerformanceMetrics();
        metrics.setTotalRequests(totalRequests.get());
        metrics.setCacheHits(cacheHits.get());
        metrics.setCacheMisses(cacheMisses.get());
        metrics.setErrors(errors.get());
        metrics.setQuantumOperations(quantumOperations.get());
        metrics.setTraditionalOperations(traditionalOperations.get());
        metrics.setHitRate(getHitRate());
        metrics.setAverageLatencyNanos(getCurrentAverageLatency());
        metrics.setMinLatencyNanos(minLatencyNanos.get() == Long.MAX_VALUE ? 0 : minLatencyNanos.get());
        metrics.setMaxLatencyNanos(maxLatencyNanos.get());
        metrics.setCurrentQPS(getCurrentQPS());
        return metrics;
    }

    @PreDestroy
    public void shutdown() {
        if (analyzerExecutor != null) {
            analyzerExecutor.shutdown();
            try {
                if (!analyzerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    analyzerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                analyzerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // 内部类定义
    public static class PerformanceRecord {
        final String id;
        final long latencyNanos;
        final boolean isHit;
        final boolean isQuantumOperation;
        final long timestamp;

        PerformanceRecord(String id, long latencyNanos, boolean isHit, 
                         boolean isQuantumOperation, long timestamp) {
            this.id = id;
            this.latencyNanos = latencyNanos;
            this.isHit = isHit;
            this.isQuantumOperation = isQuantumOperation;
            this.timestamp = timestamp;
        }
    }

    public enum PerformanceDirection {
        IMPROVE, STABLE, DEGRADE
    }

    public static class PerformanceTrend {
        final PerformanceDirection direction;
        final double rateOfChange;
        final long avgLatencyChange;

        PerformanceTrend(PerformanceDirection direction, double rateOfChange, long avgLatencyChange) {
            this.direction = direction;
            this.rateOfChange = rateOfChange;
            this.avgLatencyChange = avgLatencyChange;
        }
    }

    public static class PerformanceRecommendation {
        private final List<String> recommendations = new ArrayList<>();

        public void addRecommendation(String recommendation) {
            recommendations.add(recommendation);
        }

        public List<String> getRecommendations() {
            return new ArrayList<>(recommendations);
        }

        public boolean hasRecommendations() {
            return !recommendations.isEmpty();
        }

        @Override
        public String toString() {
            return String.join("\n", recommendations);
        }
    }

    public static class PerformanceMetrics {
        private long totalRequests;
        private long cacheHits;
        private long cacheMisses;
        private long errors;
        private long quantumOperations;
        private long traditionalOperations;
        private double hitRate;
        private long averageLatencyNanos;
        private long minLatencyNanos;
        private long maxLatencyNanos;
        private double currentQPS;

        // Getters and Setters
        public long getTotalRequests() { return totalRequests; }
        public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }
        
        public long getCacheHits() { return cacheHits; }
        public void setCacheHits(long cacheHits) { this.cacheHits = cacheHits; }
        
        public long getCacheMisses() { return cacheMisses; }
        public void setCacheMisses(long cacheMisses) { this.cacheMisses = cacheMisses; }
        
        public long getErrors() { return errors; }
        public void setErrors(long errors) { this.errors = errors; }
        
        public long getQuantumOperations() { return quantumOperations; }
        public void setQuantumOperations(long quantumOperations) { this.quantumOperations = quantumOperations; }
        
        public long getTraditionalOperations() { return traditionalOperations; }
        public void setTraditionalOperations(long traditionalOperations) { this.traditionalOperations = traditionalOperations; }
        
        public double getHitRate() { return hitRate; }
        public void setHitRate(double hitRate) { this.hitRate = hitRate; }
        
        public long getAverageLatencyNanos() { return averageLatencyNanos; }
        public void setAverageLatencyNanos(long averageLatencyNanos) { this.averageLatencyNanos = averageLatencyNanos; }
        
        public long getMinLatencyNanos() { return minLatencyNanos; }
        public void setMinLatencyNanos(long minLatencyNanos) { this.minLatencyNanos = minLatencyNanos; }
        
        public long getMaxLatencyNanos() { return maxLatencyNanos; }
        public void setMaxLatencyNanos(long maxLatencyNanos) { this.maxLatencyNanos = maxLatencyNanos; }
        
        public double getCurrentQPS() { return currentQPS; }
        public void setCurrentQPS(double currentQPS) { this.currentQPS = currentQPS; }
    }
}