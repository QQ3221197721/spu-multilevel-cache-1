package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 分布式锁增强服务
 * 
 * 核心特性:
 * 1. 多级锁降级: Redis -> 本地锁的优雅降级
 * 2. 读写分离: 读写锁优化读多写少场景
 * 3. 可重入支持: 同线程多次获取锁
 * 4. 看门狗续期: 自动续期防止锁提前失效
 * 5. 公平锁选项: 按请求顺序获取锁
 * 6. 锁预热: 启动时预创建热点锁
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLockEnhancer {
    
    private final OptimizationV7Properties properties;
    private final MeterRegistry meterRegistry;
    private final RedissonClient redissonClient;
    
    // ========== 锁管理 ==========
    
    /** 本地锁降级缓存 */
    private final ConcurrentMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    
    /** 本地读写锁 */
    private final ConcurrentMap<String, ReentrantReadWriteLock> localRWLocks = new ConcurrentHashMap<>();
    
    /** 锁持有记录(用于可重入) */
    private final ConcurrentMap<String, LockHolder> lockHolders = new ConcurrentHashMap<>();
    
    /** 锁统计 */
    private final ConcurrentMap<String, LockStats> lockStats = new ConcurrentHashMap<>();
    
    /** 看门狗任务 */
    private final ConcurrentMap<String, ScheduledFuture<?>> watchdogTasks = new ConcurrentHashMap<>();
    
    /** 预热锁集合 */
    private final Set<String> warmedUpLocks = ConcurrentHashMap.newKeySet();
    
    /** 降级状态 */
    private volatile boolean degraded = false;
    private volatile long degradedSince = 0;
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    
    /** 锁计数 */
    private final AtomicLong totalLockAcquired = new AtomicLong(0);
    private final AtomicLong totalLockFailed = new AtomicLong(0);
    private final AtomicLong totalLockDegraded = new AtomicLong(0);
    
    // ========== 指标 ==========
    
    private Counter lockAcquireCounter;
    private Counter lockFailCounter;
    private Counter lockDegradeCounter;
    private Timer lockAcquireTimer;
    
    @PostConstruct
    public void init() {
        if (!properties.isDistributedLockEnhanced()) {
            log.info("[分布式锁增强] 未启用");
            return;
        }
        
        // 初始化调度器
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "lock-enhancer");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化指标
        initMetrics();
        
        // 锁预热
        if (properties.getDistributedLock().isWarmupEnabled()) {
            warmupLocks();
        }
        
        // 启动健康检查
        scheduler.scheduleAtFixedRate(
            this::healthCheck,
            10,
            10,
            TimeUnit.SECONDS
        );
        
        log.info("[分布式锁增强] 初始化完成 - 超时: {}ms, 公平锁: {}, 读写分离: {}",
            properties.getDistributedLock().getAcquireTimeoutMs(),
            properties.getDistributedLock().isFairLock(),
            properties.getDistributedLock().isReadWriteSeparation());
    }
    
    @PreDestroy
    public void shutdown() {
        // 取消所有看门狗任务
        watchdogTasks.values().forEach(f -> f.cancel(true));
        watchdogTasks.clear();
        
        if (scheduler != null) {
            scheduler.shutdown();
        }
        
        log.info("[分布式锁增强] 已关闭");
    }
    
    private void initMetrics() {
        lockAcquireCounter = Counter.builder("cache.lock.acquire")
            .description("锁获取次数")
            .register(meterRegistry);
        
        lockFailCounter = Counter.builder("cache.lock.fail")
            .description("锁获取失败次数")
            .register(meterRegistry);
        
        lockDegradeCounter = Counter.builder("cache.lock.degrade")
            .description("锁降级次数")
            .register(meterRegistry);
        
        lockAcquireTimer = Timer.builder("cache.lock.acquire.time")
            .description("锁获取耗时")
            .register(meterRegistry);
        
        Gauge.builder("cache.lock.active", lockHolders, Map::size)
            .description("活跃锁数量")
            .register(meterRegistry);
        
        Gauge.builder("cache.lock.degraded", () -> degraded ? 1 : 0)
            .description("锁降级状态")
            .register(meterRegistry);
    }
    
    private void warmupLocks() {
        log.info("[分布式锁增强] 开始锁预热");
        
        // 预创建一些常用锁
        List<String> hotLockKeys = List.of(
            "cache:update:lock",
            "cache:refresh:lock",
            "cache:invalidate:lock",
            "bloom:filter:lock",
            "hot:key:migrate:lock"
        );
        
        for (String key : hotLockKeys) {
            try {
                localLocks.put(key, new ReentrantLock(properties.getDistributedLock().isFairLock()));
                localRWLocks.put(key, new ReentrantReadWriteLock(properties.getDistributedLock().isFairLock()));
                warmedUpLocks.add(key);
            } catch (Exception e) {
                log.warn("[分布式锁增强] 预热锁失败: {}", key, e);
            }
        }
        
        log.info("[分布式锁增强] 锁预热完成 - 预热数量: {}", warmedUpLocks.size());
    }
    
    // ========== 核心API ==========
    
    /**
     * 获取分布式锁
     */
    public boolean tryLock(String lockKey) {
        return tryLock(lockKey, properties.getDistributedLock().getAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
    }
    
    /**
     * 获取分布式锁(带超时)
     */
    public boolean tryLock(String lockKey, long timeout, TimeUnit unit) {
        if (!properties.isDistributedLockEnhanced()) {
            return true; // 未启用时默认获取成功
        }
        
        return lockAcquireTimer.record(() -> {
            var config = properties.getDistributedLock();
            
            // 检查可重入
            if (config.isReentrant()) {
                LockHolder holder = lockHolders.get(lockKey);
                if (holder != null && holder.isCurrentThread()) {
                    holder.incrementCount();
                    return true;
                }
            }
            
            boolean acquired = false;
            int retries = 0;
            
            while (!acquired && retries < config.getRetryCount()) {
                try {
                    // 尝试Redis分布式锁
                    if (!degraded) {
                        acquired = tryRedisLock(lockKey, timeout, unit);
                    }
                    
                    // 降级到本地锁
                    if (!acquired && config.isDegradationEnabled()) {
                        acquired = tryLocalLock(lockKey, timeout, unit);
                        if (acquired) {
                            totalLockDegraded.incrementAndGet();
                            lockDegradeCounter.increment();
                        }
                    }
                    
                    if (!acquired) {
                        retries++;
                        if (retries < config.getRetryCount()) {
                            Thread.sleep(config.getRetryIntervalMs());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("[分布式锁增强] 获取锁异常: {}", lockKey, e);
                    retries++;
                }
            }
            
            if (acquired) {
                totalLockAcquired.incrementAndGet();
                lockAcquireCounter.increment();
                
                // 记录锁持有
                lockHolders.put(lockKey, new LockHolder(Thread.currentThread().getId(), lockKey));
                
                // 启动看门狗
                if (config.isWatchdogEnabled() && !degraded) {
                    startWatchdog(lockKey);
                }
                
                // 更新统计
                lockStats.computeIfAbsent(lockKey, k -> new LockStats()).recordAcquire();
            } else {
                totalLockFailed.incrementAndGet();
                lockFailCounter.increment();
                lockStats.computeIfAbsent(lockKey, k -> new LockStats()).recordFail();
            }
            
            return acquired;
        });
    }
    
    /**
     * 释放锁
     */
    public void unlock(String lockKey) {
        if (!properties.isDistributedLockEnhanced()) {
            return;
        }
        
        // 检查可重入计数
        LockHolder holder = lockHolders.get(lockKey);
        if (holder != null && holder.isCurrentThread()) {
            if (holder.decrementCount() > 0) {
                return; // 还有重入未释放
            }
        }
        
        // 停止看门狗
        stopWatchdog(lockKey);
        
        // 移除持有记录
        lockHolders.remove(lockKey);
        
        try {
            // 释放Redis锁
            if (!degraded) {
                RLock lock = redissonClient.getLock(lockKey);
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (Exception e) {
            log.warn("[分布式锁增强] 释放Redis锁异常: {}", lockKey, e);
        }
        
        // 释放本地锁
        ReentrantLock localLock = localLocks.get(lockKey);
        if (localLock != null && localLock.isHeldByCurrentThread()) {
            localLock.unlock();
        }
        
        lockStats.computeIfAbsent(lockKey, k -> new LockStats()).recordRelease();
    }
    
    /**
     * 获取读锁
     */
    public boolean tryReadLock(String lockKey, long timeout, TimeUnit unit) {
        if (!properties.isDistributedLockEnhanced() || 
            !properties.getDistributedLock().isReadWriteSeparation()) {
            return tryLock(lockKey, timeout, unit);
        }
        
        return lockAcquireTimer.record(() -> {
            boolean acquired = false;
            
            try {
                if (!degraded) {
                    RReadWriteLock rwLock = redissonClient.getReadWriteLock(lockKey);
                    acquired = rwLock.readLock().tryLock(timeout, unit.toMillis(timeout), unit);
                }
                
                if (!acquired) {
                    ReentrantReadWriteLock localRWLock = localRWLocks.computeIfAbsent(
                        lockKey, 
                        k -> new ReentrantReadWriteLock(properties.getDistributedLock().isFairLock())
                    );
                    acquired = localRWLock.readLock().tryLock(timeout, unit);
                    if (acquired) {
                        totalLockDegraded.incrementAndGet();
                    }
                }
                
                if (acquired) {
                    totalLockAcquired.incrementAndGet();
                    lockAcquireCounter.increment();
                } else {
                    totalLockFailed.incrementAndGet();
                    lockFailCounter.increment();
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[分布式锁增强] 获取读锁异常: {}", lockKey, e);
            }
            
            return acquired;
        });
    }
    
    /**
     * 释放读锁
     */
    public void unlockRead(String lockKey) {
        if (!properties.isDistributedLockEnhanced()) {
            return;
        }
        
        try {
            if (!degraded) {
                RReadWriteLock rwLock = redissonClient.getReadWriteLock(lockKey);
                if (rwLock.readLock().isHeldByCurrentThread()) {
                    rwLock.readLock().unlock();
                }
            }
        } catch (Exception e) {
            log.warn("[分布式锁增强] 释放Redis读锁异常: {}", lockKey, e);
        }
        
        ReentrantReadWriteLock localRWLock = localRWLocks.get(lockKey);
        if (localRWLock != null) {
            try {
                localRWLock.readLock().unlock();
            } catch (IllegalMonitorStateException ignored) {}
        }
    }
    
    /**
     * 获取写锁
     */
    public boolean tryWriteLock(String lockKey, long timeout, TimeUnit unit) {
        if (!properties.isDistributedLockEnhanced() || 
            !properties.getDistributedLock().isReadWriteSeparation()) {
            return tryLock(lockKey, timeout, unit);
        }
        
        return lockAcquireTimer.record(() -> {
            boolean acquired = false;
            
            try {
                if (!degraded) {
                    RReadWriteLock rwLock = redissonClient.getReadWriteLock(lockKey);
                    acquired = rwLock.writeLock().tryLock(timeout, unit.toMillis(timeout), unit);
                }
                
                if (!acquired) {
                    ReentrantReadWriteLock localRWLock = localRWLocks.computeIfAbsent(
                        lockKey, 
                        k -> new ReentrantReadWriteLock(properties.getDistributedLock().isFairLock())
                    );
                    acquired = localRWLock.writeLock().tryLock(timeout, unit);
                    if (acquired) {
                        totalLockDegraded.incrementAndGet();
                    }
                }
                
                if (acquired) {
                    totalLockAcquired.incrementAndGet();
                    lockAcquireCounter.increment();
                    lockHolders.put(lockKey, new LockHolder(Thread.currentThread().getId(), lockKey));
                } else {
                    totalLockFailed.incrementAndGet();
                    lockFailCounter.increment();
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[分布式锁增强] 获取写锁异常: {}", lockKey, e);
            }
            
            return acquired;
        });
    }
    
    /**
     * 释放写锁
     */
    public void unlockWrite(String lockKey) {
        if (!properties.isDistributedLockEnhanced()) {
            return;
        }
        
        lockHolders.remove(lockKey);
        
        try {
            if (!degraded) {
                RReadWriteLock rwLock = redissonClient.getReadWriteLock(lockKey);
                if (rwLock.writeLock().isHeldByCurrentThread()) {
                    rwLock.writeLock().unlock();
                }
            }
        } catch (Exception e) {
            log.warn("[分布式锁增强] 释放Redis写锁异常: {}", lockKey, e);
        }
        
        ReentrantReadWriteLock localRWLock = localRWLocks.get(lockKey);
        if (localRWLock != null) {
            try {
                localRWLock.writeLock().unlock();
            } catch (IllegalMonitorStateException ignored) {}
        }
    }
    
    /**
     * 带锁执行操作
     */
    public <T> T executeWithLock(String lockKey, Supplier<T> operation) {
        return executeWithLock(lockKey, operation, null);
    }
    
    /**
     * 带锁执行操作(带降级)
     */
    public <T> T executeWithLock(String lockKey, Supplier<T> operation, Supplier<T> fallback) {
        if (tryLock(lockKey)) {
            try {
                return operation.get();
            } finally {
                unlock(lockKey);
            }
        } else {
            if (fallback != null) {
                return fallback.get();
            }
            throw new RuntimeException("获取锁失败: " + lockKey);
        }
    }
    
    /**
     * 判断锁是否被持有
     */
    public boolean isLocked(String lockKey) {
        return lockHolders.containsKey(lockKey);
    }
    
    // ========== 内部方法 ==========
    
    private boolean tryRedisLock(String lockKey, long timeout, TimeUnit unit) throws InterruptedException {
        RLock lock = redissonClient.getLock(lockKey);
        return lock.tryLock(timeout, properties.getDistributedLock().getLeaseTimeMs(), unit);
    }
    
    private boolean tryLocalLock(String lockKey, long timeout, TimeUnit unit) throws InterruptedException {
        ReentrantLock localLock = localLocks.computeIfAbsent(
            lockKey, 
            k -> new ReentrantLock(properties.getDistributedLock().isFairLock())
        );
        return localLock.tryLock(timeout, unit);
    }
    
    private void startWatchdog(String lockKey) {
        long renewInterval = properties.getDistributedLock().getLeaseTimeMs() / 3;
        
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                LockHolder holder = lockHolders.get(lockKey);
                if (holder != null) {
                    RLock lock = redissonClient.getLock(lockKey);
                    if (lock.isHeldByCurrentThread()) {
                        lock.lock(properties.getDistributedLock().getLeaseTimeMs(), TimeUnit.MILLISECONDS);
                        log.trace("[分布式锁增强] 看门狗续期: {}", lockKey);
                    }
                }
            } catch (Exception e) {
                log.warn("[分布式锁增强] 看门狗续期失败: {}", lockKey, e);
            }
        }, renewInterval, renewInterval, TimeUnit.MILLISECONDS);
        
        watchdogTasks.put(lockKey, task);
    }
    
    private void stopWatchdog(String lockKey) {
        ScheduledFuture<?> task = watchdogTasks.remove(lockKey);
        if (task != null) {
            task.cancel(false);
        }
    }
    
    private void healthCheck() {
        try {
            // 检测Redis是否可用
            redissonClient.getBucket("lock:health:check").set("ping", 5, TimeUnit.SECONDS);
            
            if (degraded) {
                degraded = false;
                log.info("[分布式锁增强] Redis恢复，退出降级模式");
            }
        } catch (Exception e) {
            if (!degraded) {
                degraded = true;
                degradedSince = System.currentTimeMillis();
                log.warn("[分布式锁增强] Redis不可用，进入降级模式");
            }
        }
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", properties.isDistributedLockEnhanced());
        stats.put("degraded", degraded);
        stats.put("degradedSince", degraded ? new Date(degradedSince) : null);
        stats.put("activeLocks", lockHolders.size());
        stats.put("localLockCount", localLocks.size());
        stats.put("watchdogTasks", watchdogTasks.size());
        stats.put("warmedUpLocks", warmedUpLocks.size());
        
        // 计数统计
        stats.put("totalAcquired", totalLockAcquired.get());
        stats.put("totalFailed", totalLockFailed.get());
        stats.put("totalDegraded", totalLockDegraded.get());
        
        long total = totalLockAcquired.get() + totalLockFailed.get();
        stats.put("successRate", total > 0 ? 
            String.format("%.2f%%", (double) totalLockAcquired.get() / total * 100) : "N/A");
        stats.put("degradeRate", totalLockAcquired.get() > 0 ?
            String.format("%.2f%%", (double) totalLockDegraded.get() / totalLockAcquired.get() * 100) : "N/A");
        
        // 热点锁统计
        List<Map<String, Object>> hotLocks = lockStats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().acquireCount, a.getValue().acquireCount))
            .limit(10)
            .map(e -> {
                Map<String, Object> lock = new LinkedHashMap<>();
                lock.put("key", e.getKey());
                lock.put("acquireCount", e.getValue().acquireCount);
                lock.put("failCount", e.getValue().failCount);
                lock.put("avgHoldTime", String.format("%.2fms", e.getValue().getAverageHoldTime()));
                return lock;
            })
            .toList();
        stats.put("hotLocks", hotLocks);
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    /**
     * 锁持有者
     */
    @Data
    private static class LockHolder {
        private final long threadId;
        private final String lockKey;
        private final long acquireTime = System.currentTimeMillis();
        private int count = 1;
        
        boolean isCurrentThread() {
            return Thread.currentThread().getId() == threadId;
        }
        
        void incrementCount() { count++; }
        int decrementCount() { return --count; }
    }
    
    /**
     * 锁统计
     */
    private static class LockStats {
        private long acquireCount = 0;
        private long failCount = 0;
        private long totalHoldTime = 0;
        private long releaseCount = 0;
        private long lastAcquireTime = 0;
        
        synchronized void recordAcquire() {
            acquireCount++;
            lastAcquireTime = System.currentTimeMillis();
        }
        
        synchronized void recordFail() {
            failCount++;
        }
        
        synchronized void recordRelease() {
            releaseCount++;
            if (lastAcquireTime > 0) {
                totalHoldTime += System.currentTimeMillis() - lastAcquireTime;
                lastAcquireTime = 0;
            }
        }
        
        double getAverageHoldTime() {
            return releaseCount > 0 ? (double) totalHoldTime / releaseCount : 0;
        }
    }
}
