package com.ecommerce.cache.optimization.v5;

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
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 流量整形与削峰填谷服务 - 平滑流量毛刺
 * 
 * 核心功能：
 * 1. 请求排队 - 超过阈值的请求进入等待队列
 * 2. 漏桶限流 - 恒定速率处理请求
 * 3. 令牌桶限流 - 允许突发流量
 * 4. 滑动窗口限流 - 精确的时间窗口控制
 * 5. 智能降级 - 根据系统负载自动降级
 * 6. 流量预测 - 基于历史数据预测流量高峰
 * 7. 弹性队列 - 根据等待时间动态调整队列
 * 
 * 目标：10W QPS 场景下，请求毛刺减少 80%，响应时间标准差降低 60%
 */
@Service
public class TrafficShapingService {
    
    private static final Logger log = LoggerFactory.getLogger(TrafficShapingService.class);
    
    // ========== 配置 ==========
    @Value("${optimization.traffic.leaky-bucket.rate:10000}")
    private int leakyBucketRate;
    
    @Value("${optimization.traffic.token-bucket.capacity:50000}")
    private int tokenBucketCapacity;
    
    @Value("${optimization.traffic.token-bucket.refill-rate:10000}")
    private int tokenRefillRate;
    
    @Value("${optimization.traffic.queue.max-size:10000}")
    private int maxQueueSize;
    
    @Value("${optimization.traffic.queue.timeout-ms:100}")
    private long queueTimeoutMs;
    
    @Value("${optimization.traffic.sliding-window.size-ms:1000}")
    private int slidingWindowSizeMs;
    
    @Value("${optimization.traffic.adaptive.enabled:true}")
    private boolean adaptiveEnabled;
    
    @Value("${optimization.traffic.prediction.enabled:true}")
    private boolean predictionEnabled;
    
    @Value("${optimization.traffic.enabled:true}")
    private boolean enabled;
    
    // ========== 限流器 ==========
    private final LeakyBucket leakyBucket;
    private final TokenBucket tokenBucket;
    private final SlidingWindowLimiter slidingWindowLimiter;
    private final ElasticQueue<PendingRequest<?>> pendingQueue;
    
    // ========== 流量预测 ==========
    private final TrafficPredictor trafficPredictor;
    
    // ========== 统计 ==========
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder acceptedRequests = new LongAdder();
    private final LongAdder rejectedRequests = new LongAdder();
    private final LongAdder queuedRequests = new LongAdder();
    private final LongAdder timeoutRequests = new LongAdder();
    
    // ========== 指标 ==========
    private final MeterRegistry meterRegistry;
    private Timer requestProcessTimer;
    private Timer queueWaitTimer;
    private Counter rejectionCounter;
    
