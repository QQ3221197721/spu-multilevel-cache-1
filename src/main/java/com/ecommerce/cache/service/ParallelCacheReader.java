package com.ecommerce.cache.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 高性能并行缓存读取器
 * 特性：
 * 1. 并行读取 L2/L3 缓存，First-Win 策略
 * 2. 虚拟线程优化（JDK 21）
 * 3. 批量并行读取支持
 * 4. 自适应超时控制
 * 
 * 性能目标：批量读取延迟 < 5ms，并行读取延迟 < 3ms
 */
@Service
public class ParallelCacheReader {
    
    private static final Logger log = LoggerFactory.getLogger(ParallelCacheReader.class);
    
    // 虚拟线程执行器
    private final ExecutorService virtualExecutor;
    // 并行读取超时（毫秒）
    private static final long PARALLEL_READ_TIMEOUT_MS = 50;
    // 批量读取并发度
    private static final int BATCH_PARALLELISM = 32;
    
    private final L2RedisService l2RedisService;
    private final L3MemcachedService l3MemcachedService;
    private final MeterRegistry meterRegistry;
    
    // 性能指标
    private final Timer parallelReadTimer;
    private final Timer batchReadTimer;

    public ParallelCacheReader(L2RedisService l2RedisService,
                                L3MemcachedService l3MemcachedService,
                                MeterRegistry meterRegistry) {
        this.l2RedisService = l2RedisService;
        this.l3MemcachedService = l3MemcachedService;
        this.meterRegistry = meterRegistry;
        
        // JDK 21 虚拟线程执行器
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        // 注册指标
        this.parallelReadTimer = Timer.builder("cache.parallel.read")
            .description("Parallel cache read latency")
            .register(meterRegistry);
        this.batchReadTimer = Timer.builder("cache.batch.read")
            .description("Batch cache read latency")
            .register(meterRegistry);
    }

    /**
     * 并行读取 L2/L3 缓存（First-Win 策略）
     * 同时发起 Redis 和 Memcached 请求，谁先返回用谁
     * 
     * @param key 缓存键
     * @return 缓存值，null 表示未命中
     */
    public String parallelGet(String key) {
        return parallelReadTimer.record(() -> doParallelGet(key));
    }
    
    private String doParallelGet(String key) {
        CompletableFuture<String> l2Future = CompletableFuture.supplyAsync(
            () -> l2RedisService.get(key), virtualExecutor);
        CompletableFuture<String> l3Future = CompletableFuture.supplyAsync(
            () -> l3MemcachedService.get(key), virtualExecutor);
        
        try {
            // First-Win：任一完成即返回
            CompletableFuture<String> winner = CompletableFuture.anyOf(l2Future, l3Future)
                .thenApply(result -> (String) result)
                .orTimeout(PARALLEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            
            String result = winner.get();
            if (result != null) {
                log.debug("Parallel read hit: key={}", key);
                return result;
            }
            
            // 如果第一个返回 null，等待另一个
            String l2Result = l2Future.getNow(null);
            String l3Result = l3Future.getNow(null);
            
            return l2Result != null ? l2Result : l3Result;
            
        } catch (TimeoutException e) {
            log.debug("Parallel read timeout: key={}", key);
            return null;
        } catch (Exception e) {
            log.warn("Parallel read error: key={}", key, e);
            return null;
        }
    }

    /**
     * 批量并行读取
     * 将请求分组后并行执行，大幅提升批量读取性能
     * 
     * @param keys 缓存键列表
     * @return 键值映射（不含未命中的键）
     */
    public Map<String, String> batchParallelGet(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }
        
        return batchReadTimer.record(() -> doBatchParallelGet(keys));
    }
    
    private Map<String, String> doBatchParallelGet(Collection<String> keys) {
        List<String> keyList = new ArrayList<>(keys);
        Map<String, String> results = new ConcurrentHashMap<>();
        
        // 分批并行处理
        List<List<String>> batches = partitionList(keyList, BATCH_PARALLELISM);
        
        List<CompletableFuture<Void>> futures = batches.stream()
            .map(batch -> CompletableFuture.runAsync(() -> {
                // 先尝试 Redis 批量获取
                List<String> redisResults = l2RedisService.multiGet(batch);
                for (int i = 0; i < batch.size(); i++) {
                    String key = batch.get(i);
                    String value = (i < redisResults.size()) ? redisResults.get(i) : null;
                    if (value != null) {
                        results.put(key, value);
                    }
                }
                
                // 未命中的键从 Memcached 获取
                List<String> missedKeys = batch.stream()
                    .filter(k -> !results.containsKey(k))
                    .toList();
                    
                if (!missedKeys.isEmpty()) {
                    Map<String, Object> mcResults = l3MemcachedService.getBulk(missedKeys);
                    mcResults.forEach((k, v) -> {
                        if (v != null) {
                            results.put(k, v.toString());
                        }
                    });
                }
            }, virtualExecutor))
            .toList();
        
        // 等待所有批次完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(PARALLEL_READ_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
                .join();
        } catch (CompletionException e) {
            log.warn("Batch parallel get partially failed", e);
        }
        
        log.debug("Batch parallel get: requested={}, hit={}", keys.size(), results.size());
        return results;
    }

    /**
     * 带回源的并行读取
     * 缓存未命中时并行执行回源
     */
    public String parallelGetOrLoad(String key, Supplier<String> loader) {
        String cached = parallelGet(key);
        if (cached != null) {
            return cached;
        }
        
        // 使用虚拟线程执行回源
        try {
            return CompletableFuture.supplyAsync(loader, virtualExecutor)
                .orTimeout(1000, TimeUnit.MILLISECONDS)
                .join();
        } catch (Exception e) {
            log.error("Load from source failed: key={}", key, e);
            return null;
        }
    }

    /**
     * 异步预取（不阻塞主流程）
     * 用于预测性缓存加载
     */
    public CompletableFuture<String> asyncPrefetch(String key, Supplier<String> loader) {
        return CompletableFuture.supplyAsync(() -> {
            String cached = parallelGet(key);
            if (cached != null) {
                return cached;
            }
            return loader.get();
        }, virtualExecutor);
    }

    /**
     * 批量异步预取
     */
    public CompletableFuture<Map<String, String>> asyncBatchPrefetch(
            Collection<String> keys, 
            java.util.function.Function<String, String> loader) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> cached = batchParallelGet(keys);
            
            // 未命中的键执行回源
            Set<String> missedKeys = keys.stream()
                .filter(k -> !cached.containsKey(k))
                .collect(Collectors.toSet());
            
            if (!missedKeys.isEmpty()) {
                missedKeys.parallelStream().forEach(key -> {
                    String value = loader.apply(key);
                    if (value != null) {
                        cached.put(key, value);
                    }
                });
            }
            
            return cached;
        }, virtualExecutor);
    }

    /**
     * 获取读取统计
     */
    public ReadStats getStats() {
        return new ReadStats(
            parallelReadTimer.count(),
            parallelReadTimer.mean(TimeUnit.MILLISECONDS),
            parallelReadTimer.max(TimeUnit.MILLISECONDS),
            batchReadTimer.count(),
            batchReadTimer.mean(TimeUnit.MILLISECONDS)
        );
    }
    
    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    public record ReadStats(
        long parallelReadCount,
        double parallelReadAvgMs,
        double parallelReadMaxMs,
        long batchReadCount,
        double batchReadAvgMs
    ) {}
}
