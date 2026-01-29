package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 数据库优化模块
 * 
 * 核心优化：
 * 1. 查询分析器 - 自动分析慢查询并给出优化建议
 * 2. 索引建议器 - 基于查询模式推荐索引
 * 3. 分表路由器 - 支持按时间/ID分表
 * 4. 批量操作优化器 - 高效批量插入/更新
 * 5. 连接池监控 - 实时监控连接池状态
 * 6. 读写分离路由 - 智能路由到主从库
 */
@Service
public class DatabaseOptimizer {
    
    private static final Logger log = LoggerFactory.getLogger(DatabaseOptimizer.class);
    
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final SlowQueryAnalyzer slowQueryAnalyzer;
    private final IndexAdvisor indexAdvisor;
    private final ShardingRouter shardingRouter;
    
    public DatabaseOptimizer(JdbcTemplate jdbcTemplate, 
                            MeterRegistry meterRegistry,
                            SlowQueryAnalyzer slowQueryAnalyzer,
                            IndexAdvisor indexAdvisor,
                            ShardingRouter shardingRouter) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.slowQueryAnalyzer = slowQueryAnalyzer;
        this.indexAdvisor = indexAdvisor;
        this.shardingRouter = shardingRouter;
    }
    
    /**
     * 执行带监控的查询
     */
    public <T> T executeWithMonitoring(String queryName, java.util.function.Supplier<T> query) {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startTime = System.currentTimeMillis();
        
        try {
            T result = query.get();
            sample.stop(Timer.builder("db.query.duration")
                .tag("query", queryName)
                .tag("status", "success")
                .register(meterRegistry));
            return result;
        } catch (Exception e) {
            sample.stop(Timer.builder("db.query.duration")
                .tag("query", queryName)
                .tag("status", "error")
                .register(meterRegistry));
            
            long duration = System.currentTimeMillis() - startTime;
            slowQueryAnalyzer.recordQuery(queryName, duration, e.getMessage());
            
            throw e;
        }
    }
    
    /**
     * 获取数据库优化报告
     */
    public OptimizationReport getOptimizationReport() {
        return new OptimizationReport(
            slowQueryAnalyzer.getSlowQueries(),
            indexAdvisor.getRecommendations(),
            getConnectionPoolStats(),
            shardingRouter.getShardingStats()
        );
    }
    
    private Map<String, Object> getConnectionPoolStats() {
        Map<String, Object> stats = new HashMap<>();
        // 从 HikariCP 获取连接池统计
        // 实际实现需要注入 HikariDataSource
        stats.put("activeConnections", 0);
        stats.put("idleConnections", 0);
        stats.put("totalConnections", 0);
        stats.put("pendingThreads", 0);
        return stats;
    }
    
    public record OptimizationReport(
        List<SlowQueryInfo> slowQueries,
        List<IndexRecommendation> indexRecommendations,
        Map<String, Object> connectionPoolStats,
        Map<String, Object> shardingStats
    ) {}
    
    public record SlowQueryInfo(String query, long avgDurationMs, long maxDurationMs, long count, String suggestion) {}
    public record IndexRecommendation(String table, String column, String reason, int priority) {}
}

/**
 * 慢查询分析器
 */
@Component
class SlowQueryAnalyzer {
    
    private static final Logger log = LoggerFactory.getLogger(SlowQueryAnalyzer.class);
    
    // 查询统计
    private final ConcurrentHashMap<String, QueryStats> queryStatsMap = new ConcurrentHashMap<>();
    
    // 慢查询阈值（毫秒）
    @Value("${optimization.database.slow-query-threshold-ms:100}")
    private long slowQueryThreshold;
    
    // 最近慢查询列表
    private final ConcurrentLinkedQueue<SlowQuery> recentSlowQueries = new ConcurrentLinkedQueue<>();
    private static final int MAX_SLOW_QUERIES = 100;
    
    private final Counter slowQueryCounter;
    
    public SlowQueryAnalyzer(MeterRegistry meterRegistry) {
        this.slowQueryCounter = Counter.builder("db.slow.queries").register(meterRegistry);
        
        Gauge.builder("db.slow.queries.recent", recentSlowQueries, ConcurrentLinkedQueue::size)
            .register(meterRegistry);
    }
    
