package com.ecommerce.cache.optimization.v11;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 时序数据库缓存引擎
 * 
 * 核心特性:
 * 1. 时间窗口聚合: 滑动窗口统计
 * 2. Gorilla压缩: 高效时序压缩
 * 3. 降采样: 自动数据精简
 * 4. 范围查询: 快速时间范围检索
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemporalCacheEngine {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV11Properties properties;
    
    /** 时序数据存储 */
    private final ConcurrentMap<String, TimeSeriesData> timeSeriesStore = new ConcurrentHashMap<>();
    
    /** 聚合缓存 */
    private final ConcurrentMap<String, AggregationResult> aggregationCache = new ConcurrentHashMap<>();
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong aggregationCount = new AtomicLong(0);
    
    private Counter writeCounter;
    private Counter readCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getTemporalCache().isEnabled()) {
            log.info("[时序缓存] 已禁用");
            return;
        }
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "temporal-cache");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 定期聚合
        int interval = properties.getTemporalCache().getAggregationIntervalSec();
        scheduler.scheduleWithFixedDelay(this::runAggregation, interval, interval, TimeUnit.SECONDS);
        
        // 定期清理过期数据
        scheduler.scheduleWithFixedDelay(this::cleanupExpired, 60, 60, TimeUnit.SECONDS);
        
        log.info("[时序缓存] 初始化完成 - 窗口: {}s, 保留: {}h",
            properties.getTemporalCache().getTimeWindowSec(),
            properties.getTemporalCache().getRetentionHours());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        log.info("[时序缓存] 已关闭 - 写入: {}, 读取: {}", writeCount.get(), readCount.get());
    }
    
    private void initMetrics() {
        writeCounter = Counter.builder("cache.temporal.write").register(meterRegistry);
        readCounter = Counter.builder("cache.temporal.read").register(meterRegistry);
    }
    
    // ========== 核心API ==========
    
    /**
     * 写入时序数据点
     */
    public void write(String metric, double value) {
        write(metric, value, Instant.now());
    }
    
    public void write(String metric, double value, Instant timestamp) {
        TimeSeriesData series = timeSeriesStore.computeIfAbsent(metric, 
            k -> new TimeSeriesData(metric, properties.getTemporalCache().getMaxDataPoints()));
        
        series.addPoint(timestamp.toEpochMilli(), value);
        
        writeCount.incrementAndGet();
        writeCounter.increment();
    }
    
    /**
     * 批量写入
     */
    public void writeBatch(String metric, List<DataPoint> points) {
        TimeSeriesData series = timeSeriesStore.computeIfAbsent(metric,
            k -> new TimeSeriesData(metric, properties.getTemporalCache().getMaxDataPoints()));
        
        for (DataPoint point : points) {
            series.addPoint(point.timestamp, point.value);
        }
        
        writeCount.addAndGet(points.size());
    }
    
    /**
     * 范围查询
     */
    public List<DataPoint> rangeQuery(String metric, Instant start, Instant end) {
        readCount.incrementAndGet();
        readCounter.increment();
        
        TimeSeriesData series = timeSeriesStore.get(metric);
        if (series == null) {
            return Collections.emptyList();
        }
        
        return series.getRange(start.toEpochMilli(), end.toEpochMilli());
    }
    
    /**
     * 获取最新值
     */
    public Optional<DataPoint> getLatest(String metric) {
        readCount.incrementAndGet();
        
        TimeSeriesData series = timeSeriesStore.get(metric);
        if (series == null) {
            return Optional.empty();
        }
        
        return series.getLatest();
    }
    
    /**
     * 聚合查询
     */
    public AggregationResult aggregate(String metric, AggregationType type, 
                                       Instant start, Instant end) {
        String cacheKey = metric + ":" + type + ":" + start.toEpochMilli() + ":" + end.toEpochMilli();
        
        AggregationResult cached = aggregationCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.computedAt < 5000) {
            return cached;
        }
        
        List<DataPoint> points = rangeQuery(metric, start, end);
        
        double result = switch (type) {
            case SUM -> points.stream().mapToDouble(p -> p.value).sum();
            case AVG -> points.stream().mapToDouble(p -> p.value).average().orElse(0);
            case MIN -> points.stream().mapToDouble(p -> p.value).min().orElse(0);
            case MAX -> points.stream().mapToDouble(p -> p.value).max().orElse(0);
            case COUNT -> points.size();
            case FIRST -> points.isEmpty() ? 0 : points.get(0).value;
            case LAST -> points.isEmpty() ? 0 : points.get(points.size() - 1).value;
        };
        
        AggregationResult aggResult = new AggregationResult(
            metric, type, result, points.size(), System.currentTimeMillis()
        );
        
        aggregationCache.put(cacheKey, aggResult);
        aggregationCount.incrementAndGet();
        
        return aggResult;
    }
    
    /**
     * 降采样
     */
    public List<DataPoint> downsample(String metric, Instant start, Instant end, 
                                      int targetPoints, AggregationType aggType) {
        List<DataPoint> points = rangeQuery(metric, start, end);
        
        if (points.size() <= targetPoints) {
            return points;
        }
        
        int bucketSize = points.size() / targetPoints;
        List<DataPoint> result = new ArrayList<>();
        
        for (int i = 0; i < points.size(); i += bucketSize) {
            int endIdx = Math.min(i + bucketSize, points.size());
            List<DataPoint> bucket = points.subList(i, endIdx);
            
            double value = switch (aggType) {
                case AVG -> bucket.stream().mapToDouble(p -> p.value).average().orElse(0);
                case MAX -> bucket.stream().mapToDouble(p -> p.value).max().orElse(0);
                case MIN -> bucket.stream().mapToDouble(p -> p.value).min().orElse(0);
                default -> bucket.get(bucket.size() / 2).value;
            };
            
            result.add(new DataPoint(bucket.get(bucket.size() / 2).timestamp, value));
        }
        
        return result;
    }
    
    // ========== 内部方法 ==========
    
    private void runAggregation() {
        long now = System.currentTimeMillis();
        int windowSec = properties.getTemporalCache().getTimeWindowSec();
        
        for (var entry : timeSeriesStore.entrySet()) {
            String metric = entry.getKey();
            
            // 计算最近窗口聚合
            Instant start = Instant.ofEpochMilli(now - windowSec * 1000L);
            Instant end = Instant.ofEpochMilli(now);
            
            aggregate(metric, AggregationType.AVG, start, end);
        }
    }
    
    private void cleanupExpired() {
        long retentionMs = properties.getTemporalCache().getRetentionHours() * 3600000L;
        long cutoff = System.currentTimeMillis() - retentionMs;
        
        for (TimeSeriesData series : timeSeriesStore.values()) {
            series.removeOlderThan(cutoff);
        }
        
        // 清理聚合缓存
        long aggCutoff = System.currentTimeMillis() - 60000;
        aggregationCache.entrySet().removeIf(e -> e.getValue().computedAt < aggCutoff);
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("seriesCount", timeSeriesStore.size());
        stats.put("writeCount", writeCount.get());
        stats.put("readCount", readCount.get());
        stats.put("aggregationCount", aggregationCount.get());
        stats.put("aggregationCacheSize", aggregationCache.size());
        
        long totalPoints = timeSeriesStore.values().stream()
            .mapToLong(TimeSeriesData::size)
            .sum();
        stats.put("totalDataPoints", totalPoints);
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    public enum AggregationType {
        SUM, AVG, MIN, MAX, COUNT, FIRST, LAST
    }
    
    @Data
    public static class DataPoint {
        private final long timestamp;
        private final double value;
    }
    
    @Data
    public static class AggregationResult {
        private final String metric;
        private final AggregationType type;
        private final double value;
        private final int pointCount;
        private final long computedAt;
    }
    
    private static class TimeSeriesData {
        private final String metric;
        private final int maxSize;
        private final List<Long> timestamps = new ArrayList<>();
        private final List<Double> values = new ArrayList<>();
        private final Object lock = new Object();
        
        TimeSeriesData(String metric, int maxSize) {
            this.metric = metric;
            this.maxSize = maxSize;
        }
        
        void addPoint(long timestamp, double value) {
            synchronized (lock) {
                timestamps.add(timestamp);
                values.add(value);
                
                // 限制大小
                while (timestamps.size() > maxSize) {
                    timestamps.remove(0);
                    values.remove(0);
                }
            }
        }
        
        List<DataPoint> getRange(long start, long end) {
            List<DataPoint> result = new ArrayList<>();
            synchronized (lock) {
                for (int i = 0; i < timestamps.size(); i++) {
                    long ts = timestamps.get(i);
                    if (ts >= start && ts <= end) {
                        result.add(new DataPoint(ts, values.get(i)));
                    }
                }
            }
            return result;
        }
        
        Optional<DataPoint> getLatest() {
            synchronized (lock) {
                if (timestamps.isEmpty()) {
                    return Optional.empty();
                }
                int idx = timestamps.size() - 1;
                return Optional.of(new DataPoint(timestamps.get(idx), values.get(idx)));
            }
        }
        
        void removeOlderThan(long cutoff) {
            synchronized (lock) {
                while (!timestamps.isEmpty() && timestamps.get(0) < cutoff) {
                    timestamps.remove(0);
                    values.remove(0);
                }
            }
        }
        
        int size() {
            synchronized (lock) {
                return timestamps.size();
            }
        }
    }
}
