package com.ecommerce.cache.optimization;

import com.alibaba.fastjson2.JSON;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 配置中心模块
 * 
 * 核心功能：
 * 1. 动态配置管理 - 支持运行时配置变更
 * 2. 配置版本控制 - 配置变更历史追踪
 * 3. 灰度发布开关 - 细粒度特性控制
 * 4. A/B 测试支持 - 流量分桶实验
 * 5. 配置热更新 - 无需重启即时生效
 * 6. 配置校验 - 防止错误配置上线
 */
@Service
public class DynamicConfigCenter {
    
    private static final Logger log = LoggerFactory.getLogger(DynamicConfigCenter.class);
    
    private static final String CONFIG_KEY_PREFIX = "config:dynamic:";
    private static final String CONFIG_VERSION_KEY = "config:version";
    
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    
    // 本地配置缓存
    private final ConcurrentHashMap<String, ConfigEntry> localCache = new ConcurrentHashMap<>();
    
    // 配置变更监听器
    private final ConcurrentHashMap<String, List<Consumer<ConfigEntry>>> listeners = new ConcurrentHashMap<>();
    
    // 当前配置版本
    private final AtomicLong currentVersion = new AtomicLong(0);
    
    // 指标
    private final Counter configUpdateCounter;
    private final Counter configReadCounter;
    
    public DynamicConfigCenter(StringRedisTemplate redisTemplate,
                               ApplicationEventPublisher eventPublisher,
                               MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        
        this.configUpdateCounter = Counter.builder("config.updates").register(meterRegistry);
        this.configReadCounter = Counter.builder("config.reads").register(meterRegistry);
        
        Gauge.builder("config.cache.size", localCache, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("config.version", currentVersion, AtomicLong::get)
            .register(meterRegistry);
    }
    
    @PostConstruct
    public void init() {
        // 初始化时加载所有配置
        refreshAllConfigs();
        log.info("Dynamic config center initialized, version: {}", currentVersion.get());
    }
    
    /**
     * 获取配置值
     */
    public String getConfig(String key) {
        return getConfig(key, null);
    }
    
    /**
     * 获取配置值（带默认值）
     */
    public String getConfig(String key, String defaultValue) {
        configReadCounter.increment();
        
        ConfigEntry entry = localCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.value();
        }
        
        // 本地缓存未命中，从 Redis 获取
        String value = redisTemplate.opsForValue().get(CONFIG_KEY_PREFIX + key);
        if (value != null) {
            localCache.put(key, new ConfigEntry(key, value, Instant.now(), -1));
            return value;
        }
        
        return defaultValue;
    }
    