    /**
     * 记录查询
     */
    public void recordQuery(String query, long durationMs, String error) {
        QueryStats stats = queryStatsMap.computeIfAbsent(normalizeQuery(query), k -> new QueryStats());
        stats.record(durationMs);
        
        if (durationMs > slowQueryThreshold) {
            slowQueryCounter.increment();
            recordSlowQuery(query, durationMs, error);
        }
    }
    
    private void recordSlowQuery(String query, long durationMs, String error) {
        SlowQuery slowQuery = new SlowQuery(query, durationMs, error, Instant.now());
        recentSlowQueries.offer(slowQuery);
        
        while (recentSlowQueries.size() > MAX_SLOW_QUERIES) {
            recentSlowQueries.poll();
        }
        
        log.warn("Slow query detected: {}ms - {}", durationMs, truncate(query, 200));
    }
    
    /**
     * 规范化查询（移除参数值）
     */
    private String normalizeQuery(String query) {
        return query.replaceAll("'[^']*'", "?")
                    .replaceAll("\\d+", "?")
                    .replaceAll("\\s+", " ")
                    .trim();
    }
    
    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
    
    /**
     * 获取慢查询列表及优化建议
     */
    public List<DatabaseOptimizer.SlowQueryInfo> getSlowQueries() {
        return queryStatsMap.entrySet().stream()
            .filter(e -> e.getValue().maxDuration > slowQueryThreshold)
            .sorted((a, b) -> Long.compare(b.getValue().maxDuration, a.getValue().maxDuration))
            .limit(20)
            .map(e -> new DatabaseOptimizer.SlowQueryInfo(
                e.getKey(),
                e.getValue().getAverageDuration(),
                e.getValue().maxDuration,
                e.getValue().count.sum(),
                generateSuggestion(e.getKey())
            ))
            .toList();
    }
    
    /**
     * 生成优化建议
     */
    private String generateSuggestion(String query) {
        String lowerQuery = query.toLowerCase();
        List<String> suggestions = new ArrayList<>();
        
        if (lowerQuery.contains("select *")) {
            suggestions.add("Avoid SELECT *, specify required columns");
        }
        if (!lowerQuery.contains("limit") && lowerQuery.contains("select")) {
            suggestions.add("Consider adding LIMIT clause");
        }
        if (lowerQuery.contains("like '%")) {
            suggestions.add("Leading wildcard in LIKE prevents index usage");
        }
        if (lowerQuery.contains("or ")) {
            suggestions.add("Consider using UNION instead of OR for better index usage");
        }
        if (!lowerQuery.contains("where") && (lowerQuery.contains("update") || lowerQuery.contains("delete"))) {
            suggestions.add("Warning: UPDATE/DELETE without WHERE clause");
        }
        if (lowerQuery.contains("order by") && !lowerQuery.contains("limit")) {
            suggestions.add("ORDER BY without LIMIT may cause full table scan");
        }
        
        return suggestions.isEmpty() ? "Check EXPLAIN plan for index usage" : String.join("; ", suggestions);
    }
    
    public List<SlowQuery> getRecentSlowQueries() {
        return new ArrayList<>(recentSlowQueries);
    }
    
    record SlowQuery(String query, long durationMs, String error, Instant time) {}
    
    static class QueryStats {
        final LongAdder count = new LongAdder();
        final LongAdder totalDuration = new LongAdder();
        volatile long maxDuration = 0;
        
        void record(long duration) {
            count.increment();
            totalDuration.add(duration);
            if (duration > maxDuration) {
                maxDuration = duration;
            }
        }
        
        long getAverageDuration() {
            long c = count.sum();
            return c > 0 ? totalDuration.sum() / c : 0;
        }
    }
}

/**
 * 索引建议器
 */
@Component
class IndexAdvisor {
    
    private static final Logger log = LoggerFactory.getLogger(IndexAdvisor.class);
    
    // 查询模式统计
    private final ConcurrentHashMap<String, QueryPattern> queryPatterns = new ConcurrentHashMap<>();
    
    /**
     * 记录查询模式
     */
    public void recordQueryPattern(String table, List<String> whereColumns, List<String> orderColumns) {
        String patternKey = table + ":" + String.join(",", whereColumns) + ":" + String.join(",", orderColumns);
        queryPatterns.computeIfAbsent(patternKey, k -> new QueryPattern(table, whereColumns, orderColumns))
            .increment();
    }
    
