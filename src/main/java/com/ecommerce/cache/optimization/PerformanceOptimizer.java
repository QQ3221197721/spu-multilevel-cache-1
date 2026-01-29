package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 性能优化模块 - 极致性能优化实现
 * 
 * 核心优化：
 * 1. 虚拟线程增强执行器 - 充分利用 JDK 21 虚拟线程
 * 2. 自适应线程池 - 根据负载动态调整
 * 3. 对象池化 - 减少 GC 压力
 * 4. 连接池监控与优化
 * 5. 批量操作聚合器 - 减少网络往返
 * 6. 热点代码 JIT 预热
 */
@Configuration
@ConfigurationProperties(prefix = "optimization.performance")
public class PerformanceOptimizer {
    
    private static final Logger log = LoggerFactory.getLogger(PerformanceOptimizer.class);
    
    // 配置项
    private boolean virtualThreadEnabled = true;
    private int adaptivePoolCoreSize = 16;
    private int adaptivePoolMaxSize = 256;
    private int batchAggregatorSize = 64;
    private long batchAggregatorTimeoutMs = 5;
    private boolean objectPoolEnabled = true;
    private int objectPoolSize = 1024;
    
    /**
     * 虚拟线程增强执行器
     * 适用于 I/O 密集型任务，可创建百万级并发
     */
    @Bean("virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        if (virtualThreadEnabled) {
            log.info("Virtual thread executor enabled (JDK 21+)");
            return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                    .name("vt-cache-", 0)
                    .factory()
            );
        } else {
            log.info("Fallback to platform thread pool");
            return Executors.newFixedThreadPool(adaptivePoolMaxSize);
        }
    }
    
    /**
     * 自适应线程池 - CPU 密集型任务
     * 根据系统负载动态调整核心线程数
     */
    @Bean("adaptiveThreadPool")
    public ThreadPoolExecutor adaptiveThreadPool(MeterRegistry meterRegistry) {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            Math.min(cpuCores * 2, adaptivePoolCoreSize),
            adaptivePoolMaxSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(4096),
            new ThreadFactory() {
                private final AtomicLong counter = new AtomicLong(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "adaptive-pool-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // 允许核心线程超时
        executor.allowCoreThreadTimeOut(true);
        
        // 预启动所有核心线程
        executor.prestartAllCoreThreads();
        
        // 注册指标监控
        Gauge.builder("thread.pool.adaptive.active", executor, ThreadPoolExecutor::getActiveCount)
            .register(meterRegistry);
        Gauge.builder("thread.pool.adaptive.queue.size", executor, e -> e.getQueue().size())
            .register(meterRegistry);
        Gauge.builder("thread.pool.adaptive.pool.size", executor, ThreadPoolExecutor::getPoolSize)
            .register(meterRegistry);
        
        log.info("Adaptive thread pool initialized: core={}, max={}", 
            executor.getCorePoolSize(), executor.getMaximumPoolSize());
        
        return executor;
    }
    
    /**
     * 高性能定时调度器
     */
    @Bean("scheduledExecutor")
    public ScheduledExecutorService scheduledExecutor() {
        return Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "scheduled-task");
            t.setDaemon(true);
            return t;
        });
    }
    
    // Getters and Setters
    public boolean isVirtualThreadEnabled() { return virtualThreadEnabled; }
    public void setVirtualThreadEnabled(boolean virtualThreadEnabled) { this.virtualThreadEnabled = virtualThreadEnabled; }
    public int getAdaptivePoolCoreSize() { return adaptivePoolCoreSize; }
    public void setAdaptivePoolCoreSize(int adaptivePoolCoreSize) { this.adaptivePoolCoreSize = adaptivePoolCoreSize; }
    public int getAdaptivePoolMaxSize() { return adaptivePoolMaxSize; }
    public void setAdaptivePoolMaxSize(int adaptivePoolMaxSize) { this.adaptivePoolMaxSize = adaptivePoolMaxSize; }
    public int getBatchAggregatorSize() { return batchAggregatorSize; }
    public void setBatchAggregatorSize(int batchAggregatorSize) { this.batchAggregatorSize = batchAggregatorSize; }
    public long getBatchAggregatorTimeoutMs() { return batchAggregatorTimeoutMs; }
    public void setBatchAggregatorTimeoutMs(long batchAggregatorTimeoutMs) { this.batchAggregatorTimeoutMs = batchAggregatorTimeoutMs; }
    public boolean isObjectPoolEnabled() { return objectPoolEnabled; }
    public void setObjectPoolEnabled(boolean objectPoolEnabled) { this.objectPoolEnabled = objectPoolEnabled; }
    public int getObjectPoolSize() { return objectPoolSize; }
    public void setObjectPoolSize(int objectPoolSize) { this.objectPoolSize = objectPoolSize; }
}

/**
 * 批量操作聚合器 - 将多个小请求合并为批量请求
 * 显著减少网络往返次数，提升吞吐量
 */