    /**
     * 获取配置值（指定类型）
     */
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String key, Class<T> type, T defaultValue) {
        String value = getConfig(key);
        if (value == null) return defaultValue;
        
        try {
            if (type == String.class) return (T) value;
            if (type == Integer.class) return (T) Integer.valueOf(value);
            if (type == Long.class) return (T) Long.valueOf(value);
            if (type == Boolean.class) return (T) Boolean.valueOf(value);
            if (type == Double.class) return (T) Double.valueOf(value);
            return JSON.parseObject(value, type);
        } catch (Exception e) {
            log.error("Failed to parse config value: key={}, type={}", key, type, e);
            return defaultValue;
        }
    }
    
    /**
     * 设置配置值
     */
    public void setConfig(String key, String value) {
        setConfig(key, value, -1);
    }
    
    /**
     * 设置配置值（带过期时间）
     */
    public void setConfig(String key, String value, long ttlSeconds) {
        // 配置校验
        if (!validateConfig(key, value)) {
            throw new IllegalArgumentException("Invalid config value: " + key);
        }
        
        // 保存到 Redis
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue().set(CONFIG_KEY_PREFIX + key, value, Duration.ofSeconds(ttlSeconds));
        } else {
            redisTemplate.opsForValue().set(CONFIG_KEY_PREFIX + key, value);
        }
        
        // 更新本地缓存
        ConfigEntry oldEntry = localCache.get(key);
        ConfigEntry newEntry = new ConfigEntry(key, value, Instant.now(), ttlSeconds);
        localCache.put(key, newEntry);
        
        // 递增版本号
        long newVersion = redisTemplate.opsForValue().increment(CONFIG_VERSION_KEY);
        currentVersion.set(newVersion);
        
        configUpdateCounter.increment();
        
        // 通知监听器
        notifyListeners(key, newEntry);
        
        // 发布配置变更事件
        eventPublisher.publishEvent(new ConfigChangeEvent(key, 
            oldEntry != null ? oldEntry.value() : null, value, newVersion));
        
        log.info("Config updated: key={}, version={}", key, newVersion);
    }
    
    /**
     * 批量设置配置
     */
    public void setConfigs(Map<String, String> configs) {
        configs.forEach(this::setConfig);
    }
    
    /**
     * 删除配置
     */
    public void deleteConfig(String key) {
        redisTemplate.delete(CONFIG_KEY_PREFIX + key);
        localCache.remove(key);
        
        long newVersion = redisTemplate.opsForValue().increment(CONFIG_VERSION_KEY);
        currentVersion.set(newVersion);
        
        log.info("Config deleted: key={}, version={}", key, newVersion);
    }
    
    /**
     * 注册配置变更监听器
     */
    public void addListener(String key, Consumer<ConfigEntry> listener) {
        listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
    }
    
    /**
     * 移除配置变更监听器
     */
    public void removeListener(String key, Consumer<ConfigEntry> listener) {
        List<Consumer<ConfigEntry>> keyListeners = listeners.get(key);
        if (keyListeners != null) {
            keyListeners.remove(listener);
        }
    }
    
    private void notifyListeners(String key, ConfigEntry entry) {
        List<Consumer<ConfigEntry>> keyListeners = listeners.get(key);
        if (keyListeners != null) {
            keyListeners.forEach(listener -> {
                try {
                    listener.accept(entry);
                } catch (Exception e) {
                    log.error("Config listener error: key={}", key, e);
                }
            });
        }
    }
    
    /**
     * 配置校验
     */
    private boolean validateConfig(String key, String value) {
        // 这里可以添加自定义校验规则
        if (value == null) return false;
        if (value.length() > 65536) return false; // 最大 64KB
        return true;
    }
    
    /**
     * 定时检查配置更新
     */
    @Scheduled(fixedRate = 5000)
    public void checkConfigUpdates() {
        String versionStr = redisTemplate.opsForValue().get(CONFIG_VERSION_KEY);
        if (versionStr == null) return;
        
        long remoteVersion = Long.parseLong(versionStr);
        if (remoteVersion > currentVersion.get()) {
            log.info("Config version changed: {} -> {}, refreshing...", currentVersion.get(), remoteVersion);
            refreshAllConfigs();
            currentVersion.set(remoteVersion);
        }
    }
    
    /**
     * 刷新所有配置
     */
    public void refreshAllConfigs() {
        Set<String> keys = redisTemplate.keys(CONFIG_KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return;
        
        keys.forEach(fullKey -> {
            String key = fullKey.substring(CONFIG_KEY_PREFIX.length());
            String value = redisTemplate.opsForValue().get(fullKey);
            if (value != null) {
                localCache.put(key, new ConfigEntry(key, value, Instant.now(), -1));
            }
        });
        
        log.info("Refreshed {} configs", keys.size());
    }
    
    /**
     * 获取所有配置
     */
    public Map<String, ConfigEntry> getAllConfigs() {
        return new HashMap<>(localCache);
    }
    
    public record ConfigEntry(String key, String value, Instant updateTime, long ttlSeconds) {
        public boolean isExpired() {
            if (ttlSeconds <= 0) return false;
            return Instant.now().isAfter(updateTime.plusSeconds(ttlSeconds));
        }
    }
    
    public record ConfigChangeEvent(String key, String oldValue, String newValue, long version) {}
}

