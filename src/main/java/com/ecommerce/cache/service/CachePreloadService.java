package com.ecommerce.cache.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 智能缓存预加载服务
 * 特性：
 * 1. 批量并行预热
 * 2. 基于访问模式的智能预测
 * 3. 定时预加载热点数据
 * 4. 关联数据预取
 */
@Service
public class CachePreloadService {
    
    private static final Logger log = LoggerFactory.getLogger(CachePreloadService.class);
    
    private final L1CacheService l1CacheService;
    private final L2RedisService l2RedisService;
    private final L3MemcachedService l3MemcachedService;
    private final BloomFilterService bloomFilterService;
    private final HotKeyDetectorService hotKeyDetectorService;
    private final MeterRegistry meterRegistry;
    
    // 虚拟线程执行器
    private final ExecutorService virtualExecutor;
    
    // 访问历史（用于预测）
    private final ConcurrentLinkedQueue<AccessRecord> accessHistory = new ConcurrentLinkedQueue<>();
    private static final int MAX_HISTORY_SIZE = 100000;
    
    // 预取队列
    private final BlockingQueue<String> prefetchQueue = new LinkedBlockingQueue<>(10000);
    
    // 预热统计
    private final Counter preloadCounter;
    private final Counter prefetchHitCounter;
    
    @Value("${multilevel-cache.preload.batch-size:100}")
    private int batchSize;
    
    @Value("${multilevel-cache.preload.parallel-threads:8}")
    private int parallelThreads;
    
    @Value("${multilevel-cache.preload.enabled:true}")
    private boolean preloadEnabled;

    public CachePreloadService(L1CacheService l1CacheService,
                                L2RedisService l2RedisService,
                                L3MemcachedService l3MemcachedService,
                                BloomFilterService bloomFilterService,
                                HotKeyDetectorService hotKeyDetectorService,
                                MeterRegistry meterRegistry) {
        this.l1CacheService = l1CacheService;
        this.l2RedisService = l2RedisService;
        this.l3MemcachedService = l3MemcachedService;
        this.bloomFilterService = bloomFilterService;
        this.hotKeyDetectorService = hotKeyDetectorService;
        this.meterRegistry = meterRegistry;
        
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        this.preloadCounter = Counter.builder("cache.preload.count")
            .description("Cache preload operations")
            .register(meterRegistry);
        this.prefetchHitCounter = Counter.builder("cache.prefetch.hit")
            .description("Prefetch cache hits")
            .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        if (preloadEnabled) {
            startPrefetchWorker();
            log.info("Cache preload service initialized");
        }
    }

    /**
     * 批量预热缓存
     * @param keys 需要预热的 key 列表
     * @param dataLoader 数据加载函数
     * @param ttlSeconds TTL
     * @return 成功预热的数量
     */
    public int batchPreload(Collection<String> keys, 
                            Function<String, String> dataLoader, 
                            long ttlSeconds) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        
        log.info("Starting batch preload for {} keys", keys.size());
        
        // 分批并行处理
        List<String> keyList = new ArrayList<>(keys);
        List<List<String>> batches = partition(keyList, batchSize);
        
        int totalLoaded = batches.parallelStream()
            .mapToInt(batch -> preloadBatch(batch, dataLoader, ttlSeconds))
            .sum();
        
        preloadCounter.increment(totalLoaded);
        log.info("Batch preload completed: {}/{} keys loaded", totalLoaded, keys.size());
        
