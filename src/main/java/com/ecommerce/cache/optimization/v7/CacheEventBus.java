package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 缓存事件总线
 * 
 * 核心特性:
 * 1. 事件发布订阅: 解耦缓存操作通知
 * 2. 异步处理: 非阻塞事件分发
 * 3. 优先级队列: 重要事件优先处理
 * 4. 事件过滤: 按类型、Key模式过滤
 * 5. 持久化支持: 重要事件持久化
 * 6. 重试机制: 失败事件自动重试
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEventBus {
    
    private final MeterRegistry meterRegistry;
    
    // ========== 事件处理 ==========
    
    /** 事件队列 */
    private final PriorityBlockingQueue<CacheEvent> eventQueue = 
        new PriorityBlockingQueue<>(10000, Comparator.comparingInt(e -> -e.priority));
    
    /** 订阅者注册表 */
    private final ConcurrentMap<CacheEventType, List<EventSubscriber>> subscribers = new ConcurrentHashMap<>();
    
    /** 模式订阅者(支持通配符) */
    private final ConcurrentMap<String, List<EventSubscriber>> patternSubscribers = new ConcurrentHashMap<>();
    
    /** 事件历史(用于重放) */
    private final Queue<CacheEvent> eventHistory = new ConcurrentLinkedQueue<>();
    
    /** 失败事件重试队列 */
    private final Queue<RetryEvent> retryQueue = new ConcurrentLinkedQueue<>();
    
    /** 执行器 */
    private ExecutorService eventExecutor;
    private ScheduledExecutorService scheduler;
    
    /** 计数器 */
    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    
    /** 运行标志 */
    private volatile boolean running = false;
    
    // ========== 配置 ==========
    
    private static final int MAX_HISTORY_SIZE = 10000;
    private static final int MAX_RETRY_COUNT = 3;
    private static final int WORKER_COUNT = 4;
    
    // ========== 指标 ==========
    
    private Counter eventPublishCounter;
    private Counter eventProcessCounter;
    private Counter eventFailCounter;
    
    @PostConstruct
    public void init() {
        // 初始化执行器
        eventExecutor = Executors.newFixedThreadPool(WORKER_COUNT, r -> {
            Thread t = new Thread(r, "event-bus-worker");
            t.setDaemon(true);
            return t;
        });
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "event-bus-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化指标
        initMetrics();
        
        // 启动事件处理
        running = true;
        startEventProcessing();
        
        // 启动重试任务
        scheduler.scheduleWithFixedDelay(
            this::processRetryQueue,
            5,
            5,
            TimeUnit.SECONDS
        );
        
        log.info("[事件总线] 初始化完成 - 工作线程数: {}", WORKER_COUNT);
    }
    
    @PreDestroy
    public void shutdown() {
        running = false;
        
        if (eventExecutor != null) {
            eventExecutor.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        
        log.info("[事件总线] 已关闭 - 发布: {}, 处理: {}, 失败: {}",
            publishedCount.get(), processedCount.get(), failedCount.get());
    }
    
    private void initMetrics() {
        eventPublishCounter = Counter.builder("cache.event.publish")
            .description("事件发布次数")
            .register(meterRegistry);
        
        eventProcessCounter = Counter.builder("cache.event.process")
            .description("事件处理次数")
            .register(meterRegistry);
        
        eventFailCounter = Counter.builder("cache.event.fail")
            .description("事件失败次数")
            .register(meterRegistry);
    }
    
    private void startEventProcessing() {
        for (int i = 0; i < WORKER_COUNT; i++) {
            eventExecutor.submit(() -> {
                while (running) {
                    try {
                        CacheEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (event != null) {
                            processEvent(event);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("[事件总线] 处理异常", e);
                    }
                }
            });
        }
    }
    
    // ========== 核心API ==========
    
    /**
     * 发布事件
     */
    public void publish(CacheEventType type, String key, Object data) {
        publish(type, key, data, 50);
    }
    
    /**
     * 发布事件(带优先级)
     */
    public void publish(CacheEventType type, String key, Object data, int priority) {
        CacheEvent event = new CacheEvent(
            UUID.randomUUID().toString(),
            type,
            key,
            data,
            priority,
            System.currentTimeMillis()
        );
        
        if (eventQueue.offer(event)) {
            publishedCount.incrementAndGet();
            eventPublishCounter.increment();
            
            // 保存到历史
            saveToHistory(event);
        } else {
            log.warn("[事件总线] 队列已满，丢弃事件: {}", event.type);
        }
    }
    
    /**
     * 发布高优先级事件
     */
    public void publishHighPriority(CacheEventType type, String key, Object data) {
        publish(type, key, data, 100);
    }
    
    /**
     * 发布批量事件
     */
    public void publishBatch(CacheEventType type, Collection<String> keys, Object data) {
        for (String key : keys) {
            publish(type, key, data, 30);
        }
    }
    
    /**
     * 订阅事件类型
     */
    public String subscribe(CacheEventType type, Consumer<CacheEvent> handler) {
        return subscribe(type, handler, null);
    }
    
    /**
     * 订阅事件类型(带过滤器)
     */
    public String subscribe(CacheEventType type, Consumer<CacheEvent> handler, String keyPattern) {
        String subscriberId = UUID.randomUUID().toString();
        
        EventSubscriber subscriber = new EventSubscriber(subscriberId, handler, keyPattern);
        
        subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(subscriber);
        
        log.info("[事件总线] 新增订阅: {} -> {} (pattern: {})", 
            subscriberId, type, keyPattern);
        
        return subscriberId;
    }
    
    /**
     * 订阅所有事件(按Key模式)
     */
    public String subscribeByPattern(String keyPattern, Consumer<CacheEvent> handler) {
        String subscriberId = UUID.randomUUID().toString();
        
        EventSubscriber subscriber = new EventSubscriber(subscriberId, handler, keyPattern);
        patternSubscribers.computeIfAbsent(keyPattern, k -> new CopyOnWriteArrayList<>()).add(subscriber);
        
        return subscriberId;
    }
    
    /**
     * 取消订阅
     */
    public void unsubscribe(String subscriberId) {
        // 从类型订阅中移除
        for (List<EventSubscriber> subs : subscribers.values()) {
            subs.removeIf(s -> s.id.equals(subscriberId));
        }
        
        // 从模式订阅中移除
        for (List<EventSubscriber> subs : patternSubscribers.values()) {
            subs.removeIf(s -> s.id.equals(subscriberId));
        }
        
        log.info("[事件总线] 取消订阅: {}", subscriberId);
    }
    
    /**
     * 重放历史事件
     */
    public void replayHistory(CacheEventType type, long sinceTimestamp) {
        eventHistory.stream()
            .filter(e -> e.type == type && e.timestamp >= sinceTimestamp)
            .forEach(e -> eventQueue.offer(e));
        
        log.info("[事件总线] 重放历史事件: type={}, since={}", type, sinceTimestamp);
    }
    
    // ========== 内部方法 ==========
    
    private void processEvent(CacheEvent event) {
        try {
            // 获取类型订阅者
            List<EventSubscriber> typeSubs = subscribers.get(event.type);
            if (typeSubs != null) {
                for (EventSubscriber sub : typeSubs) {
                    if (matchesPattern(event.key, sub.keyPattern)) {
                        try {
                            sub.handler.accept(event);
                        } catch (Exception e) {
                            handleSubscriberError(event, sub, e);
                        }
                    }
                }
            }
            
            // 获取模式订阅者
            for (var entry : patternSubscribers.entrySet()) {
                if (matchesPattern(event.key, entry.getKey())) {
                    for (EventSubscriber sub : entry.getValue()) {
                        try {
                            sub.handler.accept(event);
                        } catch (Exception e) {
                            handleSubscriberError(event, sub, e);
                        }
                    }
                }
            }
            
            processedCount.incrementAndGet();
            eventProcessCounter.increment();
            
        } catch (Exception e) {
            failedCount.incrementAndGet();
            eventFailCounter.increment();
            log.error("[事件总线] 处理事件失败: {}", event.type, e);
        }
    }
    
    private boolean matchesPattern(String key, String pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.equals("*")) {
            return true;
        }
        
        // 简单通配符匹配
        if (pattern.endsWith("*")) {
            return key.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        if (pattern.startsWith("*")) {
            return key.endsWith(pattern.substring(1));
        }
        
        return key.equals(pattern);
    }
    
    private void handleSubscriberError(CacheEvent event, EventSubscriber subscriber, Exception e) {
        log.warn("[事件总线] 订阅者处理失败: {} -> {}", subscriber.id, e.getMessage());
        
        // 加入重试队列
        retryQueue.offer(new RetryEvent(event, subscriber, 1));
    }
    
    private void processRetryQueue() {
        int processed = 0;
        RetryEvent retry;
        
        while ((retry = retryQueue.poll()) != null && processed < 100) {
            if (retry.retryCount <= MAX_RETRY_COUNT) {
                try {
                    retry.subscriber.handler.accept(retry.event);
                    processed++;
                } catch (Exception e) {
                    if (retry.retryCount < MAX_RETRY_COUNT) {
                        retryQueue.offer(new RetryEvent(
                            retry.event, 
                            retry.subscriber, 
                            retry.retryCount + 1
                        ));
                    } else {
                        log.error("[事件总线] 重试失败，放弃: {}", retry.event.type);
                        failedCount.incrementAndGet();
                    }
                }
            }
        }
    }
    
    private void saveToHistory(CacheEvent event) {
        eventHistory.offer(event);
        
        // 限制历史大小
        while (eventHistory.size() > MAX_HISTORY_SIZE) {
            eventHistory.poll();
        }
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("queueSize", eventQueue.size());
        stats.put("historySize", eventHistory.size());
        stats.put("retryQueueSize", retryQueue.size());
        stats.put("publishedCount", publishedCount.get());
        stats.put("processedCount", processedCount.get());
        stats.put("failedCount", failedCount.get());
        stats.put("successRate", calculateSuccessRate());
        
        // 订阅统计
        Map<String, Integer> subStats = new LinkedHashMap<>();
        for (var entry : subscribers.entrySet()) {
            subStats.put(entry.getKey().name(), entry.getValue().size());
        }
        stats.put("subscribersByType", subStats);
        stats.put("patternSubscribers", patternSubscribers.size());
        
        return stats;
    }
    
    private String calculateSuccessRate() {
        long total = processedCount.get() + failedCount.get();
        if (total == 0) return "N/A";
        return String.format("%.2f%%", (double) processedCount.get() / total * 100);
    }
    
    // ========== 内部类 ==========
    
    /**
     * 缓存事件类型
     */
    public enum CacheEventType {
        PUT,           // 写入
        GET,           // 读取
        DELETE,        // 删除
        EXPIRE,        // 过期
        EVICT,         // 淘汰
        UPDATE,        // 更新
        INVALIDATE,    // 失效
        REFRESH,       // 刷新
        HOT_KEY,       // 热点Key
        COLD_KEY,      // 冷Key
        ERROR,         // 错误
        METRIC         // 指标
    }
    
    /**
     * 缓存事件
     */
    @Data
    public static class CacheEvent {
        private final String id;
        private final CacheEventType type;
        private final String key;
        private final Object data;
        private final int priority;
        private final long timestamp;
    }
    
    /**
     * 事件订阅者
     */
    @Data
    private static class EventSubscriber {
        private final String id;
        private final Consumer<CacheEvent> handler;
        private final String keyPattern;
    }
    
    /**
     * 重试事件
     */
    private record RetryEvent(CacheEvent event, EventSubscriber subscriber, int retryCount) {}
}
