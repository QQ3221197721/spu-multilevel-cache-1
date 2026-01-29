package com.ecommerce.cache.optimization.v11;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 零延迟预加载系统
 * 
 * 核心特性:
 * 1. 推测执行: 预测性数据预取
 * 2. 依赖图分析: 关联数据提前加载
 * 3. 优先级队列: 热数据优先
 * 4. 并行预取: 多路并发加载
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZeroLatencyPrefetcher {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV11Properties properties;
    private final StringRedisTemplate redisTemplate;
    
    /** 预取队列 */
    private final PriorityBlockingQueue<PrefetchTask> prefetchQueue = 
        new PriorityBlockingQueue<>(1000, Comparator.comparingDouble(t -> -t.priority));
    
    /** 依赖图 */
    private final ConcurrentMap<String, Set<String>> dependencyGraph = new ConcurrentHashMap<>();
    
    /** 访问序列 */
    private final ConcurrentMap<String, Queue<Long>> accessSequence = new ConcurrentHashMap<>();
    
    /** 预取缓存 */
    private final ConcurrentMap<String, PrefetchedData> prefetchCache = new ConcurrentHashMap<>();
    
    /** 推测执行状态 */
    private final ConcurrentMap<String, SpeculativeState> speculativeStates = new ConcurrentHashMap<>();
    
    /** 线程池 */
    private ExecutorService prefetchExecutor;
    private ScheduledExecutorService scheduler;
    
    /** 统计 */
    private final AtomicLong prefetchCount = new AtomicLong(0);
    private final AtomicLong prefetchHitCount = new AtomicLong(0);
    private final AtomicLong speculativeHitCount = new AtomicLong(0);
    
    private Counter prefetchCounter;
    private Counter hitCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getZeroLatency().isEnabled()) {
            log.info("[零延迟] 已禁用");
            return;
        }
        
        int workers = 4;
        prefetchExecutor = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "prefetch-worker");
            t.setDaemon(true);
            return t;
        });
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "prefetch-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 启动预取消费者
        for (int i = 0; i < workers; i++) {
            prefetchExecutor.submit(this::prefetchConsumer);
        }
        
        // 定期分析依赖
        scheduler.scheduleWithFixedDelay(this::analyzeDependencies, 30, 60, TimeUnit.SECONDS);
        
        // 定期清理
        scheduler.scheduleWithFixedDelay(this::cleanup, 60, 60, TimeUnit.SECONDS);
        
        log.info("[零延迟] 初始化完成 - 预取窗口: {}ms, 最大项: {}",
            properties.getZeroLatency().getPrefetchWindowMs(),
            properties.getZeroLatency().getMaxPrefetchItems());
    }
    
    @PreDestroy
    public void shutdown() {
        if (prefetchExecutor != null) prefetchExecutor.shutdownNow();
        if (scheduler != null) scheduler.shutdown();
        log.info("[零延迟] 已关闭 - 预取: {}, 命中: {}",
            prefetchCount.get(), prefetchHitCount.get());
    }
    
    private void initMetrics() {
        prefetchCounter = Counter.builder("cache.prefetch.count").register(meterRegistry);
        hitCounter = Counter.builder("cache.prefetch.hit").register(meterRegistry);
    }
    
    // ========== 核心API ==========
    
    /**
     * 记录访问(触发预测预取)
     */
    public void recordAccess(String key) {
        // 记录访问序列
        accessSequence.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>())
            .offer(System.currentTimeMillis());
        
        // 触发推测预取
        triggerSpeculativePrefetch(key);
    }
    
    /**
     * 获取数据(优先从预取缓存)
     */
    public Optional<String> get(String key) {
        // 先检查预取缓存
        PrefetchedData prefetched = prefetchCache.get(key);
        if (prefetched != null && !prefetched.isExpired()) {
            prefetchHitCount.incrementAndGet();
            hitCounter.increment();
            return Optional.of(prefetched.data);
        }
        
        // 从Redis获取
        String value = redisTemplate.opsForValue().get(key);
        
        // 记录访问
        recordAccess(key);
        
        return Optional.ofNullable(value);
    }
    
    /**
     * 主动预取
     */
    public void prefetch(String key, double priority) {
        PrefetchTask task = new PrefetchTask(key, priority, System.currentTimeMillis());
        prefetchQueue.offer(task);
    }
    
    /**
     * 批量预取
     */
    public void prefetchBatch(Collection<String> keys, double basePriority) {
        int i = 0;
        for (String key : keys) {
            prefetch(key, basePriority - i * 0.01);
            i++;
        }
    }
    
    /**
     * 注册依赖关系
     */
    public void registerDependency(String key, String dependentKey) {
        dependencyGraph.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
            .add(dependentKey);
    }
    
    // ========== 推测执行 ==========
    
    private void triggerSpeculativePrefetch(String accessedKey) {
        // 获取依赖
        Set<String> dependencies = dependencyGraph.get(accessedKey);
        if (dependencies != null && !dependencies.isEmpty()) {
            double basePriority = 0.8;
            for (String dep : dependencies) {
                prefetch(dep, basePriority);
                basePriority -= 0.1;
            }
        }
        
        // 基于历史序列预测
        predictNextAccess(accessedKey);
    }
    
    private void predictNextAccess(String currentKey) {
        // 简单马尔可夫预测
        Queue<Long> sequence = accessSequence.get(currentKey);
        if (sequence == null || sequence.size() < 3) return;
        
        // 计算访问频率
        long now = System.currentTimeMillis();
        int recentCount = 0;
        for (Long ts : sequence) {
            if (now - ts < 60000) recentCount++;
        }
        
        if (recentCount >= 3) {
            // 高频访问，预取相关数据
            double predictedPriority = Math.min(1.0, recentCount / 10.0);
            
            // 预取变体
            prefetch(currentKey + ":detail", predictedPriority);
            prefetch(currentKey + ":meta", predictedPriority * 0.8);
        }
    }
    
    // ========== 预取执行 ==========
    
    private void prefetchConsumer() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PrefetchTask task = prefetchQueue.poll(1, TimeUnit.SECONDS);
                if (task != null) {
                    executePrefetch(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[零延迟] 预取异常: {}", e.getMessage());
            }
        }
    }
    
    private void executePrefetch(PrefetchTask task) {
        // 检查是否已缓存
        if (prefetchCache.containsKey(task.key)) {
            return;
        }
        
        // 检查是否超时
        long age = System.currentTimeMillis() - task.createdAt;
        if (age > properties.getZeroLatency().getPrefetchWindowMs()) {
            return;
        }
        
        try {
            String value = redisTemplate.opsForValue().get(task.key);
            if (value != null) {
                long ttl = properties.getZeroLatency().getPrefetchWindowMs() * 2;
                prefetchCache.put(task.key, new PrefetchedData(
                    task.key, value, System.currentTimeMillis(), ttl
                ));
                
                prefetchCount.incrementAndGet();
                prefetchCounter.increment();
            }
        } catch (Exception e) {
            log.debug("[零延迟] 预取失败: {} - {}", task.key, e.getMessage());
        }
    }
    
    // ========== 依赖分析 ==========
    
    private void analyzeDependencies() {
        // 基于访问序列分析依赖
        List<String> recentKeys = new ArrayList<>();
        long now = System.currentTimeMillis();
        
        for (var entry : accessSequence.entrySet()) {
            for (Long ts : entry.getValue()) {
                if (now - ts < 60000) {
                    recentKeys.add(entry.getKey());
                    break;
                }
            }
        }
        
        // 简单共现分析
        for (int i = 0; i < recentKeys.size() - 1; i++) {
            String key1 = recentKeys.get(i);
            String key2 = recentKeys.get(i + 1);
            
            // 记录潜在依赖
            dependencyGraph.computeIfAbsent(key1, k -> ConcurrentHashMap.newKeySet()).add(key2);
        }
        
        log.debug("[零延迟] 依赖分析完成 - 依赖数: {}", dependencyGraph.size());
    }
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        
        // 清理过期预取
        prefetchCache.entrySet().removeIf(e -> e.getValue().isExpired());
        
        // 清理访问序列
        for (Queue<Long> seq : accessSequence.values()) {
            while (!seq.isEmpty() && now - seq.peek() > 300000) {
                seq.poll();
            }
        }
        
        // 限制依赖图大小
        if (dependencyGraph.size() > 10000) {
            Iterator<String> it = dependencyGraph.keySet().iterator();
            int toRemove = dependencyGraph.size() - 5000;
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("prefetchCount", prefetchCount.get());
        stats.put("prefetchHitCount", prefetchHitCount.get());
        stats.put("speculativeHitCount", speculativeHitCount.get());
        stats.put("queueSize", prefetchQueue.size());
        stats.put("cacheSize", prefetchCache.size());
        stats.put("dependencyCount", dependencyGraph.size());
        
        if (prefetchCount.get() > 0) {
            stats.put("hitRate", String.format("%.2f%%",
                (double) prefetchHitCount.get() / prefetchCount.get() * 100));
        }
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    @Data
    private static class PrefetchTask {
        private final String key;
        private final double priority;
        private final long createdAt;
    }
    
    @Data
    private static class PrefetchedData {
        private final String key;
        private final String data;
        private final long fetchedAt;
        private final long ttlMs;
        
        boolean isExpired() {
            return System.currentTimeMillis() - fetchedAt > ttlMs;
        }
    }
    
    @Data
    private static class SpeculativeState {
        private final String key;
        private final List<String> predictedNext;
        private final long createdAt;
    }
}