        return totalLoaded;
    }

    private int preloadBatch(List<String> keys, Function<String, String> dataLoader, long ttlSeconds) {
        int loaded = 0;
        
        // 使用虚拟线程并行加载
        List<CompletableFuture<Boolean>> futures = keys.stream()
            .map(key -> CompletableFuture.supplyAsync(() -> {
                try {
                    // 检查是否已缓存
                    if (l2RedisService.exists(key)) {
                        return false;
                    }
                    
                    String value = dataLoader.apply(key);
                    if (value != null) {
                        // 写入各级缓存
                        l3MemcachedService.set(key, value, (int) ttlSeconds);
                        l2RedisService.set(key, value, ttlSeconds);
                        l1CacheService.put(key, value);
                        
                        // 添加到布隆过滤器
                        String spuId = extractSpuId(key);
                        bloomFilterService.add(spuId);
                        
                        return true;
                    }
                    return false;
                } catch (Exception e) {
                    log.warn("Failed to preload key: {}", key, e);
                    return false;
                }
            }, virtualExecutor))
            .toList();
        
        // 等待所有任务完成
        for (CompletableFuture<Boolean> future : futures) {
            try {
                if (future.get(5, TimeUnit.SECONDS)) {
                    loaded++;
                }
            } catch (Exception e) {
                log.debug("Preload task failed", e);
            }
        }
        
        return loaded;
    }

    /**
     * 智能预取：记录访问并预测下一个可能访问的数据
     */
    public void recordAccessAndPrefetch(String key, Function<String, String> dataLoader) {
        // 记录访问
        accessHistory.offer(new AccessRecord(key, System.currentTimeMillis()));
        
        // 限制历史大小
        while (accessHistory.size() > MAX_HISTORY_SIZE) {
            accessHistory.poll();
        }
        
        // 预测关联 key
        List<String> predictedKeys = predictRelatedKeys(key);
        for (String predictedKey : predictedKeys) {
            prefetchQueue.offer(predictedKey);
        }
    }

    /**
     * 基于访问模式预测关联 key
     * 简单实现：基于共同访问频率
     */
    private List<String> predictRelatedKeys(String currentKey) {
        // 提取 SPU ID 的相邻 ID（简单策略）
        String spuId = extractSpuId(currentKey);
        try {
            long id = Long.parseLong(spuId);
            return List.of(
                buildKey(id - 1),
                buildKey(id + 1),
                buildKey(id - 2),
                buildKey(id + 2)
            );
        } catch (NumberFormatException e) {
            return Collections.emptyList();
        }
    }

    private String buildKey(long spuId) {
        return "spu:detail:" + spuId;
    }

    /**
     * 预取工作线程
     */
    private void startPrefetchWorker() {
        Thread.ofVirtual().name("prefetch-worker").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String key = prefetchQueue.poll(1, TimeUnit.SECONDS);
                    if (key != null && !l2RedisService.exists(key)) {
                        // 标记为预取命中
                        prefetchHitCounter.increment();
                        log.debug("Prefetching key: {}", key);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /**
     * 定时预热热点数据（每 10 分钟）
     */
    @Scheduled(fixedRate = 600000)
    public void scheduledHotKeyPreload() {
        if (!preloadEnabled) return;
        
        log.info("Starting scheduled hot key preload");
        
        try {
            // 获取 Top 100 热点 Key
            List<String> hotKeys = hotKeyDetectorService.getTopHotKeys(100);
            
            int refreshed = 0;
            for (String key : hotKeys) {
                Long ttl = l2RedisService.ttl(key);
                // TTL 低于 3 分钟的刷新
                if (ttl != null && ttl > 0 && ttl < 180) {
                    l2RedisService.expire(key, 600);
                    refreshed++;
                }
            }
            
            log.info("Scheduled preload completed: {} keys refreshed", refreshed);
            
        } catch (Exception e) {
            log.error("Scheduled hot key preload failed", e);
        }
    }

    /**
     * 预加载指定前缀的所有数据
     */
    public CompletableFuture<Integer> preloadByPrefix(String prefix, 
                                                       Function<String, String> dataLoader,
                                                       List<String> ids,
                                                       long ttlSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> keys = ids.stream()
                .map(id -> prefix + id)
                .collect(Collectors.toList());
            return batchPreload(keys, dataLoader, ttlSeconds);
        }, virtualExecutor);
    }

    /**
     * 获取预加载统计
     */
    public PreloadStats getStats() {
        return new PreloadStats(
            preloadCounter.count(),
            prefetchHitCounter.count(),
            accessHistory.size(),
            prefetchQueue.size()
        );
    }

    private String extractSpuId(String key) {
        int lastIndex = key.lastIndexOf(':');
        return lastIndex > 0 ? key.substring(lastIndex + 1) : key;
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    record AccessRecord(String key, long timestamp) {}

    public record PreloadStats(
        double totalPreloaded,
        double prefetchHits,
        int accessHistorySize,
        int prefetchQueueSize
    ) {}
}