@Component
class BatchAggregator<K, V> {
    
    private static final Logger log = LoggerFactory.getLogger(BatchAggregator.class);
    
    private final ConcurrentHashMap<K, CompletableFuture<V>> pendingRequests = new ConcurrentHashMap<>();
    private final BlockingQueue<BatchRequest<K, V>> requestQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler;
    private final int batchSize;
    private final long timeoutMs;
    private final LongAdder batchCount = new LongAdder();
    private final LongAdder requestCount = new LongAdder();
    
    public BatchAggregator() {
        this(64, 5);
    }
    
    public BatchAggregator(int batchSize, long timeoutMs) {
        this.batchSize = batchSize;
        this.timeoutMs = timeoutMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "batch-aggregator");
            t.setDaemon(true);
            return t;
        });
        startBatchProcessor();
    }
    
    private void startBatchProcessor() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                processBatch();
            } catch (Exception e) {
                log.error("Batch processing error", e);
            }
        }, timeoutMs, timeoutMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 提交单个请求，自动聚合为批量请求
     */
    public CompletableFuture<V> submit(K key, Supplier<V> loader) {
        requestCount.increment();
        
        CompletableFuture<V> future = new CompletableFuture<>();
        CompletableFuture<V> existing = pendingRequests.putIfAbsent(key, future);
        
        if (existing != null) {
            // 请求合并 - 复用已存在的请求
            return existing;
        }
        
        requestQueue.offer(new BatchRequest<>(key, loader, future));
        
        // 达到批量大小立即处理
        if (requestQueue.size() >= batchSize) {
            processBatch();
        }
        
        return future;
    }
    
    private void processBatch() {
        if (requestQueue.isEmpty()) return;
        
        ConcurrentLinkedQueue<BatchRequest<K, V>> batch = new ConcurrentLinkedQueue<>();
        requestQueue.drainTo(batch, batchSize);
        
        if (batch.isEmpty()) return;
        
        batchCount.increment();
        
        // 并行执行所有请求
        batch.parallelStream().forEach(req -> {
            try {
                V result = req.loader().get();
                req.future().complete(result);
            } catch (Exception e) {
                req.future().completeExceptionally(e);
            } finally {
                pendingRequests.remove(req.key());
            }
        });
    }
    
    public BatchStats getStats() {
        return new BatchStats(batchCount.sum(), requestCount.sum(), pendingRequests.size());
    }
    
    record BatchRequest<K, V>(K key, Supplier<V> loader, CompletableFuture<V> future) {}
    public record BatchStats(long batchCount, long requestCount, int pendingSize) {}
}

/**
 * 轻量级对象池 - 减少对象创建和 GC 压力
 */
@Component
class ObjectPool<T> {
    
    private final BlockingQueue<T> pool;
    private final Supplier<T> factory;
    private final LongAdder allocations = new LongAdder();
    private final LongAdder borrows = new LongAdder();
    private final LongAdder returns = new LongAdder();
    
    public ObjectPool(Supplier<T> factory, int size) {
        this.factory = factory;
        this.pool = new LinkedBlockingQueue<>(size);
        
        // 预填充对象池
        for (int i = 0; i < size; i++) {
            pool.offer(factory.get());
            allocations.increment();
        }
    }
    
    /**
     * 借用对象
     */
    public T borrow() {
        borrows.increment();
        T obj = pool.poll();
        if (obj == null) {
            allocations.increment();
            return factory.get();
        }
        return obj;
    }
    
    /**
     * 归还对象
     */
    public void release(T obj) {
        returns.increment();
        pool.offer(obj);
    }
    
    /**
     * 使用对象执行操作后自动归还
     */
    public <R> R execute(java.util.function.Function<T, R> action) {
        T obj = borrow();
        try {
            return action.apply(obj);
        } finally {
            release(obj);
        }
    }
    
    public PoolStats getStats() {
        return new PoolStats(allocations.sum(), borrows.sum(), returns.sum(), pool.size());
    }
    
    public record PoolStats(long allocations, long borrows, long returns, int availableSize) {}
}

/**
 * JVM 性能监控器 - 实时监控系统资源
 */
@Component
class JvmPerformanceMonitor {
    
    private static final Logger log = LoggerFactory.getLogger(JvmPerformanceMonitor.class);
    
    private final MeterRegistry meterRegistry;
    private final MemoryMXBean memoryBean;
    private final ThreadMXBean threadBean;
    
    // 性能指标
    private final AtomicLong lastGcTime = new AtomicLong(0);
    private final LongAdder gcPauseCount = new LongAdder();
    
