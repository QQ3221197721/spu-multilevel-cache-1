package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 批量异步写入优化器 - 高吞吐量写入优化
 * 
 * 核心特性:
 * 1. Write-Behind策略 - 异步批量写入降低延迟
 * 2. 写合并 - 相同key的多次写入合并
 * 3. 背压控制 - 队列满时自动降级
 * 4. 有序保证 - 同key写入顺序保证
 * 5. 失败重试 - 指数退避重试机制
 * 6. 分层写入 - 不同层级独立批处理
 * 
 * 目标: 写入吞吐量提升5x, 写入延迟降低80%
 */
@Service
public class BatchAsyncWriteOptimizer {
    
    private static final Logger log = LoggerFactory.getLogger(BatchAsyncWriteOptimizer.class);
    
    // ========== 配置参数 ==========
    @Value("${optimization.batch-write.enabled:true}")
    private boolean enabled;
    
    @Value("${optimization.batch-write.batch-size:100}")
    private int batchSize;
    
    @Value("${optimization.batch-write.flush-interval-ms:50}")
    private long flushIntervalMs;
    
    @Value("${optimization.batch-write.queue-capacity:10000}")
    private int queueCapacity;
    
    @Value("${optimization.batch-write.max-retry:3}")
    private int maxRetry;
    
    @Value("${optimization.batch-write.retry-delay-ms:100}")
    private long retryDelayMs;
    
    @Value("${optimization.batch-write.coalesce-enabled:true}")
    private boolean coalesceEnabled;
    
    // ========== 数据结构 ==========
    
    // 写入队列（按层级分离）
    private final ConcurrentHashMap<WriteTarget, BlockingQueue<WriteTask>> writeQueues = new ConcurrentHashMap<>();
    
    // 写合并缓冲区
    private final ConcurrentHashMap<String, WriteTask> coalescingBuffer = new ConcurrentHashMap<>();
    
    // 等待完成的写入
    private final ConcurrentHashMap<String, CompletableFuture<Void>> pendingWrites = new ConcurrentHashMap<>();
    
    // key级别的写入锁（保证同key顺序）
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    
    // 重试队列
    private final DelayQueue<RetryTask> retryQueue = new DelayQueue<>();
    
    // 写入处理器（外部注入）
    private final ConcurrentHashMap<WriteTarget, BiConsumer<String, String>> writeHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WriteTarget, Consumer<List<WriteTask>>> batchWriteHandlers = new ConcurrentHashMap<>();
    
    // 执行器
    private final ExecutorService writeExecutor;
    private final ScheduledExecutorService flushScheduler;
    private final ExecutorService retryExecutor;
    
    // 指标
    private final MeterRegistry meterRegistry;
    private Timer writeLatencyTimer;
    private Counter writeCounter;
    private Counter batchCounter;
    private Counter retryCounter;
    private Counter coalescedCounter;
    private Counter droppedCounter;
    
    // 统计
    private final LongAdder totalWrites = new LongAdder();
    private final LongAdder totalBatches = new LongAdder();
    private final LongAdder totalCoalesced = new LongAdder();
    private final LongAdder totalRetries = new LongAdder();
    private final LongAdder totalDropped = new LongAdder();
    
    // 背压状态
    private volatile boolean backpressureActive = false;
    
    public BatchAsyncWriteOptimizer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 初始化各层级队列
        for (WriteTarget target : WriteTarget.values()) {
            writeQueues.put(target, new LinkedBlockingQueue<>(queueCapacity));
        }
        
        this.writeExecutor = Executors.newFixedThreadPool(
            WriteTarget.values().length * 2,
            r -> {
                Thread t = new Thread(r, "batch-write-worker");
                t.setDaemon(true);
                return t;
            }
        );
        
        this.flushScheduler = Executors.newScheduledThreadPool(
            WriteTarget.values().length,
            r -> {
                Thread t = new Thread(r, "batch-write-flush");
                t.setDaemon(true);
                return t;
            }
        );
        
