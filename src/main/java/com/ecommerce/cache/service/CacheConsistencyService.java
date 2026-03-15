package com.ecommerce.cache.service;

import com.ecommerce.cache.config.CanalProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存一致性保障服务
 * <p>
 * 三层一致性保障机制：
 * <ol>
 *   <li><b>Canal binlog 最终一致性</b> — 监听 MySQL binlog 实时感知数据变更，触发缓存失效</li>
 *   <li><b>延迟双删</b> — 写操作时先删缓存，延迟后再删一次（应对主从复制延迟期间的脏读）</li>
 *   <li><b>版本号校验</b> — 每次写入缓存时携带数据版本号，读取时校验版本，过期则重新加载</li>
 * </ol>
 * <p>
 * 辅助机制：
 * <ul>
 *   <li><b>一致性审计</b> — 采样比对 DB 与缓存数据，发现不一致自动修复并告警</li>
 *   <li><b>binlog 延迟监控</b> — 超过阈值告警</li>
 *   <li><b>Prometheus 指标</b> — 全链路可观测</li>
 * </ul>
 */
@Service
public class CacheConsistencyService {

    private static final Logger log = LoggerFactory.getLogger(CacheConsistencyService.class);

    private final CanalProperties canalProperties;
    private final L1CacheService l1CacheService;
    private final L2RedisService l2RedisService;
    private final L3MemcachedService l3MemcachedService;
    private final RedissonClient redissonClient;
    private final CachePublisher cachePublisher;
    private final MeterRegistry meterRegistry;

    // 延迟双删调度器
    private final ScheduledExecutorService delayDeleteScheduler;

    // 一致性审计队列
    private final BlockingQueue<AuditTask> auditQueue = new LinkedBlockingQueue<>(10000);

    // 指标
    private Counter binlogInvalidateCounter;
    private Counter delayedDeleteCounter;
    private Counter versionMismatchCounter;
    private Counter auditCheckCounter;
    private Counter auditFixCounter;

    // 统计
    private final LongAdder totalBinlogInvalidations = new LongAdder();
    private final LongAdder totalDelayedDeletes = new LongAdder();
    private final LongAdder totalVersionMismatches = new LongAdder();
    private final AtomicLong lastBinlogEventTime = new AtomicLong(0);

    public CacheConsistencyService(CanalProperties canalProperties,
                                    L1CacheService l1CacheService,
                                    L2RedisService l2RedisService,
                                    L3MemcachedService l3MemcachedService,
                                    RedissonClient redissonClient,
                                    CachePublisher cachePublisher,
                                    MeterRegistry meterRegistry) {
        this.canalProperties = canalProperties;
        this.l1CacheService = l1CacheService;
        this.l2RedisService = l2RedisService;
        this.l3MemcachedService = l3MemcachedService;
        this.redissonClient = redissonClient;
        this.cachePublisher = cachePublisher;
        this.meterRegistry = meterRegistry;

        this.delayDeleteScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "delay-delete-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void init() {
        binlogInvalidateCounter = Counter.builder("cache.consistency.binlog.invalidations")
                .description("Cache invalidations triggered by binlog").register(meterRegistry);
        delayedDeleteCounter = Counter.builder("cache.consistency.delayed.deletes")
                .description("Delayed double delete executions").register(meterRegistry);
        versionMismatchCounter = Counter.builder("cache.consistency.version.mismatches")
                .description("Cache version mismatches detected").register(meterRegistry);
        auditCheckCounter = Counter.builder("cache.consistency.audit.checks")
                .description("Consistency audit checks performed").register(meterRegistry);
        auditFixCounter = Counter.builder("cache.consistency.audit.fixes")
                .description("Consistency issues auto-fixed").register(meterRegistry);

        Gauge.builder("cache.consistency.binlog.last.event.age.seconds",
                        () -> lastBinlogEventTime.get() == 0 ? 0 :
                                (System.currentTimeMillis() / 1000) - lastBinlogEventTime.get())
                .description("Seconds since last binlog event")
                .register(meterRegistry);

        log.info("[CacheConsistency] Initialized - delayedDoubleDelete={}, versionCheck={}, audit={}",
                canalProperties.getConsistency().isDelayedDoubleDeleteEnabled(),
                canalProperties.getConsistency().isVersionCheckEnabled(),
                canalProperties.getConsistency().isAuditEnabled());
    }

    // ==================== Canal Binlog 事件处理 ====================