/**
 * 特性开关服务 - 支持灰度发布
 */
@Service
class FeatureToggleService {
    
    private static final Logger log = LoggerFactory.getLogger(FeatureToggleService.class);
    
    private final DynamicConfigCenter configCenter;
    private final MeterRegistry meterRegistry;
    
    // 特性开关缓存
    private final ConcurrentHashMap<String, FeatureToggle> toggleCache = new ConcurrentHashMap<>();
    
    // 指标
    private final ConcurrentHashMap<String, Counter> toggleCounters = new ConcurrentHashMap<>();
    
    public FeatureToggleService(DynamicConfigCenter configCenter, MeterRegistry meterRegistry) {
        this.configCenter = configCenter;
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * 检查特性是否启用
     */
    public boolean isEnabled(String featureName) {
        return isEnabled(featureName, null);
    }
    
    /**
     * 检查特性是否启用（带用户上下文）
     */
    public boolean isEnabled(String featureName, String userId) {
        FeatureToggle toggle = getToggle(featureName);
        if (toggle == null) return false;
        
        boolean enabled = toggle.isEnabled(userId);
        
        // 记录指标
        Counter counter = toggleCounters.computeIfAbsent(featureName + ":" + enabled, 
            k -> Counter.builder("feature.toggle")
                .tag("feature", featureName)
                .tag("enabled", String.valueOf(enabled))
                .register(meterRegistry));
        counter.increment();
        
        return enabled;
    }
    
    /**
     * 获取特性开关配置
     */
    public FeatureToggle getToggle(String featureName) {
        return toggleCache.computeIfAbsent(featureName, k -> {
            String config = configCenter.getConfig("feature:" + featureName);
            if (config == null) return null;
            
            try {
                return JSON.parseObject(config, FeatureToggle.class);
            } catch (Exception e) {
                log.error("Failed to parse feature toggle: {}", featureName, e);
                return null;
            }
        });
    }
    
    /**
     * 设置特性开关
     */
    public void setToggle(FeatureToggle toggle) {
        String config = JSON.toJSONString(toggle);
        configCenter.setConfig("feature:" + toggle.name(), config);
        toggleCache.put(toggle.name(), toggle);
        log.info("Feature toggle updated: {}", toggle.name());
    }
    
    /**
     * 清除特性开关缓存
     */
    public void clearCache() {
        toggleCache.clear();
    }
    
    /**
     * 获取所有特性开关
     */
    public List<FeatureToggle> getAllToggles() {
        return new ArrayList<>(toggleCache.values());
    }
    
    /**
     * 特性开关配置
     */
    public record FeatureToggle(
        String name,
        boolean globalEnabled,
        int percentage,
        Set<String> whitelist,
        Set<String> blacklist,
        Map<String, String> metadata
    ) {
        public boolean isEnabled(String userId) {
            // 检查黑名单
            if (blacklist != null && userId != null && blacklist.contains(userId)) {
                return false;
            }
            
            // 检查白名单
            if (whitelist != null && userId != null && whitelist.contains(userId)) {
                return true;
            }
            
            // 检查全局开关
            if (!globalEnabled) {
                return false;
            }
            
            // 百分比灰度
            if (percentage < 100) {
                if (userId == null) return false;
                int hash = Math.abs(userId.hashCode() % 100);
                return hash < percentage;
            }
            
            return true;
        }
    }
}

/**
 * A/B 测试服务
 */
@Service
class ABTestService {
    
    private static final Logger log = LoggerFactory.getLogger(ABTestService.class);
    
    private final DynamicConfigCenter configCenter;
    private final MeterRegistry meterRegistry;
    
