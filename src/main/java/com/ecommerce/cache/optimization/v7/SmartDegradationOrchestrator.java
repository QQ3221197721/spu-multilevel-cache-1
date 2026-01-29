package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 智能降级编排器
 * 
 * 核心特性:
 * 1. 多级降级策略: L1-L5五级降级，逐步限制功能
 * 2. 自动检测: 基于错误率、延迟的自动降级触发
 * 3. 智能恢复: 根据恢复指标自动升级
 * 4. 静态响应: 极端情况下的静态兜底
 * 5. 本地缓存降级: 利用本地缓存作为最后防线
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartDegradationOrchestrator {
    
    private final OptimizationV7Properties properties;
    private final MeterRegistry meterRegistry;
    
    // ========== 降级状态 ==========
    
    /** 当前降级级别 */
    private final AtomicInteger currentLevel = new AtomicInteger(0);
    
    /** 降级级别定义 */
    private static final int LEVEL_NORMAL = 0;
    private static final int LEVEL_WARNING = 1;   // 限流
    private static final int LEVEL_DEGRADED = 2;  // 禁用非核心功能
    private static final int LEVEL_CRITICAL = 3;  // 只读模式
    private static final int LEVEL_EMERGENCY = 4; // 静态响应
    private static final int LEVEL_SHUTDOWN = 5;  // 完全熔断
    
    /** 降级状态映射 */
    private final ConcurrentMap<String, DegradationState> serviceStates = new ConcurrentHashMap<>();
    
    /** 静态响应缓存 */
    private final ConcurrentMap<String, CachedResponse<?>> staticResponses = new ConcurrentHashMap<>();
    
    /** 本地缓存降级存储 */
    private final ConcurrentMap<String, LocalCacheEntry> localFallbackCache = new ConcurrentHashMap<>();
    
    /** 降级历史记录 */
    private final Queue<DegradationEvent> degradationHistory = new ConcurrentLinkedQueue<>();
    
    /** 恢复任务调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 降级策略注册表 */
    private final ConcurrentMap<String, DegradationStrategy<?>> strategies = new ConcurrentHashMap<>();
    
    /** 最后降级时间 */
    private final AtomicLong lastDegradationTime = new AtomicLong(0);
    
    // ========== 指标 ==========
    
    private Counter degradationTriggerCounter;
    private Counter recoveryCounter;
    private Counter fallbackCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.isSmartDegradationEnabled()) {
            log.info("[智能降级编排器] 未启用");
            return;
        }
        
        // 初始化调度器
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "degradation-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化指标
        initMetrics();
        
        // 启动后台任务
        startBackgroundTasks();
        
        // 注册默认降级策略
        registerDefaultStrategies();
        
        log.info("[智能降级编排器] 初始化完成 - 降级级别数: {}, 自动恢复: {}",
            properties.getSmartDegradation().getDegradationLevels(),
            properties.getSmartDegradation().isAutoRecoveryEnabled());
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        log.info("[智能降级编排器] 已关闭");
    }
    
    private void initMetrics() {
        degradationTriggerCounter = Counter.builder("cache.degradation.trigger")
            .description("降级触发次数")
            .register(meterRegistry);
        
        recoveryCounter = Counter.builder("cache.degradation.recovery")
            .description("降级恢复次数")
            .register(meterRegistry);
        
        fallbackCounter = Counter.builder("cache.degradation.fallback")
            .description("降级兜底次数")
            .register(meterRegistry);
        
        Gauge.builder("cache.degradation.level", currentLevel, AtomicInteger::get)
            .description("当前降级级别")
            .register(meterRegistry);
    }
    
    private void startBackgroundTasks() {
        var config = properties.getSmartDegradation();
        
        // 降级检测任务
        scheduler.scheduleAtFixedRate(
            this::checkAndUpdateDegradation,
            5,
            config.getDetectionWindowSeconds() / 3,
            TimeUnit.SECONDS
        );
        
        // 恢复检测任务
        if (config.isAutoRecoveryEnabled()) {
            scheduler.scheduleAtFixedRate(
                this::checkAndRecovery,
                config.getRecoveryWindowSeconds(),
                config.getRecoveryWindowSeconds() / 2,
                TimeUnit.SECONDS
            );
        }
        
        // 清理过期静态响应
        scheduler.scheduleAtFixedRate(
            this::cleanupExpiredResponses,
            60,
            60,
            TimeUnit.SECONDS
        );
    }
    
    private void registerDefaultStrategies() {
        // 缓存读取降级策略
        registerStrategy("cache-read", new DegradationStrategy<String>() {
            @Override
            public String execute(String key, Function<String, String> normalOperation, 
                                 Supplier<String> fallback) {
                int level = currentLevel.get();
                
                switch (level) {
                    case LEVEL_NORMAL:
                        return normalOperation.apply(key);
                    case LEVEL_WARNING:
                        // 限流但仍执行
                        if (shouldAllowRequest()) {
                            return normalOperation.apply(key);
                        }
                        return fallback.get();
                    case LEVEL_DEGRADED:
                        // 尝试本地缓存
                        LocalCacheEntry entry = localFallbackCache.get(key);
                        if (entry != null && !entry.isExpired()) {
                            return (String) entry.getValue();
                        }
                        return fallback.get();
                    case LEVEL_CRITICAL:
                    case LEVEL_EMERGENCY:
                    case LEVEL_SHUTDOWN:
                        return fallback.get();
                    default:
                        return normalOperation.apply(key);
                }
            }
        });
    }
    
    // ========== 核心API ==========
    
    /**
     * 记录请求结果
     */
    public void recordRequest(String service, boolean success, long latencyMs) {
        if (!properties.isSmartDegradationEnabled()) return;
        
        DegradationState state = serviceStates.computeIfAbsent(service, k -> new DegradationState());
        state.recordRequest(success, latencyMs);
    }
    
    /**
     * 手动触发降级
     */
    public void triggerDegradation(String reason, int targetLevel) {
        if (!properties.isSmartDegradationEnabled()) return;
        
        int current = currentLevel.get();
        if (targetLevel > current) {
            currentLevel.set(targetLevel);
            lastDegradationTime.set(System.currentTimeMillis());
            degradationTriggerCounter.increment();
            
            recordEvent(DegradationEvent.Type.DOWNGRADE, current, targetLevel, reason);
            log.warn("[智能降级] 触发降级: {} -> {} 原因: {}", current, targetLevel, reason);
        }
    }
    
    /**
     * 执行带降级策略的操作
     */
    @SuppressWarnings("unchecked")
    public <T> T executeWithDegradation(String strategyName, Object key, 
                                        Function<Object, T> normalOperation,
                                        Supplier<T> fallback) {
        if (!properties.isSmartDegradationEnabled()) {
            return normalOperation.apply(key);
        }
        
        DegradationStrategy<T> strategy = (DegradationStrategy<T>) strategies.get(strategyName);
        if (strategy == null) {
            return normalOperation.apply(key);
        }
        
        try {
            long start = System.currentTimeMillis();
            T result = strategy.execute(key, normalOperation, fallback);
            recordRequest(strategyName, true, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            recordRequest(strategyName, false, 0);
            fallbackCounter.increment();
            return fallback.get();
        }
    }
    
    /**
     * 注册降级策略
     */
    public <T> void registerStrategy(String name, DegradationStrategy<T> strategy) {
        strategies.put(name, strategy);
    }
    
    /**
     * 设置静态响应
     */
    public <T> void setStaticResponse(String key, T response, int ttlSeconds) {
        staticResponses.put(key, new CachedResponse<>(
            response,
            System.currentTimeMillis() + ttlSeconds * 1000L
        ));
    }
    
    /**
     * 获取静态响应
     */
    @SuppressWarnings("unchecked")
    public <T> T getStaticResponse(String key) {
        CachedResponse<T> cached = (CachedResponse<T>) staticResponses.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.getValue();
        }
        return null;
    }
    
    /**
     * 更新本地缓存降级数据
     */
    public void updateLocalFallback(String key, Object value, int ttlSeconds) {
        if (!properties.isSmartDegradationEnabled() || 
            !properties.getSmartDegradation().isLocalCacheFallback()) {
            return;
        }
        
        localFallbackCache.put(key, new LocalCacheEntry(
            value,
            System.currentTimeMillis() + ttlSeconds * 1000L
        ));
    }
    
    /**
     * 获取本地降级缓存
     */
    @SuppressWarnings("unchecked")
    public <T> T getLocalFallback(String key) {
        LocalCacheEntry entry = localFallbackCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return (T) entry.getValue();
        }
        return null;
    }
    
    /**
     * 获取当前降级级别
     */
    public int getCurrentLevel() {
        return currentLevel.get();
    }
    
    /**
     * 获取降级级别名称
     */
    public String getLevelName(int level) {
        return switch (level) {
            case LEVEL_NORMAL -> "NORMAL";
            case LEVEL_WARNING -> "WARNING";
            case LEVEL_DEGRADED -> "DEGRADED";
            case LEVEL_CRITICAL -> "CRITICAL";
            case LEVEL_EMERGENCY -> "EMERGENCY";
            case LEVEL_SHUTDOWN -> "SHUTDOWN";
            default -> "UNKNOWN";
        };
    }
    
    /**
     * 判断是否应该拒绝请求
     */
    public boolean shouldRejectRequest() {
        return currentLevel.get() >= LEVEL_SHUTDOWN;
    }
    
    /**
     * 判断是否只读模式
     */
    public boolean isReadOnlyMode() {
        return currentLevel.get() >= LEVEL_CRITICAL;
    }
    
    // ========== 内部方法 ==========
    
    private void checkAndUpdateDegradation() {
        var config = properties.getSmartDegradation();
        
        // 汇总所有服务状态
        double totalErrorRate = 0;
        double totalLatency = 0;
        int count = 0;
        
        for (DegradationState state : serviceStates.values()) {
            totalErrorRate += state.getErrorRate();
            totalLatency += state.getAverageLatency();
            count++;
        }
        
        if (count == 0) return;
        
        double avgErrorRate = totalErrorRate / count;
        int currentLevelValue = currentLevel.get();
        int newLevel = calculateTargetLevel(avgErrorRate);
        
        if (newLevel > currentLevelValue) {
            // 检查冷却时间
            long timeSinceLastDegradation = System.currentTimeMillis() - lastDegradationTime.get();
            if (timeSinceLastDegradation < config.getCooldownSeconds() * 1000L) {
                return;
            }
            
            triggerDegradation("自动检测: 错误率=" + String.format("%.2f%%", avgErrorRate * 100), newLevel);
        }
    }
    
    private int calculateTargetLevel(double errorRate) {
        var config = properties.getSmartDegradation();
        
        if (errorRate >= config.getL4Threshold()) {
            return LEVEL_EMERGENCY;
        } else if (errorRate >= config.getL3Threshold()) {
            return LEVEL_CRITICAL;
        } else if (errorRate >= config.getL2Threshold()) {
            return LEVEL_DEGRADED;
        } else if (errorRate >= config.getL1Threshold()) {
            return LEVEL_WARNING;
        }
        return LEVEL_NORMAL;
    }
    
    private void checkAndRecovery() {
        int current = currentLevel.get();
        if (current == LEVEL_NORMAL) return;
        
        var config = properties.getSmartDegradation();
        
        // 检查是否满足恢复条件
        boolean canRecover = true;
        for (DegradationState state : serviceStates.values()) {
            double errorRate = state.getRecentErrorRate(config.getRecoveryWindowSeconds());
            if (errorRate >= config.getL1Threshold() * 0.5) {
                canRecover = false;
                break;
            }
        }
        
        if (canRecover) {
            int newLevel = Math.max(LEVEL_NORMAL, current - 1);
            if (newLevel < current) {
                currentLevel.set(newLevel);
                recoveryCounter.increment();
                
                recordEvent(DegradationEvent.Type.UPGRADE, current, newLevel, "自动恢复");
                log.info("[智能降级] 恢复: {} -> {}", current, newLevel);
            }
        }
    }
    
    private boolean shouldAllowRequest() {
        // 简单的概率限流
        int level = currentLevel.get();
        double allowRate = switch (level) {
            case LEVEL_WARNING -> 0.8;
            case LEVEL_DEGRADED -> 0.5;
            case LEVEL_CRITICAL -> 0.2;
            default -> 1.0;
        };
        
        return Math.random() < allowRate;
    }
    
    private void cleanupExpiredResponses() {
        staticResponses.entrySet().removeIf(e -> e.getValue().isExpired());
        localFallbackCache.entrySet().removeIf(e -> e.getValue().isExpired());
        
        // 限制历史记录大小
        while (degradationHistory.size() > 1000) {
            degradationHistory.poll();
        }
    }
    
    private void recordEvent(DegradationEvent.Type type, int fromLevel, int toLevel, String reason) {
        degradationHistory.offer(new DegradationEvent(
            System.currentTimeMillis(),
            type,
            fromLevel,
            toLevel,
            reason
        ));
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", properties.isSmartDegradationEnabled());
        stats.put("currentLevel", currentLevel.get());
        stats.put("currentLevelName", getLevelName(currentLevel.get()));
        stats.put("trackedServices", serviceStates.size());
        stats.put("staticResponseCount", staticResponses.size());
        stats.put("localFallbackCount", localFallbackCache.size());
        stats.put("registeredStrategies", strategies.keySet());
        
        // 服务状态
        Map<String, Object> serviceStats = new LinkedHashMap<>();
        for (var entry : serviceStates.entrySet()) {
            DegradationState state = entry.getValue();
            Map<String, Object> svcStat = new LinkedHashMap<>();
            svcStat.put("errorRate", String.format("%.2f%%", state.getErrorRate() * 100));
            svcStat.put("avgLatency", String.format("%.2fms", state.getAverageLatency()));
            svcStat.put("totalRequests", state.getTotalRequests());
            serviceStats.put(entry.getKey(), svcStat);
        }
        stats.put("serviceStates", serviceStats);
        
        // 最近事件
        List<Map<String, Object>> recentEvents = new ArrayList<>();
        degradationHistory.stream()
            .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
            .limit(10)
            .forEach(event -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", event.getType());
                e.put("from", getLevelName(event.getFromLevel()));
                e.put("to", getLevelName(event.getToLevel()));
                e.put("reason", event.getReason());
                e.put("timestamp", new Date(event.getTimestamp()));
                recentEvents.add(e);
            });
        stats.put("recentEvents", recentEvents);
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    /**
     * 降级状态
     */
    private static class DegradationState {
        private final Queue<RequestRecord> records = new ConcurrentLinkedQueue<>();
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong failedRequests = new AtomicLong(0);
        private final AtomicLong totalLatency = new AtomicLong(0);
        
        void recordRequest(boolean success, long latencyMs) {
            long now = System.currentTimeMillis();
            records.offer(new RequestRecord(now, success, latencyMs));
            totalRequests.incrementAndGet();
            totalLatency.addAndGet(latencyMs);
            if (!success) {
                failedRequests.incrementAndGet();
            }
            
            // 清理旧记录
            while (records.size() > 1000) {
                records.poll();
            }
        }
        
        double getErrorRate() {
            long total = totalRequests.get();
            if (total == 0) return 0;
            return (double) failedRequests.get() / total;
        }
        
        double getAverageLatency() {
            long total = totalRequests.get();
            if (total == 0) return 0;
            return (double) totalLatency.get() / total;
        }
        
        long getTotalRequests() {
            return totalRequests.get();
        }
        
        double getRecentErrorRate(int windowSeconds) {
            long cutoff = System.currentTimeMillis() - windowSeconds * 1000L;
            long total = 0;
            long failed = 0;
            
            for (RequestRecord record : records) {
                if (record.timestamp >= cutoff) {
                    total++;
                    if (!record.success) {
                        failed++;
                    }
                }
            }
            
            return total > 0 ? (double) failed / total : 0;
        }
    }
    
    private record RequestRecord(long timestamp, boolean success, long latencyMs) {}
    
    /**
     * 降级事件
     */
    @Data
    private static class DegradationEvent {
        enum Type { DOWNGRADE, UPGRADE, MANUAL }
        
        private final long timestamp;
        private final Type type;
        private final int fromLevel;
        private final int toLevel;
        private final String reason;
    }
    
    /**
     * 缓存响应
     */
    @Data
    private static class CachedResponse<T> {
        private final T value;
        private final long expirationTime;
        
        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
    
    /**
     * 本地缓存条目
     */
    @Data
    private static class LocalCacheEntry {
        private final Object value;
        private final long expirationTime;
        
        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
    
    /**
     * 降级策略接口
     */
    public interface DegradationStrategy<T> {
        T execute(Object key, Function<Object, T> normalOperation, Supplier<T> fallback);
    }
}