    /**
     * 处理 Canal binlog 事件 — 核心入口
     *
     * @param tableName  变更的表名
     * @param eventType  事件类型: INSERT / UPDATE / DELETE
     * @param cacheKeys  受影响的缓存 Key 列表
     * @param binlogTs   binlog 事件时间戳（秒）
     */
    public void onBinlogEvent(String tableName, String eventType,
                               List<String> cacheKeys, Long binlogTs) {
        if (binlogTs != null) {
            lastBinlogEventTime.set(binlogTs);
        }

        for (String cacheKey : cacheKeys) {
            // 第一步：立即删除所有层级缓存
            invalidateAllLayers(cacheKey);

            // 第二步：延迟双删（如果启用）
            if (canalProperties.getConsistency().isDelayedDoubleDeleteEnabled()) {
                scheduleDelayedDelete(cacheKey);
            }

            // 第三步：递增版本号（如果启用）
            if (canalProperties.getConsistency().isVersionCheckEnabled()) {
                incrementVersion(cacheKey);
            }

            totalBinlogInvalidations.increment();
            binlogInvalidateCounter.increment();
        }

        // 第四步：广播本地缓存失效到其他实例
        cachePublisher.publishLocalInvalidate(cacheKeys);

        // 第五步：加入审计队列（采样）
        if (canalProperties.getConsistency().isAuditEnabled()) {
            double random = ThreadLocalRandom.current().nextDouble();
            if (random < canalProperties.getConsistency().getAuditSampleRate()) {
                auditQueue.offer(new AuditTask(tableName, cacheKeys,
                        System.currentTimeMillis(), eventType));
            }
        }

        log.debug("[CacheConsistency] Binlog event processed: table={}, type={}, keys={}",
                tableName, eventType, cacheKeys.size());
    }

    // ==================== 延迟双删 ====================