    // 实验缓存
    private final ConcurrentHashMap<String, Experiment> experimentCache = new ConcurrentHashMap<>();
    
    // 用户分桶缓存
    private final ConcurrentHashMap<String, String> userBucketCache = new ConcurrentHashMap<>();
    
    public ABTestService(DynamicConfigCenter configCenter, MeterRegistry meterRegistry) {
        this.configCenter = configCenter;
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * 获取用户所属实验分桶
     */
    public String getBucket(String experimentName, String userId) {
        if (userId == null) return null;
        
        String cacheKey = experimentName + ":" + userId;
        return userBucketCache.computeIfAbsent(cacheKey, k -> {
            Experiment experiment = getExperiment(experimentName);
            if (experiment == null || !experiment.enabled()) {
                return null;
            }
            
            return assignBucket(experiment, userId);
        });
    }
    
    /**
     * 分配实验桶
     */
    private String assignBucket(Experiment experiment, String userId) {
        int hash = Math.abs(userId.hashCode() % 100);
        int accumulated = 0;
        
        for (Map.Entry<String, Integer> entry : experiment.buckets().entrySet()) {
            accumulated += entry.getValue();
            if (hash < accumulated) {
                // 记录分桶指标
                Counter.builder("ab.test.bucket")
                    .tag("experiment", experiment.name())
                    .tag("bucket", entry.getKey())
                    .register(meterRegistry)
                    .increment();
                
                return entry.getKey();
            }
        }
        
        return "control"; // 默认对照组
    }
    
    /**
     * 获取实验配置
     */
    public Experiment getExperiment(String experimentName) {
        return experimentCache.computeIfAbsent(experimentName, k -> {
            String config = configCenter.getConfig("experiment:" + experimentName);
            if (config == null) return null;
            
            try {
                return JSON.parseObject(config, Experiment.class);
            } catch (Exception e) {
                log.error("Failed to parse experiment: {}", experimentName, e);
                return null;
            }
        });
    }
    
    /**
     * 创建/更新实验
     */
    public void setExperiment(Experiment experiment) {
        // 验证桶分配总和为 100
        int total = experiment.buckets().values().stream().mapToInt(Integer::intValue).sum();
        if (total != 100) {
            throw new IllegalArgumentException("Bucket percentages must sum to 100");
        }
        
        String config = JSON.toJSONString(experiment);
        configCenter.setConfig("experiment:" + experiment.name(), config);
        experimentCache.put(experiment.name(), experiment);
        
        log.info("Experiment updated: {}", experiment.name());
    }
    
    /**
     * 记录实验转化
     */
    public void recordConversion(String experimentName, String userId, String metric) {
        String bucket = getBucket(experimentName, userId);
        if (bucket != null) {
            Counter.builder("ab.test.conversion")
                .tag("experiment", experimentName)
                .tag("bucket", bucket)
                .tag("metric", metric)
                .register(meterRegistry)
                .increment();
        }
    }
    
    /**
     * 清除用户分桶缓存（用于重新分桶）
     */
    public void clearUserBucketCache() {
        userBucketCache.clear();
    }
    
    /**
     * 实验配置
     */
    public record Experiment(
        String name,
        boolean enabled,
        Map<String, Integer> buckets, // bucket_name -> percentage
        Instant startTime,
        Instant endTime,
        Map<String, String> metadata
    ) {
        public boolean isActive() {
            if (!enabled) return false;
            Instant now = Instant.now();
            if (startTime != null && now.isBefore(startTime)) return false;
            if (endTime != null && now.isAfter(endTime)) return false;
            return true;
        }
    }
}

/**
 * 配置快照服务 - 配置版本管理
 */
@Component
class ConfigSnapshotService {
    
    private static final Logger log = LoggerFactory.getLogger(ConfigSnapshotService.class);
    
    private static final String SNAPSHOT_KEY_PREFIX = "config:snapshot:";
    private static final int MAX_SNAPSHOTS = 10;
    
