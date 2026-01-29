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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 多租户缓存隔离器
 * 
 * 核心特性:
 * 1. 租户隔离: 不同租户数据完全隔离
 * 2. 配额管理: 支持租户级别的容量配额
 * 3. 优先级控制: 租户级别的访问优先级
 * 4. 动态配置: 运行时调整租户配置
 * 5. 统计分析: 租户级别的使用统计
 * 6. 过期策略: 租户数据独立过期
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiTenantCacheIsolator {
    
    private final OptimizationV7Properties properties;
    private final MeterRegistry meterRegistry;
    
    // ========== 租户管理 ==========
    
    /** 租户配置表 */
    private final ConcurrentMap<String, TenantConfig> tenantConfigs = new ConcurrentHashMap<>();
    
    /** 租户使用统计 */
    private final ConcurrentMap<String, TenantStats> tenantStats = new ConcurrentHashMap<>();
    
    /** 租户数据存储 */
    private final ConcurrentMap<String, ConcurrentMap<String, CacheEntry>> tenantCaches = new ConcurrentHashMap<>();
    
    /** 当前租户(ThreadLocal) */
    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();
    
    /** 默认租户 */
    private static final String DEFAULT_TENANT = "default";
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    // ========== 指标 ==========
    
    private Counter tenantHitCounter;
    private Counter tenantMissCounter;
    private Counter quotaExceededCounter;
    
    @PostConstruct
    public void init() {
        // 初始化调度器
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tenant-cache-cleaner");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化指标
        initMetrics();
        
        // 注册默认租户
        registerTenant(DEFAULT_TENANT, TenantConfig.defaultConfig());
        
        // 启动后台任务
        startBackgroundTasks();
        
        log.info("[多租户隔离器] 初始化完成");
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        log.info("[多租户隔离器] 已关闭");
    }
    
    private void initMetrics() {
        tenantHitCounter = Counter.builder("cache.tenant.hit")
            .description("租户缓存命中次数")
            .register(meterRegistry);
        
        tenantMissCounter = Counter.builder("cache.tenant.miss")
            .description("租户缓存未命中次数")
            .register(meterRegistry);
        
        quotaExceededCounter = Counter.builder("cache.tenant.quota.exceeded")
            .description("配额超限次数")
            .register(meterRegistry);
        
        Gauge.builder("cache.tenant.count", tenantConfigs, Map::size)
            .description("注册租户数")
            .register(meterRegistry);
    }
    
    private void startBackgroundTasks() {
        // 过期数据清理
        scheduler.scheduleAtFixedRate(
            this::cleanupExpiredEntries,
            60,
            60,
            TimeUnit.SECONDS
        );
        
        // 配额检查
        scheduler.scheduleAtFixedRate(
            this::checkQuotas,
            30,
            30,
            TimeUnit.SECONDS
        );
    }
    
    // ========== 租户管理API ==========
    
    /**
     * 注册租户
     */
    public void registerTenant(String tenantId, TenantConfig config) {
        tenantConfigs.put(tenantId, config);
        tenantStats.put(tenantId, new TenantStats(tenantId));
        tenantCaches.put(tenantId, new ConcurrentHashMap<>());
        
        log.info("[多租户隔离器] 注册租户: {} - 配额: {}MB, 优先级: {}",
            tenantId, config.maxCacheSizeMb, config.priority);
    }
    
    /**
     * 注销租户
     */
    public void unregisterTenant(String tenantId) {
        if (DEFAULT_TENANT.equals(tenantId)) {
            log.warn("[多租户隔离器] 不能注销默认租户");
            return;
        }
        
        tenantConfigs.remove(tenantId);
        tenantStats.remove(tenantId);
        tenantCaches.remove(tenantId);
        
        log.info("[多租户隔离器] 注销租户: {}", tenantId);
    }
    
    /**
     * 更新租户配置
     */
    public void updateTenantConfig(String tenantId, TenantConfig config) {
        if (!tenantConfigs.containsKey(tenantId)) {
            registerTenant(tenantId, config);
        } else {
            tenantConfigs.put(tenantId, config);
        }
    }
    
    /**
     * 设置当前租户
     */
    public void setCurrentTenant(String tenantId) {
        currentTenant.set(tenantId);
    }
    
    /**
     * 获取当前租户
     */
    public String getCurrentTenant() {
        String tenant = currentTenant.get();
        return tenant != null ? tenant : DEFAULT_TENANT;
    }
    
    /**
     * 清除当前租户
     */
    public void clearCurrentTenant() {
        currentTenant.remove();
    }
    
    /**
     * 在租户上下文中执行操作
     */
    public <T> T executeInTenantContext(String tenantId, java.util.function.Supplier<T> operation) {
        String previousTenant = getCurrentTenant();
        try {
            setCurrentTenant(tenantId);
            return operation.get();
        } finally {
            if (previousTenant != null) {
                setCurrentTenant(previousTenant);
            } else {
                clearCurrentTenant();
            }
        }
    }
    
    // ========== 缓存操作API ==========
    
    /**
     * 获取缓存(当前租户)
     */
    public <T> T get(String key) {
        return get(getCurrentTenant(), key);
    }
    
    /**
     * 获取缓存(指定租户)
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String tenantId, String key) {
        ConcurrentMap<String, CacheEntry> cache = tenantCaches.get(tenantId);
        if (cache == null) {
            tenantMissCounter.increment();
            recordMiss(tenantId);
            return null;
        }
        
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.isExpired()) {
            tenantMissCounter.increment();
            recordMiss(tenantId);
            if (entry != null) {
                cache.remove(key);
            }
            return null;
        }
        
        tenantHitCounter.increment();
        recordHit(tenantId);
        entry.recordAccess();
        
        return (T) entry.value;
    }
    
    /**
     * 获取或加载(当前租户)
     */
    public <T> T getOrLoad(String key, Function<String, T> loader) {
        return getOrLoad(getCurrentTenant(), key, loader);
    }
    
    /**
     * 获取或加载(指定租户)
     */
    public <T> T getOrLoad(String tenantId, String key, Function<String, T> loader) {
        T value = get(tenantId, key);
        if (value != null) {
            return value;
        }
        
        value = loader.apply(key);
        if (value != null) {
            put(tenantId, key, value);
        }
        
        return value;
    }
    
    /**
     * 存入缓存(当前租户)
     */
    public <T> boolean put(String key, T value) {
        return put(getCurrentTenant(), key, value);
    }
    
    /**
     * 存入缓存(指定租户)
     */
    public <T> boolean put(String tenantId, String key, T value) {
        return put(tenantId, key, value, 0);
    }
    
    /**
     * 存入缓存(带TTL)
     */
    public <T> boolean put(String tenantId, String key, T value, long ttlSeconds) {
        TenantConfig config = tenantConfigs.get(tenantId);
        if (config == null) {
            // 自动创建默认配置
            config = TenantConfig.defaultConfig();
            registerTenant(tenantId, config);
        }
        
        ConcurrentMap<String, CacheEntry> cache = tenantCaches.get(tenantId);
        if (cache == null) {
            return false;
        }
        
        // 检查配额
        TenantStats stats = tenantStats.get(tenantId);
        if (stats != null && stats.currentSizeBytes.get() >= config.maxCacheSizeMb * 1024 * 1024L) {
            quotaExceededCounter.increment();
            
            if (config.evictOnQuotaExceeded) {
                evictLRU(tenantId, config.maxCacheSizeMb * 1024 * 1024L / 10);
            } else {
                log.warn("[多租户隔离器] 租户 {} 配额已满", tenantId);
                return false;
            }
        }
        
        long actualTtl = ttlSeconds > 0 ? ttlSeconds : config.defaultTtlSeconds;
        long expirationTime = actualTtl > 0 ? 
            System.currentTimeMillis() + actualTtl * 1000 : 0;
        
        CacheEntry entry = new CacheEntry(key, value, expirationTime);
        cache.put(key, entry);
        
        recordPut(tenantId, entry.estimatedSize);
        
        return true;
    }
    
    /**
     * 删除缓存(当前租户)
     */
    public boolean remove(String key) {
        return remove(getCurrentTenant(), key);
    }
    
    /**
     * 删除缓存(指定租户)
     */
    public boolean remove(String tenantId, String key) {
        ConcurrentMap<String, CacheEntry> cache = tenantCaches.get(tenantId);
        if (cache == null) {
            return false;
        }
        
        CacheEntry removed = cache.remove(key);
        if (removed != null) {
            recordRemove(tenantId, removed.estimatedSize);
            return true;
        }
        return false;
    }
    
    /**
     * 清空租户缓存
     */
    public void clearTenant(String tenantId) {
        ConcurrentMap<String, CacheEntry> cache = tenantCaches.get(tenantId);
        if (cache != null) {
            cache.clear();
        }
        
        TenantStats stats = tenantStats.get(tenantId);
        if (stats != null) {
            stats.currentSizeBytes.set(0);
            stats.entryCount.set(0);
        }
    }
    
    /**
     * 获取租户缓存大小
     */
    public int getTenantCacheSize(String tenantId) {
        ConcurrentMap<String, CacheEntry> cache = tenantCaches.get(tenantId);
        return cache != null ? cache.size() : 0;
    }
    
    // ========== 内部方法 ==========
    
    private void recordHit(String tenantId) {
        TenantStats stats = tenantStats.get(tenantId);
        if (stats != null) {
            stats.hitCount.incrementAndGet();
        }
    }
    
    private void recordMiss(String tenantId) {
        TenantStats stats = tenantStats.get(tenantId);
        if (stats != null) {
            stats.missCount.incrementAndGet();
        }
    }
    
    private void recordPut(String tenantId, long size) {
        TenantStats stats = tenantStats.get(tenantId);
        if (stats != null) {
            stats.currentSizeBytes.addAndGet(size);
            stats.entryCount.incrementAndGet();
            stats.putCount.incrementAndGet();
        }
    }
    
    private void recordRemove(String tenantId, long size) {
        TenantStats stats = tenantStats.get(tenantId);
        if (stats != null) {
            stats.currentSizeBytes.addAndGet(-size);
            stats.entryCount.decrementAndGet();
        }
    }
    
    private void evictLRU(String tenantId, long targetEvictSize) {
        ConcurrentMap<String, CacheEntry> cache = tenantCaches.get(tenantId);
        if (cache == null) return;
        
        List<CacheEntry> entries = new ArrayList<>(cache.values());
        entries.sort(Comparator.comparingLong(e -> e.lastAccessTime));
        
        long evicted = 0;
        for (CacheEntry entry : entries) {
            if (evicted >= targetEvictSize) break;
            
            cache.remove(entry.key);
            evicted += entry.estimatedSize;
            recordRemove(tenantId, entry.estimatedSize);
        }
        
        log.info("[多租户隔离器] 租户 {} LRU淘汰 {} 字节", tenantId, evicted);
    }
    
    private void cleanupExpiredEntries() {
        for (var entry : tenantCaches.entrySet()) {
            String tenantId = entry.getKey();
            ConcurrentMap<String, CacheEntry> cache = entry.getValue();
            
            List<String> expiredKeys = new ArrayList<>();
            for (var cacheEntry : cache.entrySet()) {
                if (cacheEntry.getValue().isExpired()) {
                    expiredKeys.add(cacheEntry.getKey());
                }
            }
            
            for (String key : expiredKeys) {
                CacheEntry removed = cache.remove(key);
                if (removed != null) {
                    recordRemove(tenantId, removed.estimatedSize);
                }
            }
        }
    }
    
    private void checkQuotas() {
        for (var entry : tenantConfigs.entrySet()) {
            String tenantId = entry.getKey();
            TenantConfig config = entry.getValue();
            TenantStats stats = tenantStats.get(tenantId);
            
            if (stats != null) {
                long maxBytes = config.maxCacheSizeMb * 1024 * 1024L;
                double usage = (double) stats.currentSizeBytes.get() / maxBytes;
                
                if (usage > 0.9) {
                    log.warn("[多租户隔离器] 租户 {} 配额使用率: {:.1f}%", tenantId, usage * 100);
                }
            }
        }
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("tenantCount", tenantConfigs.size());
        stats.put("currentTenant", getCurrentTenant());
        
        // 各租户统计
        Map<String, Object> tenantStatsMap = new LinkedHashMap<>();
        for (var entry : tenantStats.entrySet()) {
            TenantStats ts = entry.getValue();
            TenantConfig config = tenantConfigs.get(entry.getKey());
            
            Map<String, Object> tsMap = new LinkedHashMap<>();
            tsMap.put("entryCount", ts.entryCount.get());
            tsMap.put("currentSize", formatSize(ts.currentSizeBytes.get()));
            tsMap.put("maxSize", config != null ? config.maxCacheSizeMb + "MB" : "N/A");
            tsMap.put("hitCount", ts.hitCount.get());
            tsMap.put("missCount", ts.missCount.get());
            tsMap.put("hitRate", calculateHitRate(ts));
            tsMap.put("priority", config != null ? config.priority : 0);
            
            tenantStatsMap.put(entry.getKey(), tsMap);
        }
        stats.put("tenants", tenantStatsMap);
        
        return stats;
    }
    
    /**
     * 获取租户统计
     */
    public Map<String, Object> getTenantStatistics(String tenantId) {
        TenantStats ts = tenantStats.get(tenantId);
        TenantConfig config = tenantConfigs.get(tenantId);
        
        if (ts == null) {
            return Collections.emptyMap();
        }
        
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("tenantId", tenantId);
        stats.put("entryCount", ts.entryCount.get());
        stats.put("currentSizeBytes", ts.currentSizeBytes.get());
        stats.put("currentSize", formatSize(ts.currentSizeBytes.get()));
        stats.put("maxSizeMb", config != null ? config.maxCacheSizeMb : 0);
        stats.put("hitCount", ts.hitCount.get());
        stats.put("missCount", ts.missCount.get());
        stats.put("putCount", ts.putCount.get());
        stats.put("hitRate", calculateHitRate(ts));
        
        return stats;
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }
    
    private String calculateHitRate(TenantStats ts) {
        long total = ts.hitCount.get() + ts.missCount.get();
        if (total == 0) return "N/A";
        return String.format("%.2f%%", (double) ts.hitCount.get() / total * 100);
    }
    
    // ========== 内部类 ==========
    
    /**
     * 租户配置
     */
    @Data
    public static class TenantConfig {
        /** 最大缓存大小(MB) */
        private int maxCacheSizeMb = 100;
        
        /** 默认TTL(秒) */
        private long defaultTtlSeconds = 3600;
        
        /** 优先级(0-100) */
        private int priority = 50;
        
        /** 配额超限时是否淘汰 */
        private boolean evictOnQuotaExceeded = true;
        
        /** 是否启用隔离 */
        private boolean isolationEnabled = true;
        
        public static TenantConfig defaultConfig() {
            return new TenantConfig();
        }
        
        public static TenantConfig withQuota(int maxSizeMb) {
            TenantConfig config = new TenantConfig();
            config.setMaxCacheSizeMb(maxSizeMb);
            return config;
        }
    }
    
    /**
     * 租户统计
     */
    private static class TenantStats {
        private final String tenantId;
        private final AtomicLong currentSizeBytes = new AtomicLong(0);
        private final AtomicLong entryCount = new AtomicLong(0);
        private final AtomicLong hitCount = new AtomicLong(0);
        private final AtomicLong missCount = new AtomicLong(0);
        private final AtomicLong putCount = new AtomicLong(0);
        
        TenantStats(String tenantId) {
            this.tenantId = tenantId;
        }
    }
    
    /**
     * 缓存条目
     */
    @Data
    private static class CacheEntry {
        private final String key;
        private final Object value;
        private final long expirationTime;
        private final long estimatedSize;
        private volatile long lastAccessTime = System.currentTimeMillis();
        
        CacheEntry(String key, Object value, long expirationTime) {
            this.key = key;
            this.value = value;
            this.expirationTime = expirationTime;
            this.estimatedSize = estimateSize(value);
        }
        
        boolean isExpired() {
            return expirationTime > 0 && System.currentTimeMillis() > expirationTime;
        }
        
        void recordAccess() {
            lastAccessTime = System.currentTimeMillis();
        }
        
        private static long estimateSize(Object value) {
            if (value == null) return 8;
            if (value instanceof String) return ((String) value).length() * 2L + 40;
            if (value instanceof byte[]) return ((byte[]) value).length + 16L;
            if (value instanceof Map) return ((Map<?, ?>) value).size() * 100L + 64;
            if (value instanceof Collection) return ((Collection<?>) value).size() * 50L + 48;
            return 256; // 默认估算
        }
    }
}