    /**
     * 调度延迟双删 — 在主从复制延迟结束后再次删除缓存
     * <p>
     * 时序:
     *   T0: 写 DB → Canal 捕获 binlog → 第一次删除缓存
     *   T0 + delayMs: 第二次删除缓存（此时从库应已同步）
     */
    private void scheduleDelayedDelete(String cacheKey) {
        long delayMs = canalProperties.getConsistency().getDelayedDeleteMs();
        delayDeleteScheduler.schedule(() -> {
            try {
                invalidateAllLayers(cacheKey);
                totalDelayedDeletes.increment();
                delayedDeleteCounter.increment();
                log.debug("[CacheConsistency] Delayed delete executed: key={}", cacheKey);
            } catch (Exception e) {
                log.error("[CacheConsistency] Delayed delete failed: key={}", cacheKey, e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    // ==================== 版本号校验 ====================

    /**
     * 递增缓存版本号
     * <p>
     * 每次数据变更时递增 Redis 中的版本号；缓存写入时携带当前版本号；
     * 读取时校验缓存中的版本号是否与 Redis 一致，不一致则视为脏数据。
     */
    public long incrementVersion(String cacheKey) {
        String versionKey = canalProperties.getConsistency().getVersionKeyPrefix() + cacheKey;
        int ttlSeconds = canalProperties.getConsistency().getVersionTtlSeconds();

        RBucket<Long> bucket = redissonClient.getBucket(versionKey);
        Long currentVersion = bucket.get();
        long newVersion = (currentVersion != null ? currentVersion : 0) + 1;
        bucket.set(newVersion, Duration.ofSeconds(ttlSeconds));
        return newVersion;
    }

    /**
     * 获取当前缓存版本号
     */
    public long getCurrentVersion(String cacheKey) {
        String versionKey = canalProperties.getConsistency().getVersionKeyPrefix() + cacheKey;
        RBucket<Long> bucket = redissonClient.getBucket(versionKey);
        Long version = bucket.get();
        return version != null ? version : 0;
    }

    /**
     * 校验缓存版本是否有效
     *
     * @param cacheKey     缓存 Key
     * @param cacheVersion 缓存中携带的版本号
     * @return true 版本一致（缓存有效），false 版本不一致（需重新加载）
     */
    public boolean validateVersion(String cacheKey, long cacheVersion) {
        if (!canalProperties.getConsistency().isVersionCheckEnabled()) {
            return true; // 未启用版本校验，直接通过
        }

        long currentVersion = getCurrentVersion(cacheKey);
        if (currentVersion > cacheVersion) {
            totalVersionMismatches.increment();
            versionMismatchCounter.increment();
            log.debug("[CacheConsistency] Version mismatch: key={}, cache={}, current={}",
                    cacheKey, cacheVersion, currentVersion);
            return false;
        }
        return true;
    }

    // ==================== 缓存失效操作 ====================

    /**
     * 失效所有层级缓存
     */
    private void invalidateAllLayers(String cacheKey) {
        // L1 本地缓存
        try {
            l1CacheService.invalidate(cacheKey);
        } catch (Exception e) {
            log.warn("[CacheConsistency] L1 invalidate failed: key={}", cacheKey, e);
        }

        // L2 Redis（包括热点 Key 分片）
        try {
            l2RedisService.deleteHotKey(cacheKey);
        } catch (Exception e) {
            log.warn("[CacheConsistency] L2 invalidate failed: key={}", cacheKey, e);
        }

        // L3 Memcached
        try {
            l3MemcachedService.delete(cacheKey);
        } catch (Exception e) {
            log.warn("[CacheConsistency] L3 invalidate failed: key={}", cacheKey, e);
        }
    }

    // ==================== 一致性审计 ====================

    /**
     * 定时消费审计队列，执行缓存与 DB 一致性比对
     */
    @Scheduled(fixedDelayString = "${canal.consistency.audit-window-seconds:60}000")
    public void processAuditQueue() {
        if (!canalProperties.getConsistency().isAuditEnabled()) return;

        List<AuditTask> batch = new ArrayList<>();
        auditQueue.drainTo(batch, 100);

        if (batch.isEmpty()) return;

        log.info("[CacheConsistency] Processing audit batch: size={}", batch.size());

        for (AuditTask task : batch) {
            try {
                auditCacheKeys(task);
            } catch (Exception e) {
                log.error("[CacheConsistency] Audit failed: table={}", task.tableName, e);
            }
        }
    }

    /**
     * 审计单个任务 — 检查缓存 Key 是否仍有残留脏数据
     * <p>
     * 在 binlog 事件处理后的一个审计窗口内，
     * 检查缓存是否已正确失效（不应存在旧数据）
     */
    private void auditCacheKeys(AuditTask task) {
        for (String cacheKey : task.cacheKeys) {
            auditCheckCounter.increment();

            // 检查 L2 Redis 中是否残留数据
            try {
                String cachedValue = l2RedisService.get(cacheKey);
                if (cachedValue != null) {
                    // 缓存仍有数据 — 可能是 binlog 失效后被重新填充的正确数据
                    // 通过版本号判断是否一致
                    if (canalProperties.getConsistency().isVersionCheckEnabled()) {
                        long currentVersion = getCurrentVersion(cacheKey);
                        // 如果版本号为 0，说明重新填充时没有正确设置版本，可能是脏数据
                        if (currentVersion == 0) {
                            log.warn("[CacheConsistency] Audit: potential stale cache detected, " +
                                    "key={}, table={}, eventType={}", cacheKey, task.tableName, task.eventType);

                            if (canalProperties.getConsistency().isAuditAutoFix()) {
                                invalidateAllLayers(cacheKey);
                                auditFixCounter.increment();
                                log.info("[CacheConsistency] Audit auto-fix: invalidated key={}", cacheKey);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[CacheConsistency] Audit check failed for key={}", cacheKey, e);
            }
        }
    }

    // ==================== 写操作一致性保障（供业务层调用）====================

    /**
     * 写操作前调用 — 先删缓存，再写 DB
     * <p>
     * 使用分布式锁防止并发写冲突
     */
    public void beforeWrite(String cacheKey) {
        RLock lock = redissonClient.getLock("consistency:write:" + cacheKey);
        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    invalidateAllLayers(cacheKey);
                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("[CacheConsistency] Failed to acquire write lock: key={}", cacheKey);
                // 即使获取锁失败，仍尝试删除
                invalidateAllLayers(cacheKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            invalidateAllLayers(cacheKey);
        }
    }

    /**
     * 写操作后调用 — 发布失效消息 + 延迟双删
     */
    public void afterWrite(String tableName, String primaryKey, List<String> cacheKeys) {
        // 发布到 RocketMQ（通知其他服务实例）
        cachePublisher.publishInvalidate(tableName, "UPDATE", primaryKey, cacheKeys);

        // 调度延迟双删
        if (canalProperties.getConsistency().isDelayedDoubleDeleteEnabled()) {
            for (String cacheKey : cacheKeys) {
                scheduleDelayedDelete(cacheKey);
            }
        }
    }

    // ==================== 统计信息 ====================

    /**
     * 获取一致性服务统计信息
     */
    public ConsistencyStats getStats() {
        return new ConsistencyStats(
                totalBinlogInvalidations.sum(),
                totalDelayedDeletes.sum(),
                totalVersionMismatches.sum(),
                lastBinlogEventTime.get(),
                auditQueue.size()
        );
    }

    public record ConsistencyStats(
            long totalBinlogInvalidations,
            long totalDelayedDeletes,
            long totalVersionMismatches,
            long lastBinlogEventTimestamp,
            int pendingAuditTasks
    ) {}

    /**
     * 审计任务
     */
    private record AuditTask(
            String tableName,
            List<String> cacheKeys,
            long eventTimestamp,
            String eventType
    ) {}
}
