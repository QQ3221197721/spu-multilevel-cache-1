package com.ecommerce.cache.optimization.v5;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 动态配置中心增强版 V5
 * 
 * 核心功能：
 * 1. 运行时动态配置 - 无需重启修改配置
 * 2. 配置版本管理 - 支持回滚
 * 3. 灰度发布 - 按比例/用户组生效
 * 4. A/B 测试 - 配置对比实验
 * 5. 配置监听 - 变更即时通知
 * 6. 配置审计 - 变更历史记录
 * 7. 配置校验 - 防止非法值
 */
@Service
public class DynamicConfigCenterV5 {
    
    private static final Logger log = LoggerFactory.getLogger(DynamicConfigCenterV5.class);
    
    // ========== 配置存储 ==========
    private final ConcurrentHashMap<String, ConfigValue> configMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ConfigChangeListener>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConfigHistory> historyMap = new ConcurrentHashMap<>();
    
    // ========== 灰度配置 ==========
    private final ConcurrentHashMap<String, GrayConfig> grayConfigs = new ConcurrentHashMap<>();
    
    // ========== A/B 测试 ==========
    private final ConcurrentHashMap<String, ABTestConfig> abTests = new ConcurrentHashMap<>();
    
    // ========== 配置校验器 ==========
    private final ConcurrentHashMap<String, ConfigValidator> validators = new ConcurrentHashMap<>();
    
    // ========== 版本管理 ==========
    private final AtomicLong versionCounter = new AtomicLong(0);
    
    // ========== 指标 ==========
    private final MeterRegistry meterRegistry;
    private Counter configChangeCounter;
    private Counter configRollbackCounter;
    private Counter grayHitCounter;
    
    @Value("${optimization.config-center.max-history:100}")
    private int maxHistory;
    
    @Value("${optimization.config-center.enabled:true}")
    private boolean enabled;
    