    private final StringRedisTemplate redisTemplate;
    private final DynamicConfigCenter configCenter;
    
    public ConfigSnapshotService(StringRedisTemplate redisTemplate, DynamicConfigCenter configCenter) {
        this.redisTemplate = redisTemplate;
        this.configCenter = configCenter;
    }
    
    /**
     * 创建配置快照
     */
    public String createSnapshot(String description) {
        String snapshotId = String.valueOf(System.currentTimeMillis());
        
        ConfigSnapshot snapshot = new ConfigSnapshot(
            snapshotId,
            description,
            Instant.now(),
            configCenter.getAllConfigs()
        );
        
        String json = JSON.toJSONString(snapshot);
        redisTemplate.opsForValue().set(SNAPSHOT_KEY_PREFIX + snapshotId, json);
        
        // 维护快照列表
        redisTemplate.opsForList().leftPush(SNAPSHOT_KEY_PREFIX + "list", snapshotId);
        redisTemplate.opsForList().trim(SNAPSHOT_KEY_PREFIX + "list", 0, MAX_SNAPSHOTS - 1);
        
        log.info("Config snapshot created: {}", snapshotId);
        return snapshotId;
    }
    
    /**
     * 恢复配置快照
     */
    public void restoreSnapshot(String snapshotId) {
        String json = redisTemplate.opsForValue().get(SNAPSHOT_KEY_PREFIX + snapshotId);
        if (json == null) {
            throw new IllegalArgumentException("Snapshot not found: " + snapshotId);
        }
        
        ConfigSnapshot snapshot = JSON.parseObject(json, ConfigSnapshot.class);
        
        // 先创建当前状态的快照（用于回滚）
        createSnapshot("Auto backup before restore to " + snapshotId);
        
        // 恢复配置
        snapshot.configs().forEach((key, entry) -> 
            configCenter.setConfig(key, entry.value()));
        
        log.info("Config snapshot restored: {}", snapshotId);
    }
    
    /**
     * 获取快照列表
     */
    public List<ConfigSnapshot> getSnapshots() {
        List<String> snapshotIds = redisTemplate.opsForList().range(SNAPSHOT_KEY_PREFIX + "list", 0, -1);
        if (snapshotIds == null) return Collections.emptyList();
        
        return snapshotIds.stream()
            .map(id -> {
                String json = redisTemplate.opsForValue().get(SNAPSHOT_KEY_PREFIX + id);
                return json != null ? JSON.parseObject(json, ConfigSnapshot.class) : null;
            })
            .filter(Objects::nonNull)
            .toList();
    }
    
    /**
     * 删除快照
     */
    public void deleteSnapshot(String snapshotId) {
        redisTemplate.delete(SNAPSHOT_KEY_PREFIX + snapshotId);
        redisTemplate.opsForList().remove(SNAPSHOT_KEY_PREFIX + "list", 1, snapshotId);
        log.info("Config snapshot deleted: {}", snapshotId);
    }
    
    public record ConfigSnapshot(
        String id,
        String description,
        Instant createTime,
        Map<String, DynamicConfigCenter.ConfigEntry> configs
    ) {}
}

/**
 * 配置变更事件监听器
 */
@Component
class ConfigChangeListener {
    
    private static final Logger log = LoggerFactory.getLogger(ConfigChangeListener.class);
    
    private final MeterRegistry meterRegistry;
    
    public ConfigChangeListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    @EventListener
    public void onConfigChange(DynamicConfigCenter.ConfigChangeEvent event) {
        log.info("Config changed: key={}, version={}", event.key(), event.version());
        
        // 记录变更指标
        Counter.builder("config.change.events")
            .tag("key", event.key())
            .register(meterRegistry)
            .increment();
        
        // 这里可以添加配置变更后的处理逻辑
        // 例如：刷新特定缓存、通知其他服务等
    }
}