    /**
     * 获取索引推荐
     */
    public List<DatabaseOptimizer.IndexRecommendation> getRecommendations() {
        return queryPatterns.values().stream()
            .filter(p -> p.count.sum() > 100) // 至少执行100次
            .flatMap(p -> generateRecommendations(p).stream())
            .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
            .limit(10)
            .toList();
    }
    
    private List<DatabaseOptimizer.IndexRecommendation> generateRecommendations(QueryPattern pattern) {
        List<DatabaseOptimizer.IndexRecommendation> recommendations = new ArrayList<>();
        
        // 基于 WHERE 列推荐索引
        if (!pattern.whereColumns.isEmpty()) {
            String columns = String.join(", ", pattern.whereColumns);
            recommendations.add(new DatabaseOptimizer.IndexRecommendation(
                pattern.table,
                columns,
                String.format("Frequently used in WHERE clause (%d times)", pattern.count.sum()),
                calculatePriority(pattern)
            ));
        }
        
        // 基于 ORDER BY 列推荐索引
        if (!pattern.orderColumns.isEmpty()) {
            String columns = String.join(", ", pattern.orderColumns);
            recommendations.add(new DatabaseOptimizer.IndexRecommendation(
                pattern.table,
                columns,
                String.format("Frequently used in ORDER BY (%d times)", pattern.count.sum()),
                calculatePriority(pattern) - 1
            ));
        }
        
        // 组合索引推荐
        if (!pattern.whereColumns.isEmpty() && !pattern.orderColumns.isEmpty()) {
            List<String> combined = new ArrayList<>(pattern.whereColumns);
            combined.addAll(pattern.orderColumns);
            String columns = String.join(", ", combined);
            recommendations.add(new DatabaseOptimizer.IndexRecommendation(
                pattern.table,
                columns,
                "Composite index for WHERE + ORDER BY",
                calculatePriority(pattern) + 1
            ));
        }
        
        return recommendations;
    }
    
    private int calculatePriority(QueryPattern pattern) {
        // 基于执行次数计算优先级
        long count = pattern.count.sum();
        if (count > 10000) return 10;
        if (count > 5000) return 8;
        if (count > 1000) return 6;
        if (count > 500) return 4;
        return 2;
    }
    
    static class QueryPattern {
        final String table;
        final List<String> whereColumns;
        final List<String> orderColumns;
        final LongAdder count = new LongAdder();
        
        QueryPattern(String table, List<String> whereColumns, List<String> orderColumns) {
            this.table = table;
            this.whereColumns = whereColumns;
            this.orderColumns = orderColumns;
        }
        
        void increment() {
            count.increment();
        }
    }
}

/**
 * 分表路由器
 */
@Component
class ShardingRouter {
    
    private static final Logger log = LoggerFactory.getLogger(ShardingRouter.class);
    
    // 分表配置
    @Value("${optimization.database.sharding.enabled:false}")
    private boolean enabled;
    
    @Value("${optimization.database.sharding.table-count:16}")
    private int tableCount;
    
    @Value("${optimization.database.sharding.strategy:hash}")
    private String strategy; // hash, range, time
    
    // 路由统计
    private final ConcurrentHashMap<String, LongAdder> routeStats = new ConcurrentHashMap<>();
    
    private final Counter routeCounter;
    
    public ShardingRouter(MeterRegistry meterRegistry) {
        this.routeCounter = Counter.builder("db.sharding.routes").register(meterRegistry);
    }
    
    /**
     * 根据分片键获取目标表名
     */
    public String route(String baseTable, Object shardKey) {
        if (!enabled) {
            return baseTable;
        }
        
        routeCounter.increment();
        
        String targetTable = switch (strategy) {
            case "hash" -> routeByHash(baseTable, shardKey);
            case "range" -> routeByRange(baseTable, shardKey);
            case "time" -> routeByTime(baseTable, shardKey);
            default -> baseTable;
        };
        
        routeStats.computeIfAbsent(targetTable, k -> new LongAdder()).increment();
        
        return targetTable;
    }
    
    private String routeByHash(String baseTable, Object shardKey) {
        int hash = Math.abs(shardKey.hashCode());
        int shardIndex = hash % tableCount;
        return String.format("%s_%02d", baseTable, shardIndex);
    }
    