    public DynamicConfigCenterV5(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    @PostConstruct
    public void init() {
        // 注册指标
        configChangeCounter = Counter.builder("config.changes")
            .description("Configuration changes count")
            .register(meterRegistry);
        configRollbackCounter = Counter.builder("config.rollbacks")
            .description("Configuration rollback count")
            .register(meterRegistry);
        grayHitCounter = Counter.builder("config.gray.hits")
            .description("Gray config hits")
            .register(meterRegistry);
        
        Gauge.builder("config.total.count", configMap, Map::size)
            .register(meterRegistry);
        Gauge.builder("config.gray.count", grayConfigs, Map::size)
            .register(meterRegistry);
        Gauge.builder("config.abtest.count", abTests, Map::size)
            .register(meterRegistry);
        
        // 初始化默认配置
        initDefaultConfigs();
        
        log.info("DynamicConfigCenterV5 initialized: maxHistory={}", maxHistory);
    }
    
    /**
     * 获取配置值
     */
    public <T> T get(String key, T defaultValue) {
        return get(key, defaultValue, null);
    }
    
    /**
     * 获取配置值（带上下文，支持灰度）
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue, ConfigContext context) {
        if (!enabled) {
            return defaultValue;
        }
        
        // 检查灰度配置
        if (context != null) {
            GrayConfig grayConfig = grayConfigs.get(key);
            if (grayConfig != null && grayConfig.shouldApply(context)) {
                grayHitCounter.increment();
                return (T) grayConfig.value;
            }
            
            // 检查 A/B 测试
            ABTestConfig abTest = abTests.get(key);
            if (abTest != null && abTest.isActive()) {
                return (T) abTest.getValue(context.getUserId());
            }
        }
        
        // 获取普通配置
        ConfigValue configValue = configMap.get(key);
        if (configValue != null) {
            return (T) configValue.value;
        }
        
        return defaultValue;
    }
    
    /**
     * 设置配置值
     */
    public void set(String key, Object value) {
        set(key, value, "SYSTEM", "Direct set");
    }
    
    /**
     * 设置配置值（带审计信息）
     */
    public void set(String key, Object value, String operator, String reason) {
        // 校验配置
        ConfigValidator validator = validators.get(key);
        if (validator != null && !validator.validate(value)) {
            throw new IllegalArgumentException("Configuration validation failed for key: " + key);
        }
        
        // 保存历史
        ConfigValue oldValue = configMap.get(key);
        if (oldValue != null) {
            saveHistory(key, oldValue);
        }
        
        // 更新配置
        long version = versionCounter.incrementAndGet();
        ConfigValue newValue = new ConfigValue(value, version, operator, reason, LocalDateTime.now());
        configMap.put(key, newValue);
        
        configChangeCounter.increment();
        log.info("Config changed: key={}, version={}, operator={}, reason={}", 
            key, version, operator, reason);
        
        // 通知监听器
        notifyListeners(key, oldValue, newValue);
    }
    
    /**
     * 删除配置
     */
    public void remove(String key, String operator) {
        ConfigValue oldValue = configMap.remove(key);
        if (oldValue != null) {
            saveHistory(key, oldValue);
            log.info("Config removed: key={}, operator={}", key, operator);
            notifyListeners(key, oldValue, null);
        }
    }
    
    /**
     * 回滚配置
     */
    public boolean rollback(String key, long targetVersion) {
        ConfigHistory history = historyMap.get(key);
        if (history == null) {
            return false;
        }
        
        ConfigValue targetValue = history.getVersion(targetVersion);
        if (targetValue == null) {
            return false;
        }
        
        // 保存当前值到历史
        ConfigValue currentValue = configMap.get(key);
        if (currentValue != null) {
            saveHistory(key, currentValue);
        }
        
        // 回滚
        long newVersion = versionCounter.incrementAndGet();
        ConfigValue rolledBack = new ConfigValue(
            targetValue.value, newVersion, "SYSTEM", 
            "Rollback to version " + targetVersion, LocalDateTime.now()
        );
        configMap.put(key, rolledBack);
        
        configRollbackCounter.increment();
        log.info("Config rolled back: key={}, targetVersion={}, newVersion={}", 
            key, targetVersion, newVersion);
        
        notifyListeners(key, currentValue, rolledBack);
        return true;
    }
    
    /**
     * 设置灰度配置
     */
    public void setGrayConfig(String key, Object value, GrayStrategy strategy) {
        grayConfigs.put(key, new GrayConfig(value, strategy));
        log.info("Gray config set: key={}, strategy={}", key, strategy);
    }
    
    /**
     * 移除灰度配置
     */
    public void removeGrayConfig(String key) {
        grayConfigs.remove(key);
        log.info("Gray config removed: key={}", key);
    }
    
    /**
     * 创建 A/B 测试
     */
    public void createABTest(String key, Object valueA, Object valueB, double ratioA) {
        abTests.put(key, new ABTestConfig(valueA, valueB, ratioA));
        log.info("A/B test created: key={}, ratioA={}", key, ratioA);
    }
    
    /**
     * 结束 A/B 测试
     */
    public ABTestResult endABTest(String key, String winner) {
        ABTestConfig test = abTests.remove(key);
        if (test == null) {
            return null;
        }
        
        // 应用获胜配置
        Object winnerValue = "A".equals(winner) ? test.valueA : test.valueB;
        set(key, winnerValue, "SYSTEM", "A/B test winner: " + winner);
        
        ABTestResult result = test.getResult();
        log.info("A/B test ended: key={}, winner={}, result={}", key, winner, result);
        return result;
    }
    
    /**
     * 注册配置监听器
     */
    public void addListener(String key, ConfigChangeListener listener) {
        listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
    }
    
    /**
     * 移除配置监听器
     */
    public void removeListener(String key, ConfigChangeListener listener) {
        List<ConfigChangeListener> keyListeners = listeners.get(key);
        if (keyListeners != null) {
            keyListeners.remove(listener);
        }
    }
    
    /**
     * 注册配置校验器
     */
    public void registerValidator(String key, ConfigValidator validator) {
        validators.put(key, validator);
    }
    
    /**
     * 获取配置历史
     */
    public List<ConfigValue> getHistory(String key) {
        ConfigHistory history = historyMap.get(key);
        return history != null ? history.getAll() : Collections.emptyList();
    }
    
    /**
     * 获取所有配置
     */
    public Map<String, Object> getAllConfigs() {
        Map<String, Object> result = new HashMap<>();
        configMap.forEach((k, v) -> result.put(k, v.value));
        return result;
    }
    
    /**
     * 获取配置统计
     */
    public ConfigStats getStats() {
        return new ConfigStats(
            configMap.size(),
            grayConfigs.size(),
            abTests.size(),
            versionCounter.get(),
            historyMap.values().stream().mapToInt(h -> h.getAll().size()).sum()
        );
    }
    
    /**
     * 定期清理过期历史
     */
    @Scheduled(fixedRate = 3600000) // 1小时
    public void cleanupHistory() {
        historyMap.values().forEach(history -> history.trim(maxHistory));
    }
    
    // ========== 私有方法 ==========
    
    private void initDefaultConfigs() {
        // 初始化 V5 优化相关默认配置
        setDefault("cache.v5.enabled", true);
        setDefault("cache.v5.hot-key-threshold", 1000L);
        setDefault("cache.v5.shard.min-count", 4);
        setDefault("cache.v5.shard.max-count", 64);
        setDefault("cache.v5.traffic.leaky-rate", 10000);
        setDefault("cache.v5.traffic.token-capacity", 50000);
        setDefault("cache.v5.ttl.default", 600L);
        setDefault("cache.v5.ttl.min", 60L);
        setDefault("cache.v5.ttl.max", 7200L);
    }
    
    private void setDefault(String key, Object value) {
        if (!configMap.containsKey(key)) {
            configMap.put(key, new ConfigValue(value, 0, "SYSTEM", "Default", LocalDateTime.now()));
        }
    }
    
    private void saveHistory(String key, ConfigValue value) {
        historyMap.computeIfAbsent(key, k -> new ConfigHistory()).add(value);
    }
    
    private void notifyListeners(String key, ConfigValue oldValue, ConfigValue newValue) {
        List<ConfigChangeListener> keyListeners = listeners.get(key);
        if (keyListeners != null) {
            Object oldVal = oldValue != null ? oldValue.value : null;
            Object newVal = newValue != null ? newValue.value : null;
            ConfigChangeEvent event = new ConfigChangeEvent(key, oldVal, newVal);
            keyListeners.forEach(listener -> {
                try {
                    listener.onConfigChange(event);
                } catch (Exception e) {
                    log.error("Config listener error for key: {}", key, e);
                }
            });
        }
    }
    
    // ========== 内部类 ==========
    
    /**
     * 配置值
     */
    public record ConfigValue(
        Object value,
        long version,
        String operator,
        String reason,
        LocalDateTime timestamp
    ) {}
    
    /**
     * 配置历史
     */
    private static class ConfigHistory {
        private final List<ConfigValue> history = new CopyOnWriteArrayList<>();
        
        void add(ConfigValue value) {
            history.add(0, value);
        }
        
        ConfigValue getVersion(long version) {
            return history.stream()
                .filter(v -> v.version == version)
                .findFirst()
                .orElse(null);
        }
        
        List<ConfigValue> getAll() {
            return new ArrayList<>(history);
        }
        
        void trim(int maxSize) {
            while (history.size() > maxSize) {
                history.remove(history.size() - 1);
            }
        }
    }
    
    /**
     * 灰度配置
     */
    private record GrayConfig(Object value, GrayStrategy strategy) {
        boolean shouldApply(ConfigContext context) {
            return strategy.shouldApply(context);
        }
    }
    
    /**
     * 灰度策略
     */
    public interface GrayStrategy {
        boolean shouldApply(ConfigContext context);
        
        static GrayStrategy byRatio(double ratio) {
            return ctx -> Math.random() < ratio;
        }
        
        static GrayStrategy byUserIds(Set<String> userIds) {
            return ctx -> ctx != null && userIds.contains(ctx.getUserId());
        }
        
        static GrayStrategy byIpRange(String ipPrefix) {
            return ctx -> ctx != null && ctx.getIp() != null && ctx.getIp().startsWith(ipPrefix);
        }
    }
    
    /**
     * A/B 测试配置
     */
    private static class ABTestConfig {
        final Object valueA;
        final Object valueB;
        final double ratioA;
        final long createTime = System.currentTimeMillis();
        final AtomicLong hitsA = new AtomicLong(0);
        final AtomicLong hitsB = new AtomicLong(0);
        volatile boolean active = true;
        
        ABTestConfig(Object valueA, Object valueB, double ratioA) {
            this.valueA = valueA;
            this.valueB = valueB;
            this.ratioA = ratioA;
        }
        
        boolean isActive() { return active; }
        
        Object getValue(String userId) {
            // 基于用户 ID 哈希确保一致性
            boolean useA = userId != null ? 
                (Math.abs(userId.hashCode()) % 100) < (ratioA * 100) :
                Math.random() < ratioA;
            
            if (useA) {
                hitsA.incrementAndGet();
                return valueA;
            } else {
                hitsB.incrementAndGet();
                return valueB;
            }
        }
        
        ABTestResult getResult() {
            return new ABTestResult(hitsA.get(), hitsB.get(), 
                System.currentTimeMillis() - createTime);
        }
    }
    
    /**
     * 配置上下文
     */
    public record ConfigContext(String userId, String ip, Map<String, String> tags) {
        public String getUserId() { return userId; }
        public String getIp() { return ip; }
    }
    
    /**
     * 配置变更事件
     */
    public record ConfigChangeEvent(String key, Object oldValue, Object newValue) {}
    
    /**
     * 配置变更监听器
     */
    @FunctionalInterface
    public interface ConfigChangeListener {
        void onConfigChange(ConfigChangeEvent event);
    }
    
    /**
     * 配置校验器
     */
    @FunctionalInterface
    public interface ConfigValidator {
        boolean validate(Object value);
    }
    
    /**
     * A/B 测试结果
     */
    public record ABTestResult(long hitsA, long hitsB, long durationMs) {}
    
    /**
     * 配置统计
     */
    public record ConfigStats(
        int totalConfigs,
        int grayConfigs,
        int abTests,
        long currentVersion,
        int totalHistoryEntries
    ) {}
}