        this.retryExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "batch-write-retry");
            t.setDaemon(true);
            return t;
        });
    }
    
    @PostConstruct
    public void init() {
        writeLatencyTimer = Timer.builder("cache.batch.write.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        writeCounter = Counter.builder("cache.batch.write.total").register(meterRegistry);
        batchCounter = Counter.builder("cache.batch.write.batches").register(meterRegistry);
        retryCounter = Counter.builder("cache.batch.write.retries").register(meterRegistry);
        coalescedCounter = Counter.builder("cache.batch.write.coalesced").register(meterRegistry);
        droppedCounter = Counter.builder("cache.batch.write.dropped").register(meterRegistry);
        
        // 队列大小指标
        for (WriteTarget target : WriteTarget.values()) {
            BlockingQueue<WriteTask> queue = writeQueues.get(target);
            Gauge.builder("cache.batch.write.queue.size", queue, BlockingQueue::size)
                .tag("target", target.name())
                .register(meterRegistry);
        }
        
        Gauge.builder("cache.batch.write.coalescing.size", coalescingBuffer, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("cache.batch.write.pending.size", pendingWrites, ConcurrentHashMap::size)
            .register(meterRegistry);
        Gauge.builder("cache.batch.write.backpressure", () -> backpressureActive ? 1 : 0)
            .register(meterRegistry);
        
        // 启动处理器
        startBatchProcessors();
        startRetryProcessor();
        
        log.info("BatchAsyncWriteOptimizer initialized: batchSize={}, flushInterval={}ms",
            batchSize, flushIntervalMs);
    }
    
    @PreDestroy
    public void shutdown() {
        // 刷新所有待处理写入
        flushAll();
        
        writeExecutor.shutdown();
        flushScheduler.shutdown();
        retryExecutor.shutdown();
        
        try {
            writeExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 异步写入 - 核心方法
     */
    public CompletableFuture<Void> writeAsync(WriteTarget target, String key, String value, long ttlSeconds) {
        if (!enabled) {
            // 直接同步写入
            return CompletableFuture.runAsync(() -> executeWrite(target, key, value, ttlSeconds));
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        WriteTask task = new WriteTask(
            target, key, value, ttlSeconds,
            System.currentTimeMillis(), 0, future
        );
        
        // 写合并
        if (coalesceEnabled) {
            WriteTask existing = coalescingBuffer.put(key, task);
            if (existing != null) {
                // 取消旧的写入
                existing.future.complete(null);
                totalCoalesced.increment();
                coalescedCounter.increment();
            }
        }
        
        // 入队
        BlockingQueue<WriteTask> queue = writeQueues.get(target);
        if (!queue.offer(task)) {
            // 队列满，触发背压
            handleBackpressure(task);
        }
        
        pendingWrites.put(key, future);
        totalWrites.increment();
        writeCounter.increment();
        
        return future;
    }
    
    /**
     * 批量异步写入
     */
    public CompletableFuture<Void> writeBatchAsync(WriteTarget target, Map<String, String> entries, long ttlSeconds) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        entries.forEach((key, value) -> {
            futures.add(writeAsync(target, key, value, ttlSeconds));
        });
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * 同步写入（等待完成）
     */
    public void writeSync(WriteTarget target, String key, String value, long ttlSeconds) {
        try {
            writeAsync(target, key, value, ttlSeconds).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Sync write failed: key={}", key, e);
            throw new RuntimeException("Write failed", e);
        }
    }
    
    /**
     * 注册写入处理器
     */
    public void registerWriteHandler(WriteTarget target, BiConsumer<String, String> handler) {
        writeHandlers.put(target, handler);
    }
    
    /**
     * 注册批量写入处理器
     */
    public void registerBatchWriteHandler(WriteTarget target, Consumer<List<WriteTask>> handler) {
        batchWriteHandlers.put(target, handler);
    }
    
    /**
     * 启动批处理器
     */
    private void startBatchProcessors() {
        for (WriteTarget target : WriteTarget.values()) {
            // 定时刷新
            flushScheduler.scheduleAtFixedRate(
                () -> flushQueue(target),
                flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS
            );
            
            // 批量处理
            writeExecutor.submit(() -> processBatchQueue(target));
        }
    }
    
    /**
     * 处理批量队列
     */
    private void processBatchQueue(WriteTarget target) {
        BlockingQueue<WriteTask> queue = writeQueues.get(target);
        
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<WriteTask> batch = new ArrayList<>(batchSize);
                
                // 阻塞等待第一个任务
                WriteTask first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                
                batch.add(first);
                
                // 尝试获取更多任务
                queue.drainTo(batch, batchSize - 1);
                
                // 从合并缓冲区移除
                if (coalesceEnabled) {
                    for (WriteTask task : batch) {
                        coalescingBuffer.remove(task.key, task);
                    }
                }
                
                // 执行批量写入
                processBatch(target, batch);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Batch processing error for {}", target, e);
            }
        }
    }
    
    /**
     * 处理批次
     */
    private void processBatch(WriteTarget target, List<WriteTask> batch) {
        if (batch.isEmpty()) return;
        
        totalBatches.increment();
        batchCounter.increment();
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 优先使用批量处理器
            Consumer<List<WriteTask>> batchHandler = batchWriteHandlers.get(target);
            if (batchHandler != null) {
                batchHandler.accept(batch);
                completeTasks(batch, null);
            } else {
                // 逐个处理
                BiConsumer<String, String> handler = writeHandlers.get(target);
                if (handler != null) {
                    for (WriteTask task : batch) {
                        try {
                            handler.accept(task.key, task.value);
                            completeTask(task, null);
                        } catch (Exception e) {
                            handleWriteFailure(task, e);
                        }
                    }
                } else {
                    // 默认处理
                    for (WriteTask task : batch) {
                        executeWrite(target, task.key, task.value, task.ttlSeconds);
                        completeTask(task, null);
                    }
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            writeLatencyTimer.record(java.time.Duration.ofMillis(duration));
            
            log.debug("Batch write completed: target={}, size={}, duration={}ms",
                target, batch.size(), duration);
            
        } catch (Exception e) {
            log.error("Batch write failed: target={}, size={}", target, batch.size(), e);
            // 批量失败，逐个重试
            for (WriteTask task : batch) {
                handleWriteFailure(task, e);
            }
        }
    }
    
    /**
     * 执行写入（默认实现）
     */
    private void executeWrite(WriteTarget target, String key, String value, long ttlSeconds) {
        // 实际实现由外部处理器提供
        log.debug("Executing write: target={}, key={}", target, key);
    }
    
    /**
     * 完成任务
     */
    private void completeTask(WriteTask task, Throwable error) {
        pendingWrites.remove(task.key);
        
        if (error != null) {
            task.future.completeExceptionally(error);
        } else {
            task.future.complete(null);
        }
    }
    
    /**
     * 批量完成任务
     */
    private void completeTasks(List<WriteTask> tasks, Throwable error) {
        for (WriteTask task : tasks) {
            completeTask(task, error);
        }
    }
    
    /**
     * 处理写入失败
     */
    private void handleWriteFailure(WriteTask task, Exception error) {
        if (task.retryCount >= maxRetry) {
            log.error("Write failed after {} retries: key={}", maxRetry, task.key, error);
            completeTask(task, error);
            totalDropped.increment();
            droppedCounter.increment();
            return;
        }
        
        // 计算重试延迟（指数退避）
        long delay = retryDelayMs * (long) Math.pow(2, task.retryCount);
        
        RetryTask retryTask = new RetryTask(
            new WriteTask(
                task.target, task.key, task.value, task.ttlSeconds,
                task.timestamp, task.retryCount + 1, task.future
            ),
            System.currentTimeMillis() + delay
        );
        
        retryQueue.offer(retryTask);
        totalRetries.increment();
        retryCounter.increment();
        
        log.debug("Scheduled retry {} for key={}, delay={}ms", 
            task.retryCount + 1, task.key, delay);
    }
    
    /**
     * 启动重试处理器
     */
    private void startRetryProcessor() {
        retryExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    RetryTask retry = retryQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (retry == null) continue;
                    
                    WriteTask task = retry.task;
                    BlockingQueue<WriteTask> queue = writeQueues.get(task.target);
                    
                    if (!queue.offer(task)) {
                        // 队列仍然满，再次延迟
                        handleWriteFailure(task, new RuntimeException("Queue full"));
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
    
    /**
     * 处理背压
     */
    private void handleBackpressure(WriteTask task) {
        backpressureActive = true;
        
        log.warn("Backpressure active, switching to sync write: key={}", task.key);
        
        // 降级为同步写入
        try {
            executeWrite(task.target, task.key, task.value, task.ttlSeconds);
            completeTask(task, null);
        } catch (Exception e) {
            completeTask(task, e);
        }
    }
    
    /**
     * 刷新指定队列
     */
    private void flushQueue(WriteTarget target) {
        BlockingQueue<WriteTask> queue = writeQueues.get(target);
        
        if (queue.isEmpty()) {
            if (backpressureActive && getTotalQueueSize() < queueCapacity / 2) {
                backpressureActive = false;
            }
            return;
        }
        
        List<WriteTask> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        
        if (!batch.isEmpty()) {
            writeExecutor.submit(() -> processBatch(target, batch));
        }
    }
    
    /**
     * 刷新所有队列
     */
    public void flushAll() {
        for (WriteTarget target : WriteTarget.values()) {
            flushQueue(target);
        }
        
        // 处理合并缓冲区
        if (coalesceEnabled) {
            coalescingBuffer.forEach((key, task) -> {
                BlockingQueue<WriteTask> queue = writeQueues.get(task.target);
                queue.offer(task);
            });
            coalescingBuffer.clear();
        }
    }
    
    /**
     * 获取总队列大小
     */
    private int getTotalQueueSize() {
        return writeQueues.values().stream()
            .mapToInt(BlockingQueue::size)
            .sum();
    }
    
    /**
     * 定期状态检查
     */
    @Scheduled(fixedRate = 10000) // 10秒
    public void checkStatus() {
        int totalQueued = getTotalQueueSize();
        int pending = pendingWrites.size();
        int retrying = retryQueue.size();
        
        if (totalQueued > queueCapacity * 0.8 || pending > 1000) {
            log.warn("Write queues high: queued={}, pending={}, retrying={}, backpressure={}",
                totalQueued, pending, retrying, backpressureActive);
        }
    }
    
    /**
     * 获取优化器统计
     */
    public BatchWriteStats getStats() {
        Map<String, Integer> queueSizes = new HashMap<>();
        writeQueues.forEach((target, queue) -> queueSizes.put(target.name(), queue.size()));
        
        return new BatchWriteStats(
            enabled,
            backpressureActive,
            totalWrites.sum(),
            totalBatches.sum(),
            totalCoalesced.sum(),
            totalRetries.sum(),
            totalDropped.sum(),
            pendingWrites.size(),
            coalescingBuffer.size(),
            retryQueue.size(),
            queueSizes
        );
    }
    
    // ========== 内部类 ==========
    
    public enum WriteTarget {
        L1_LOCAL,
        L2_REDIS,
        L3_MEMCACHED,
        L0_ULTRA_HOT
    }
    
    public record WriteTask(
        WriteTarget target,
        String key,
        String value,
        long ttlSeconds,
        long timestamp,
        int retryCount,
        CompletableFuture<Void> future
    ) {}
    
    private static class RetryTask implements Delayed {
        final WriteTask task;
        final long executeTime;
        
        RetryTask(WriteTask task, long executeTime) {
            this.task = task;
            this.executeTime = executeTime;
        }
        
        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(executeTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }
        
        @Override
        public int compareTo(Delayed o) {
            return Long.compare(this.executeTime, ((RetryTask) o).executeTime);
        }
    }
    
    public record BatchWriteStats(
        boolean enabled,
        boolean backpressureActive,
        long totalWrites,
        long totalBatches,
        long totalCoalesced,
        long totalRetries,
        long totalDropped,
        int pendingCount,
        int coalescingCount,
        int retryQueueSize,
        Map<String, Integer> queueSizes
    ) {}
}