    private String routeByRange(String baseTable, Object shardKey) {
        if (shardKey instanceof Number num) {
            long value = num.longValue();
            long rangeSize = Long.MAX_VALUE / tableCount;
            int shardIndex = (int) (value / rangeSize);
            return String.format("%s_%02d", baseTable, Math.min(shardIndex, tableCount - 1));
        }
        return routeByHash(baseTable, shardKey);
    }
    
    private String routeByTime(String baseTable, Object shardKey) {
        if (shardKey instanceof java.time.LocalDate date) {
            return String.format("%s_%d%02d", baseTable, date.getYear(), date.getMonthValue());
        }
        if (shardKey instanceof java.time.LocalDateTime dateTime) {
            return String.format("%s_%d%02d", baseTable, dateTime.getYear(), dateTime.getMonthValue());
        }
        return baseTable;
    }
    
    /**
     * 获取所有分表名
     */
    public List<String> getAllShardTables(String baseTable) {
        List<String> tables = new ArrayList<>();
        for (int i = 0; i < tableCount; i++) {
            tables.add(String.format("%s_%02d", baseTable, i));
        }
        return tables;
    }
    
    public Map<String, Object> getShardingStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", enabled);
        stats.put("strategy", strategy);
        stats.put("tableCount", tableCount);
        stats.put("routeDistribution", routeStats.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum())));
        return stats;
    }
}

/**
 * 批量操作优化器
 */
@Component
class BatchOperationOptimizer {
    
    private static final Logger log = LoggerFactory.getLogger(BatchOperationOptimizer.class);
    
    private final JdbcTemplate jdbcTemplate;
    
    @Value("${optimization.database.batch.size:1000}")
    private int batchSize;
    
    @Value("${optimization.database.batch.parallel:4}")
    private int parallelism;
    
    private final Timer batchInsertTimer;
    private final Timer batchUpdateTimer;
    private final Counter batchCounter;
    
    public BatchOperationOptimizer(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.batchInsertTimer = Timer.builder("db.batch.insert.duration").register(meterRegistry);
        this.batchUpdateTimer = Timer.builder("db.batch.update.duration").register(meterRegistry);
        this.batchCounter = Counter.builder("db.batch.operations").register(meterRegistry);
    }
    
    /**
     * 批量插入优化
     */
    public <T> int batchInsert(String sql, List<T> items, Function<T, Object[]> paramExtractor) {
        if (items.isEmpty()) return 0;
        
        return batchInsertTimer.record(() -> {
            int total = 0;
            
            // 分批处理
            for (int i = 0; i < items.size(); i += batchSize) {
                int end = Math.min(i + batchSize, items.size());
                List<T> batch = items.subList(i, end);
                
                List<Object[]> batchArgs = batch.stream()
                    .map(paramExtractor)
                    .toList();
                
                int[] results = jdbcTemplate.batchUpdate(sql, batchArgs);
                total += Arrays.stream(results).sum();
                
                batchCounter.increment();
            }
            
            log.debug("Batch insert completed: {} rows", total);
            return total;
        });
    }
    
    /**
     * 并行批量处理
     */
    public <T, R> List<R> parallelBatchProcess(List<T> items, Function<List<T>, List<R>> processor) {
        if (items.isEmpty()) return Collections.emptyList();
        
        // 分片
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            batches.add(items.subList(i, Math.min(i + batchSize, items.size())));
        }
        
        // 并行处理
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            return pool.submit(() -> batches.parallelStream()
                .flatMap(batch -> processor.apply(batch).stream())
                .toList()
            ).get();
        } catch (Exception e) {
            log.error("Parallel batch processing failed", e);
            return Collections.emptyList();
        } finally {
            pool.shutdown();
        }
    }
    
    /**
     * 智能 UPSERT（存在则更新，不存在则插入）
     */
    public int upsert(String table, Map<String, Object> data, List<String> keyColumns) {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(table).append(" (");
        sql.append(String.join(", ", data.keySet()));
        sql.append(") VALUES (");
        sql.append(data.keySet().stream().map(k -> "?").collect(Collectors.joining(", ")));
        sql.append(") ON DUPLICATE KEY UPDATE ");
        sql.append(data.keySet().stream()
            .filter(k -> !keyColumns.contains(k))
            .map(k -> k + " = VALUES(" + k + ")")
            .collect(Collectors.joining(", ")));
        
        return jdbcTemplate.update(sql.toString(), data.values().toArray());
    }
}

