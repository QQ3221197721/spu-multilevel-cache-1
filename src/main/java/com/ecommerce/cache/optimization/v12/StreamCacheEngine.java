package com.ecommerce.cache.optimization.v12;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 流式计算缓存引擎
 * 
 * 核心特性:
 * 1. 滚动窗口: 固定时间窗口聚合
 * 2. 滑动窗口: 滑动时间窗口
 * 3. 会话窗口: 基于活动的窗口
 * 4. 实时聚合: 流式统计计算
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamCacheEngine {
    
    private final MeterRegistry meterRegistry;
    private final OptimizationV12Properties properties;
    
    /** 流处理器 */
    private final ConcurrentMap<String, StreamProcessor<?>> processors = new ConcurrentHashMap<>();
    
    /** 窗口状态 */
    private final ConcurrentMap<String, WindowState> windowStates = new ConcurrentHashMap<>();
    
    /** 事件缓冲 */
    private final BlockingQueue<StreamEvent> eventBuffer = new LinkedBlockingQueue<>(100000);
    
    /** 调度器 */
    private ScheduledExecutorService scheduler;
    private ExecutorService workerPool;
    
    /** 统计 */
    private final AtomicLong eventCount = new AtomicLong(0);
    private final AtomicLong windowCount = new AtomicLong(0);
    private final AtomicLong aggregationCount = new AtomicLong(0);
    
    private Counter eventCounter;
    private Counter windowCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.getStreamCache().isEnabled()) {
            log.info("[流式缓存] 已禁用");
            return;
        }
        
        int parallelism = properties.getStreamCache().getParallelism();
        
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "stream-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        workerPool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "stream-worker");
            t.setDaemon(true);
            return t;
        });
        
        initMetrics();
        
        // 启动事件消费者
        for (int i = 0; i < parallelism; i++) {
            workerPool.submit(this::eventConsumer);
        }
        
        // 定期窗口触发
        int windowSec = properties.getStreamCache().getWindowSizeSec();
        scheduler.scheduleWithFixedDelay(this::triggerWindows, windowSec, windowSec, TimeUnit.SECONDS);
        
        log.info("[流式缓存] 初始化完成 - 窗口: {}s, 并行度: {}",
            windowSec, parallelism);
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (workerPool != null) workerPool.shutdownNow();
        log.info("[流式缓存] 已关闭 - 事件: {}, 窗口: {}", 
            eventCount.get(), windowCount.get());
    }
    
    private void initMetrics() {
        eventCounter = Counter.builder("cache.stream.events").register(meterRegistry);
        windowCounter = Counter.builder("cache.stream.windows").register(meterRegistry);
    }
    
    // ========== 流处理器注册 ==========
    
    /**
     * 注册流处理器
     */
    public <T> void registerProcessor(String streamId, StreamProcessor<T> processor) {
        processors.put(streamId, processor);
        log.info("[流式缓存] 注册处理器: {}", streamId);
    }
    
    /**
     * 注册聚合函数
     */
    public <T, R> void registerAggregation(String streamId, 
            Function<StreamEvent, T> extractor,
            BiFunction<R, T, R> aggregator,
            R initialValue) {
        processors.put(streamId, new AggregationProcessor<>(extractor, aggregator, initialValue));
    }
    
    // ========== 事件发送 ==========
    
    /**
     * 发送事件
     */
    public void emit(String streamId, Object payload) {
        StreamEvent event = new StreamEvent(
            streamId, payload, System.currentTimeMillis(), UUID.randomUUID().toString()
        );
        
        if (!eventBuffer.offer(event)) {
            log.warn("[流式缓存] 事件缓冲满，丢弃事件: {}", streamId);
            return;
        }
        
        eventCount.incrementAndGet();
        eventCounter.increment();
    }
    
    /**
     * 批量发送
     */
    public void emitBatch(String streamId, Collection<?> payloads) {
        for (Object payload : payloads) {
            emit(streamId, payload);
        }
    }
    
    // ========== 窗口操作 ==========
    
    /**
     * 创建滚动窗口
     */
    public void createTumblingWindow(String windowId, String streamId, int windowSizeSec) {
        WindowState state = new WindowState(
            windowId, streamId, WindowType.TUMBLING, windowSizeSec, System.currentTimeMillis()
        );
        windowStates.put(windowId, state);
    }
    
    /**
     * 创建滑动窗口
     */
    public void createSlidingWindow(String windowId, String streamId, int windowSizeSec, int slideSizeSec) {
        WindowState state = new WindowState(
            windowId, streamId, WindowType.SLIDING, windowSizeSec, System.currentTimeMillis()
        );
        state.setSlideSizeSec(slideSizeSec);
        windowStates.put(windowId, state);
    }
    
    /**
     * 获取窗口结果
     */
    public Optional<WindowResult> getWindowResult(String windowId) {
        WindowState state = windowStates.get(windowId);
        if (state == null) return Optional.empty();
        
        return Optional.of(new WindowResult(
            windowId,
            state.getStreamId(),
            state.getAggregatedValue(),
            state.getEventCount(),
            Instant.ofEpochMilli(state.getWindowStart()),
            Instant.now()
        ));
    }
    
    /**
     * 获取所有窗口
     */
    public List<WindowResult> getAllWindowResults() {
        return windowStates.values().stream()
            .map(state -> new WindowResult(
                state.getWindowId(),
                state.getStreamId(),
                state.getAggregatedValue(),
                state.getEventCount(),
                Instant.ofEpochMilli(state.getWindowStart()),
                Instant.now()
            ))
            .toList();
    }
    
    // ========== 内部方法 ==========
    
    private void eventConsumer() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                StreamEvent event = eventBuffer.poll(1, TimeUnit.SECONDS);
                if (event != null) {
                    processEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[流式缓存] 事件处理异常: {}", e.getMessage());
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private void processEvent(StreamEvent event) {
        // 执行处理器
        StreamProcessor<Object> processor = (StreamProcessor<Object>) processors.get(event.streamId);
        if (processor != null) {
            processor.process(event);
        }
        
        // 更新窗口状态
        for (WindowState state : windowStates.values()) {
            if (state.getStreamId().equals(event.streamId)) {
                state.addEvent(event);
            }
        }
    }
    
    private void triggerWindows() {
        long now = System.currentTimeMillis();
        int windowSizeSec = properties.getStreamCache().getWindowSizeSec();
        
        for (WindowState state : windowStates.values()) {
            long elapsed = now - state.getWindowStart();
            
            if (elapsed >= state.getWindowSizeSec() * 1000L) {
                // 触发窗口
                triggerWindow(state);
                
                // 重置窗口
                state.reset(now);
                windowCount.incrementAndGet();
                windowCounter.increment();
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private void triggerWindow(WindowState state) {
        StreamProcessor<Object> processor = (StreamProcessor<Object>) processors.get(state.getStreamId());
        if (processor != null) {
            Object result = processor.getResult();
            state.setAggregatedValue(result);
            processor.reset();
        }
        
        aggregationCount.incrementAndGet();
        log.debug("[流式缓存] 窗口触发: {} - 事件数: {}", state.getWindowId(), state.getEventCount());
    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("processorCount", processors.size());
        stats.put("windowCount", windowStates.size());
        stats.put("eventCount", eventCount.get());
        stats.put("windowTriggerCount", windowCount.get());
        stats.put("aggregationCount", aggregationCount.get());
        stats.put("bufferSize", eventBuffer.size());
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    public enum WindowType {
        TUMBLING, SLIDING, SESSION
    }
    
    @Data
    public static class StreamEvent {
        private final String streamId;
        private final Object payload;
        private final long timestamp;
        private final String eventId;
    }
    
    @Data
    public static class WindowResult {
        private final String windowId;
        private final String streamId;
        private final Object aggregatedValue;
        private final long eventCount;
        private final Instant windowStart;
        private final Instant windowEnd;
    }
    
    @Data
    private static class WindowState {
        private final String windowId;
        private final String streamId;
        private final WindowType type;
        private final int windowSizeSec;
        private long windowStart;
        private int slideSizeSec;
        private long eventCount;
        private Object aggregatedValue;
        private final List<StreamEvent> events = new ArrayList<>();
        
        void addEvent(StreamEvent event) {
            events.add(event);
            eventCount++;
        }
        
        void reset(long newStart) {
            windowStart = newStart;
            eventCount = 0;
            events.clear();
        }
    }
    
    public interface StreamProcessor<T> {
        void process(StreamEvent event);
        T getResult();
        void reset();
    }
    
    @Data
    private static class AggregationProcessor<T, R> implements StreamProcessor<R> {
        private final Function<StreamEvent, T> extractor;
        private final BiFunction<R, T, R> aggregator;
        private final R initialValue;
        private R currentValue;
        
        AggregationProcessor(Function<StreamEvent, T> extractor, BiFunction<R, T, R> aggregator, R initialValue) {
            this.extractor = extractor;
            this.aggregator = aggregator;
            this.initialValue = initialValue;
            this.currentValue = initialValue;
        }
        
        @Override
        public void process(StreamEvent event) {
            T extracted = extractor.apply(event);
            currentValue = aggregator.apply(currentValue, extracted);
        }
        
        @Override
        public R getResult() {
            return currentValue;
        }
        
        @Override
        public void reset() {
            currentValue = initialValue;
        }
    }
}