    public TrafficShapingService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 初始化组件（使用默认值，PostConstruct 中会更新）
        this.leakyBucket = new LeakyBucket(10000);
        this.tokenBucket = new TokenBucket(50000, 10000);
        this.slidingWindowLimiter = new SlidingWindowLimiter(1000, 10000);
        this.pendingQueue = new ElasticQueue<>(10000);
        this.trafficPredictor = new TrafficPredictor();
    }
    
    @PostConstruct
    public void init() {
        // 更新配置
        leakyBucket.setRate(leakyBucketRate);
        tokenBucket.setCapacity(tokenBucketCapacity);
        tokenBucket.setRefillRate(tokenRefillRate);
        slidingWindowLimiter.setWindowSize(slidingWindowSizeMs);
        pendingQueue.setMaxSize(maxQueueSize);
        
        // 注册指标
        requestProcessTimer = Timer.builder("traffic.request.process.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        queueWaitTimer = Timer.builder("traffic.queue.wait.latency")
            .publishPercentileHistogram()
            .register(meterRegistry);
        rejectionCounter = Counter.builder("traffic.request.rejected")
            .register(meterRegistry);
        
        Gauge.builder("traffic.total.requests", totalRequests, LongAdder::sum)
            .register(meterRegistry);
        Gauge.builder("traffic.accepted.requests", acceptedRequests, LongAdder::sum)
            .register(meterRegistry);
        Gauge.builder("traffic.queue.size", pendingQueue, ElasticQueue::size)
            .register(meterRegistry);
        Gauge.builder("traffic.token.available", tokenBucket, TokenBucket::getAvailableTokens)
            .register(meterRegistry);
        Gauge.builder("traffic.current.qps", slidingWindowLimiter, SlidingWindowLimiter::getCurrentQps)
            .register(meterRegistry);
        
        // 启动队列处理器
        startQueueProcessor();
        
        log.info("TrafficShapingService initialized: leakyRate={}, tokenCapacity={}, queueSize={}",
            leakyBucketRate, tokenBucketCapacity, maxQueueSize);
    }
    
    /**
     * 带流量整形的请求执行
     * 
     * @param key 请求标识
     * @param supplier 请求执行逻辑
     * @return 执行结果
     */
    public <T> T executeWithShaping(String key, Supplier<T> supplier) throws Exception {
        if (!enabled) {
            return supplier.get();
        }
        
        return requestProcessTimer.record(() -> {
            totalRequests.increment();
            
            // 1. 滑动窗口检查
            if (!slidingWindowLimiter.tryAcquire()) {
                return handleOverload(key, supplier);
            }
            
            // 2. 令牌桶检查（允许突发）
            if (tokenBucket.tryAcquire()) {
                acceptedRequests.increment();
                return supplier.get();
            }
            
            // 3. 漏桶检查（恒定速率）
            if (leakyBucket.tryAcquire()) {
                acceptedRequests.increment();
                return supplier.get();
            }
            
            // 4. 进入队列等待
            return handleOverload(key, supplier);
        });
    }
    
    /**
     * 异步执行（不阻塞）
     */
    public <T> CompletableFuture<T> executeAsync(String key, Supplier<T> supplier) {
        if (!enabled) {
            return CompletableFuture.supplyAsync(supplier);
        }
        
        // 直接通过检查
        if (tokenBucket.tryAcquire() || leakyBucket.tryAcquire()) {
            totalRequests.increment();
            acceptedRequests.increment();
            return CompletableFuture.supplyAsync(supplier);
        }
        
        // 进入队列
        return enqueue(key, supplier);
    }
    
    /**
     * 获取当前限流状态
     */
    public TrafficStatus getStatus() {
        return new TrafficStatus(
            totalRequests.sum(),
            acceptedRequests.sum(),
            rejectedRequests.sum(),
            queuedRequests.sum(),
            timeoutRequests.sum(),
            pendingQueue.size(),
            tokenBucket.getAvailableTokens(),
            slidingWindowLimiter.getCurrentQps(),
            leakyBucket.getProcessedCount(),
            getAcceptanceRate()
        );
    }
    
    /**
     * 获取流量预测
     */
    public TrafficPrediction getPrediction() {
        return trafficPredictor.predict();
    }
    
    /**
     * 动态调整限流参数
     */
    public void adjustLimits(int newLeakyRate, int newTokenCapacity) {
        leakyBucket.setRate(newLeakyRate);
        tokenBucket.setCapacity(newTokenCapacity);
        log.info("Traffic limits adjusted: leakyRate={}, tokenCapacity={}", 
            newLeakyRate, newTokenCapacity);
    }
    
    /**
     * 定期自适应调整
     */
    @Scheduled(fixedRate = 10000)
    public void adaptiveTuning() {
        if (!adaptiveEnabled || !enabled) return;
        
        double acceptanceRate = getAcceptanceRate();
        long currentQps = slidingWindowLimiter.getCurrentQps();
        
        // 根据接受率调整
        if (acceptanceRate < 0.8 && currentQps > leakyBucketRate) {
            // 拒绝率过高，适当提高限制
            int newRate = Math.min((int) (leakyBucketRate * 1.2), 100000);
            leakyBucket.setRate(newRate);
            log.info("Adaptive tuning: increased leaky rate to {}", newRate);
        } else if (acceptanceRate > 0.99 && currentQps < leakyBucketRate * 0.5) {
            // 限制过于宽松，适当降低
            int newRate = Math.max((int) (leakyBucketRate * 0.9), 1000);
            leakyBucket.setRate(newRate);
            log.info("Adaptive tuning: decreased leaky rate to {}", newRate);
        }
        
        // 记录流量数据用于预测
        if (predictionEnabled) {
            trafficPredictor.recordTraffic(currentQps);
        }
    }
    
    // ========== 私有方法 ==========
    
    @SuppressWarnings("unchecked")
    private <T> T handleOverload(String key, Supplier<T> supplier) throws RuntimeException {
        // 检查队列是否已满
        if (pendingQueue.isFull()) {
            rejectedRequests.increment();
            rejectionCounter.increment();
            throw new TrafficOverloadException("Traffic limit exceeded, queue full");
        }
        
        // 进入队列等待
        try {
            CompletableFuture<T> future = enqueue(key, supplier);
            queuedRequests.increment();
            
            // 等待结果
            return queueWaitTimer.record(() -> {
                try {
                    return future.get(queueTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    timeoutRequests.increment();
                    future.cancel(true);
                    throw new TrafficOverloadException("Queue wait timeout");
                } catch (Exception e) {
                    throw new RuntimeException("Queue processing failed", e);
                }
            });
        } catch (Exception e) {
            if (e instanceof TrafficOverloadException) throw (TrafficOverloadException) e;
            throw new RuntimeException(e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> enqueue(String key, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        PendingRequest<T> request = new PendingRequest<>(key, supplier, future, System.currentTimeMillis());
        
        if (!pendingQueue.offer((PendingRequest<Object>) (PendingRequest<?>) request)) {
            future.completeExceptionally(new TrafficOverloadException("Queue full"));
        }
        
        return future;
    }
    
    private void startQueueProcessor() {
        Thread processor = Thread.ofVirtual().name("traffic-queue-processor").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    PendingRequest<?> request = pendingQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (request != null) {
                        processRequest(request);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Queue processor error", e);
                }
            }
        });
        processor.setDaemon(true);
    }
    
    @SuppressWarnings("unchecked")
    private <T> void processRequest(PendingRequest<T> request) {
        // 检查是否超时
        long waitTime = System.currentTimeMillis() - request.submitTime;
        if (waitTime > queueTimeoutMs) {
            request.future.completeExceptionally(
                new TrafficOverloadException("Request timeout in queue"));
            return;
        }
        
        // 等待令牌
        while (!tokenBucket.tryAcquire() && !leakyBucket.tryAcquire()) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                request.future.completeExceptionally(e);
                return;
            }
        }
        
        // 执行请求
        try {
            T result = request.supplier.get();
            request.future.complete(result);
            acceptedRequests.increment();
        } catch (Exception e) {
            request.future.completeExceptionally(e);
        }
    }
    
    private double getAcceptanceRate() {
        long total = totalRequests.sum();
        return total > 0 ? (double) acceptedRequests.sum() / total : 1.0;
    }
    
    // ========== 内部类 ==========
    
    /**
     * 漏桶限流器
     */
    private static class LeakyBucket {
        private volatile int rate; // 每秒处理数
        private final AtomicLong lastLeakTime = new AtomicLong(System.nanoTime());
        private final AtomicLong water = new AtomicLong(0);
        private final LongAdder processedCount = new LongAdder();
        
        LeakyBucket(int rate) {
            this.rate = rate;
        }
        
        void setRate(int rate) {
            this.rate = rate;
        }
        
        boolean tryAcquire() {
            leak();
            long current = water.get();
            if (current < rate) {
                if (water.compareAndSet(current, current + 1)) {
                    processedCount.increment();
                    return true;
                }
            }
            return false;
        }
        
        private void leak() {
            long now = System.nanoTime();
            long last = lastLeakTime.get();
            long elapsed = now - last;
            
            if (elapsed > 1_000_000) { // 1ms
                long leaked = (long) (elapsed / 1_000_000_000.0 * rate);
                if (leaked > 0 && lastLeakTime.compareAndSet(last, now)) {
                    water.updateAndGet(w -> Math.max(0, w - leaked));
                }
            }
        }
        
        long getProcessedCount() {
            return processedCount.sum();
        }
    }
    
    /**
     * 令牌桶限流器
     */
    private static class TokenBucket {
        private volatile int capacity;
        private volatile int refillRate;
        private final AtomicLong tokens;
        private final AtomicLong lastRefillTime;
        
        TokenBucket(int capacity, int refillRate) {
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.tokens = new AtomicLong(capacity);
            this.lastRefillTime = new AtomicLong(System.nanoTime());
        }
        
        void setCapacity(int capacity) {
            this.capacity = capacity;
        }
        
        void setRefillRate(int refillRate) {
            this.refillRate = refillRate;
        }
        
        boolean tryAcquire() {
            refill();
            long current = tokens.get();
            if (current > 0) {
                return tokens.compareAndSet(current, current - 1);
            }
            return false;
        }
        
        private void refill() {
            long now = System.nanoTime();
            long last = lastRefillTime.get();
            long elapsed = now - last;
            
            if (elapsed > 1_000_000) { // 1ms
                long newTokens = (long) (elapsed / 1_000_000_000.0 * refillRate);
                if (newTokens > 0 && lastRefillTime.compareAndSet(last, now)) {
                    tokens.updateAndGet(t -> Math.min(capacity, t + newTokens));
                }
            }
        }
        
        long getAvailableTokens() {
            refill();
            return tokens.get();
        }
    }
    
    /**
     * 滑动窗口限流器
     */
    private static class SlidingWindowLimiter {
        private volatile int windowSizeMs;
        private volatile int limit;
        private final AtomicLong[] buckets;
        private final int bucketCount = 10;
        private final AtomicLong currentBucket = new AtomicLong(0);
        
        SlidingWindowLimiter(int windowSizeMs, int limit) {
            this.windowSizeMs = windowSizeMs;
            this.limit = limit;
            this.buckets = new AtomicLong[bucketCount];
            for (int i = 0; i < bucketCount; i++) {
                buckets[i] = new AtomicLong(0);
            }
        }
        
        void setWindowSize(int windowSizeMs) {
            this.windowSizeMs = windowSizeMs;
        }
        
        void setLimit(int limit) {
            this.limit = limit;
        }
        
        boolean tryAcquire() {
            rotateBuckets();
            long total = getTotal();
            if (total < limit) {
                int idx = (int) (currentBucket.get() % bucketCount);
                buckets[idx].incrementAndGet();
                return true;
            }
            return false;
        }
        
        private void rotateBuckets() {
            long now = System.currentTimeMillis();
            long expectedBucket = now / (windowSizeMs / bucketCount);
            long current = currentBucket.get();
            
            if (expectedBucket > current) {
                if (currentBucket.compareAndSet(current, expectedBucket)) {
                    // 清理过期桶
                    for (long i = current + 1; i <= expectedBucket; i++) {
                        int idx = (int) (i % bucketCount);
                        buckets[idx].set(0);
                    }
                }
            }
        }
        
        private long getTotal() {
            long total = 0;
            for (AtomicLong bucket : buckets) {
                total += bucket.get();
            }
            return total;
        }
        
        long getCurrentQps() {
            return getTotal() * 1000 / windowSizeMs;
        }
    }
    
    /**
     * 弹性队列
     */
    private static class ElasticQueue<T> {
        private final LinkedBlockingQueue<T> queue;
        private volatile int maxSize;
        
        ElasticQueue(int maxSize) {
            this.maxSize = maxSize;
            this.queue = new LinkedBlockingQueue<>(maxSize);
        }
        
        void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }
        
        boolean offer(T item) {
            return queue.offer(item);
        }
        
        T poll(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }
        
        int size() {
            return queue.size();
        }
        
        boolean isFull() {
            return queue.size() >= maxSize;
        }
    }
    
    /**
     * 流量预测器
     */
    private static class TrafficPredictor {
        private final long[] hourlyTraffic = new long[24];
        private final LongAdder[] hourlyCounters = new LongAdder[24];
        
        TrafficPredictor() {
            for (int i = 0; i < 24; i++) {
                hourlyCounters[i] = new LongAdder();
            }
        }
        
        void recordTraffic(long qps) {
            int hour = LocalTime.now().getHour();
            hourlyCounters[hour].add(qps);
            hourlyTraffic[hour] = (hourlyTraffic[hour] + qps) / 2; // 指数移动平均
        }
        
        TrafficPrediction predict() {
            int currentHour = LocalTime.now().getHour();
            int nextHour = (currentHour + 1) % 24;
            
            long currentTraffic = hourlyTraffic[currentHour];
            long nextHourPrediction = hourlyTraffic[nextHour];
            
            // 找到今日高峰
            int peakHour = 0;
            long peakTraffic = 0;
            for (int i = 0; i < 24; i++) {
                if (hourlyTraffic[i] > peakTraffic) {
                    peakTraffic = hourlyTraffic[i];
                    peakHour = i;
                }
            }
            
            return new TrafficPrediction(
                currentTraffic,
                nextHourPrediction,
                peakHour,
                peakTraffic,
                nextHourPrediction > currentTraffic * 1.5
            );
        }
    }
    
    /**
     * 待处理请求
     */
    private record PendingRequest<T>(
        String key,
        Supplier<T> supplier,
        CompletableFuture<T> future,
        long submitTime
    ) {}
    
    /**
     * 流量状态
     */
    public record TrafficStatus(
        long totalRequests,
        long acceptedRequests,
        long rejectedRequests,
        long queuedRequests,
        long timeoutRequests,
        int currentQueueSize,
        long availableTokens,
        long currentQps,
        long processedByLeakyBucket,
        double acceptanceRate
    ) {}
    
    /**
     * 流量预测
     */
    public record TrafficPrediction(
        long currentHourTraffic,
        long nextHourPrediction,
        int peakHour,
        long peakTraffic,
        boolean isPeakApproaching
    ) {}
    
    /**
     * 流量过载异常
     */
    public static class TrafficOverloadException extends RuntimeException {
        public TrafficOverloadException(String message) {
            super(message);
        }
    }
}