/**
 * 读写分离路由器
 */
@Component
class ReadWriteRouter {
    
    private static final Logger log = LoggerFactory.getLogger(ReadWriteRouter.class);
    
    // 当前是否强制使用主库（事务中）
    private static final ThreadLocal<Boolean> FORCE_MASTER = ThreadLocal.withInitial(() -> false);
    
    @Value("${optimization.database.read-write-split.enabled:false}")
    private boolean enabled;
    
    private final Counter masterReadCounter;
    private final Counter slaveReadCounter;
    private final Counter writeCounter;
    
    public ReadWriteRouter(MeterRegistry meterRegistry) {
        this.masterReadCounter = Counter.builder("db.route.master.read").register(meterRegistry);
        this.slaveReadCounter = Counter.builder("db.route.slave.read").register(meterRegistry);
        this.writeCounter = Counter.builder("db.route.write").register(meterRegistry);
    }
    
    /**
     * 获取数据源类型
     */
    public DataSourceType getTargetDataSource(boolean isWrite) {
        if (!enabled) {
            return DataSourceType.MASTER;
        }
        
        if (isWrite || FORCE_MASTER.get()) {
            writeCounter.increment();
            return DataSourceType.MASTER;
        }
        
        slaveReadCounter.increment();
        return DataSourceType.SLAVE;
    }
    
    /**
     * 强制使用主库
     */
    public void forceMaster() {
        FORCE_MASTER.set(true);
    }
    
    /**
     * 清除强制主库标记
     */
    public void clearForceMaster() {
        FORCE_MASTER.remove();
    }
    
    /**
     * 在主库执行
     */
    public <T> T executeOnMaster(java.util.function.Supplier<T> action) {
        try {
            forceMaster();
            return action.get();
        } finally {
            clearForceMaster();
        }
    }
    
    public enum DataSourceType { MASTER, SLAVE }
}

/**
 * 连接池监控器
 */
@Component
class ConnectionPoolMonitor {
    
    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolMonitor.class);
    
    private final MeterRegistry meterRegistry;
    
    // 连接使用统计
    private final LongAdder acquireCount = new LongAdder();
    private final LongAdder releaseCount = new LongAdder();
    private final LongAdder timeoutCount = new LongAdder();
    
    // 连接使用时长统计
    private final ConcurrentLinkedQueue<Long> connectionUsageTimes = new ConcurrentLinkedQueue<>();
    private static final int MAX_USAGE_SAMPLES = 1000;
    
    public ConnectionPoolMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        Counter.builder("db.connection.acquire").register(meterRegistry);
        Counter.builder("db.connection.release").register(meterRegistry);
        Counter.builder("db.connection.timeout").register(meterRegistry);
    }
    
    public void recordAcquire() {
        acquireCount.increment();
    }
    
    public void recordRelease(long usageTimeMs) {
        releaseCount.increment();
        connectionUsageTimes.offer(usageTimeMs);
        
        while (connectionUsageTimes.size() > MAX_USAGE_SAMPLES) {
            connectionUsageTimes.poll();
        }
    }
    
    public void recordTimeout() {
        timeoutCount.increment();
    }
    
    public ConnectionPoolStats getStats() {
        long[] times = connectionUsageTimes.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(times);
        
        return new ConnectionPoolStats(
            acquireCount.sum(),
            releaseCount.sum(),
            timeoutCount.sum(),
            times.length > 0 ? times[times.length / 2] : 0, // P50
            times.length > 0 ? times[(int)(times.length * 0.99)] : 0 // P99
        );
    }
    
    /**
     * 定期检查连接池健康
     */
    @Scheduled(fixedRate = 30000)
    public void checkHealth() {
        long acquires = acquireCount.sum();
        long releases = releaseCount.sum();
        long leaks = acquires - releases;
        
        if (leaks > 10) {
            log.warn("Potential connection leak detected: {} unreleased connections", leaks);
        }
        
        long timeouts = timeoutCount.sum();
        if (timeouts > 0) {
            log.warn("Connection pool timeouts detected: {}", timeouts);
        }
    }
    
    public record ConnectionPoolStats(
        long acquires, long releases, long timeouts, long p50UsageMs, long p99UsageMs
    ) {}
}