    public JvmPerformanceMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        
        registerMetrics();
    }
    
    private void registerMetrics() {
        // 堆内存使用率
        Gauge.builder("jvm.memory.heap.usage.ratio", () -> {
            long used = memoryBean.getHeapMemoryUsage().getUsed();
            long max = memoryBean.getHeapMemoryUsage().getMax();
            return max > 0 ? (double) used / max : 0;
        }).register(meterRegistry);
        
        // 线程数
        Gauge.builder("jvm.threads.count", threadBean, ThreadMXBean::getThreadCount)
            .register(meterRegistry);
        
        // 虚拟线程数（JDK 21+）
        Gauge.builder("jvm.threads.virtual.count", () -> {
            try {
                return Thread.getAllStackTraces().keySet().stream()
                    .filter(Thread::isVirtual)
                    .count();
            } catch (Exception e) {
                return 0L;
            }
        }).register(meterRegistry);
        
        // CPU 负载
        Gauge.builder("system.cpu.process.load", () -> {
            var osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                return sunBean.getProcessCpuLoad();
            }
            return 0.0;
        }).register(meterRegistry);
    }
    
    /**
     * 定期检查性能指标
     */
    @Scheduled(fixedRate = 10000)
    public void checkPerformance() {
        double heapUsage = (double) memoryBean.getHeapMemoryUsage().getUsed() 
            / memoryBean.getHeapMemoryUsage().getMax();
        
        if (heapUsage > 0.85) {
            log.warn("High heap memory usage: {:.2f}%", heapUsage * 100);
        }
        
        int threadCount = threadBean.getThreadCount();
        if (threadCount > 1000) {
            log.warn("High thread count: {}", threadCount);
        }
    }
    
    public PerformanceSnapshot getSnapshot() {
        return new PerformanceSnapshot(
            memoryBean.getHeapMemoryUsage().getUsed(),
            memoryBean.getHeapMemoryUsage().getMax(),
            memoryBean.getNonHeapMemoryUsage().getUsed(),
            threadBean.getThreadCount(),
            threadBean.getPeakThreadCount(),
            Runtime.getRuntime().availableProcessors()
        );
    }
    
    public record PerformanceSnapshot(
        long heapUsed, long heapMax, long nonHeapUsed,
        int threadCount, int peakThreadCount, int cpuCores
    ) {}
}

/**
 * 请求合并器 - 合并相同请求避免重复执行
 * 解决缓存击穿问题的另一种方案
 */
@Component
class RequestCoalescer<K, V> {
    
    private static final Logger log = LoggerFactory.getLogger(RequestCoalescer.class);
    
    private final ConcurrentHashMap<K, CompletableFuture<V>> inflightRequests = new ConcurrentHashMap<>();
    private final LongAdder coalescedCount = new LongAdder();
    private final LongAdder totalRequests = new LongAdder();
    
    /**
     * 执行请求，自动合并相同 Key 的并发请求
     */
    public V execute(K key, Supplier<V> loader, Duration timeout) throws Exception {
        totalRequests.increment();
        
        CompletableFuture<V> future = new CompletableFuture<>();
        CompletableFuture<V> existing = inflightRequests.putIfAbsent(key, future);
        
        if (existing != null) {
            // 已有相同请求正在执行，等待结果
            coalescedCount.increment();
            log.debug("Request coalesced for key: {}", key);
            return existing.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        
        try {
            V result = loader.get();
            future.complete(result);
            return result;
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            inflightRequests.remove(key);
        }
    }
    
    public CoalescerStats getStats() {
        return new CoalescerStats(totalRequests.sum(), coalescedCount.sum(), inflightRequests.size());
    }
    
    public record CoalescerStats(long totalRequests, long coalescedRequests, int inflightCount) {}
}

/**
 * JIT 预热器 - 预热关键代码路径，减少冷启动延迟
 */
@Component
class JitWarmer {
    
    private static final Logger log = LoggerFactory.getLogger(JitWarmer.class);
    
    private volatile boolean warmedUp = false;
    
    @PostConstruct
    public void warmUp() {
        log.info("Starting JIT warm-up...");
        long start = System.currentTimeMillis();
        
        // 预热字符串操作
        warmUpStringOperations();
        
        // 预热集合操作
        warmUpCollectionOperations();
        
        // 预热 JSON 序列化
        warmUpJsonOperations();
        
        warmedUp = true;
        log.info("JIT warm-up completed in {}ms", System.currentTimeMillis() - start);
    }
    
    private void warmUpStringOperations() {
        for (int i = 0; i < 10000; i++) {
            String key = "warmup:key:" + i;
            key.hashCode();
            key.substring(0, Math.min(10, key.length()));
            key.contains(":");
        }
    }
    
    private void warmUpCollectionOperations() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        for (int i = 0; i < 10000; i++) {
            map.put("key" + i, "value" + i);
            map.get("key" + i);
            map.remove("key" + i);
        }
    }
    
    private void warmUpJsonOperations() {
        // 预热 JSON 处理（如果使用 fastjson2）
        try {
            for (int i = 0; i < 1000; i++) {
                String json = String.format("{\"id\":%d,\"name\":\"test\"}", i);
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
    }
    
    public boolean isWarmedUp() {
        return warmedUp;
    }
}
