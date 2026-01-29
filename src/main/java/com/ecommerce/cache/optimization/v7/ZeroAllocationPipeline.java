package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 零分配数据管道
 * 
 * 核心特性:
 * 1. 无锁环形缓冲区: 高性能生产者-消费者模型
 * 2. 对象池: 复用对象避免GC
 * 3. 直接内存管理: 堆外内存减少GC压力
 * 4. 字符串驻留: 常用字符串复用
 * 5. 批量处理: 减少方法调用开销
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZeroAllocationPipeline {
    
    private final OptimizationV7Properties properties;
    private final MeterRegistry meterRegistry;
    
    // ========== 环形缓冲区 ==========
    
    private volatile Object[] ringBuffer;
    private final AtomicLong producerSequence = new AtomicLong(-1);
    private final AtomicLong consumerSequence = new AtomicLong(-1);
    private int bufferMask;
    
    // ========== 对象池 ==========
    
    private final ConcurrentHashMap<Class<?>, ObjectPool<?>> objectPools = new ConcurrentHashMap<>();
    
    // ========== 直接内存缓冲区池 ==========
    
    private final Queue<ByteBuffer> directBufferPool = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeDirectBuffers = new AtomicInteger(0);
    
    // ========== 字符串驻留池 ==========
    
    private final ConcurrentHashMap<String, SoftReference<String>> stringPool = new ConcurrentHashMap<>();
    private final AtomicLong stringPoolHits = new AtomicLong(0);
    private final AtomicLong stringPoolMisses = new AtomicLong(0);
    
    // ========== 批量聚合器 ==========
    
    private final ConcurrentHashMap<String, BatchAggregator<?>> batchAggregators = new ConcurrentHashMap<>();
    
    // ========== 预分配数组池 ==========
    
    private final Queue<byte[]> byteArrayPool = new ConcurrentLinkedQueue<>();
    private final Queue<Object[]> objectArrayPool = new ConcurrentLinkedQueue<>();
    
    // ========== 指标 ==========
    
    private Counter allocationAvoidedCounter;
    private Counter objectPoolHitCounter;
    private Counter objectPoolMissCounter;
    
    @PostConstruct
    public void init() {
        if (!properties.isZeroAllocationEnabled()) {
            log.info("[零分配管道] 未启用");
            return;
        }
        
        var config = properties.getZeroAllocation();
        
        // 初始化环形缓冲区(大小必须是2的幂)
        int size = nextPowerOfTwo(config.getRingBufferSize());
        ringBuffer = new Object[size];
        bufferMask = size - 1;
        
        // 预热直接内存缓冲区池
        int bufferSizeBytes = config.getDirectBufferSizeMb() * 1024 * 1024 / 64;
        for (int i = 0; i < 64; i++) {
            directBufferPool.offer(ByteBuffer.allocateDirect(bufferSizeBytes));
        }
        
        // 预热字节数组池
        for (int i = 0; i < config.getPreallocatedArraySize(); i++) {
            byteArrayPool.offer(new byte[4096]);
        }
        
        // 预热对象数组池
        for (int i = 0; i < 256; i++) {
            objectArrayPool.offer(new Object[config.getBatchSize()]);
        }
        
        // 初始化指标
        initMetrics();
        
        log.info("[零分配管道] 初始化完成 - 环形缓冲区大小: {}, 直接内存: {}MB",
            size, config.getDirectBufferSizeMb());
    }
    
    @PreDestroy
    public void shutdown() {
        // 清理直接内存
        ByteBuffer buffer;
        while ((buffer = directBufferPool.poll()) != null) {
            if (buffer.isDirect()) {
                // 帮助GC回收直接内存
                ((sun.nio.ch.DirectBuffer) buffer).cleaner().clean();
            }
        }
        
        objectPools.clear();
        stringPool.clear();
        batchAggregators.values().forEach(BatchAggregator::flush);
        
        log.info("[零分配管道] 已关闭");
    }
    
    private void initMetrics() {
        allocationAvoidedCounter = Counter.builder("cache.zeroalloc.avoided")
            .description("避免的内存分配次数")
            .register(meterRegistry);
        
        objectPoolHitCounter = Counter.builder("cache.zeroalloc.pool.hit")
            .description("对象池命中次数")
            .register(meterRegistry);
        
        objectPoolMissCounter = Counter.builder("cache.zeroalloc.pool.miss")
            .description("对象池未命中次数")
            .register(meterRegistry);
        
        Gauge.builder("cache.zeroalloc.stringpool.size", stringPool, Map::size)
            .description("字符串池大小")
            .register(meterRegistry);
        
        Gauge.builder("cache.zeroalloc.directbuffer.active", activeDirectBuffers, AtomicInteger::get)
            .description("活跃直接缓冲区数")
            .register(meterRegistry);
    }
    
    private int nextPowerOfTwo(int value) {
        int highestOneBit = Integer.highestOneBit(value);
        if (value == highestOneBit) {
            return value;
        }
        return highestOneBit << 1;
    }
    
    // ========== 环形缓冲区操作 ==========
    
    /**
     * 发布数据到环形缓冲区(无锁)
     */
    public boolean publish(Object item) {
        if (!properties.isZeroAllocationEnabled()) {
            return false;
        }
        
        long current;
        long next;
        
        do {
            current = producerSequence.get();
            next = current + 1;
            
            // 检查是否会覆盖未消费的数据
            if (next - consumerSequence.get() > bufferMask) {
                return false; // 缓冲区满
            }
        } while (!producerSequence.compareAndSet(current, next));
        
        ringBuffer[(int) (next & bufferMask)] = item;
        allocationAvoidedCounter.increment();
        
        return true;
    }
    
    /**
     * 批量发布(更高效)
     */
    public int publishBatch(Object[] items, int count) {
        if (!properties.isZeroAllocationEnabled()) {
            return 0;
        }
        
        int published = 0;
        for (int i = 0; i < count; i++) {
            if (publish(items[i])) {
                published++;
            } else {
                break;
            }
        }
        return published;
    }
    
    /**
     * 消费数据(无锁)
     */
    public Object consume() {
        if (!properties.isZeroAllocationEnabled()) {
            return null;
        }
        
        long current;
        long next;
        
        do {
            current = consumerSequence.get();
            next = current + 1;
            
            if (next > producerSequence.get()) {
                return null; // 无数据可消费
            }
        } while (!consumerSequence.compareAndSet(current, next));
        
        int index = (int) (next & bufferMask);
        Object item = ringBuffer[index];
        ringBuffer[index] = null; // 帮助GC
        
        return item;
    }
    
    /**
     * 批量消费
     */
    public int consumeBatch(Object[] buffer, int maxCount) {
        if (!properties.isZeroAllocationEnabled()) {
            return 0;
        }
        
        int consumed = 0;
        for (int i = 0; i < maxCount; i++) {
            Object item = consume();
            if (item == null) {
                break;
            }
            buffer[i] = item;
            consumed++;
        }
        return consumed;
    }
    
    // ========== 对象池操作 ==========
    
    /**
     * 注册对象池
     */
    @SuppressWarnings("unchecked")
    public <T> void registerPool(Class<T> type, Supplier<T> factory, Consumer<T> resetter) {
        var config = properties.getZeroAllocation();
        objectPools.put(type, new ObjectPool<>(
            factory, 
            resetter,
            config.getObjectPoolInitialSize(),
            config.getObjectPoolMaxSize()
        ));
    }
    
    /**
     * 从对象池获取对象
     */
    @SuppressWarnings("unchecked")
    public <T> T acquire(Class<T> type) {
        if (!properties.isZeroAllocationEnabled()) {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create instance", e);
            }
        }
        
        ObjectPool<T> pool = (ObjectPool<T>) objectPools.get(type);
        if (pool == null) {
            objectPoolMissCounter.increment();
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create instance", e);
            }
        }
        
        T obj = pool.acquire();
        if (obj != null) {
            objectPoolHitCounter.increment();
            allocationAvoidedCounter.increment();
        } else {
            objectPoolMissCounter.increment();
        }
        
        return obj;
    }
    
    /**
     * 归还对象到池
     */
    @SuppressWarnings("unchecked")
    public <T> void release(Class<T> type, T object) {
        if (!properties.isZeroAllocationEnabled() || object == null) {
            return;
        }
        
        ObjectPool<T> pool = (ObjectPool<T>) objectPools.get(type);
        if (pool != null) {
            pool.release(object);
        }
    }
    
    // ========== 直接内存操作 ==========
    
    /**
     * 获取直接内存缓冲区
     */
    public ByteBuffer acquireDirectBuffer() {
        if (!properties.isZeroAllocationEnabled() || 
            !properties.getZeroAllocation().isOffHeapEnabled()) {
            return ByteBuffer.allocate(properties.getZeroAllocation().getDirectBufferSizeMb() * 1024);
        }
        
        ByteBuffer buffer = directBufferPool.poll();
        if (buffer != null) {
            buffer.clear();
            activeDirectBuffers.incrementAndGet();
            allocationAvoidedCounter.increment();
            return buffer;
        }
        
        // 池中没有可用缓冲区，创建新的
        int size = properties.getZeroAllocation().getDirectBufferSizeMb() * 1024 * 1024 / 64;
        activeDirectBuffers.incrementAndGet();
        return ByteBuffer.allocateDirect(size);
    }
    
    /**
     * 归还直接内存缓冲区
     */
    public void releaseDirectBuffer(ByteBuffer buffer) {
        if (buffer == null) return;
        
        activeDirectBuffers.decrementAndGet();
        
        if (buffer.isDirect() && directBufferPool.size() < 128) {
            buffer.clear();
            directBufferPool.offer(buffer);
        }
    }
    
    // ========== 字符串驻留 ==========
    
    /**
     * 驻留字符串(复用相同字符串)
     */
    public String intern(String str) {
        if (!properties.isZeroAllocationEnabled() || str == null) {
            return str;
        }
        
        // 短字符串使用JVM内置intern
        if (str.length() <= 16) {
            return str.intern();
        }
        
        // 长字符串使用自定义池
        SoftReference<String> ref = stringPool.get(str);
        if (ref != null) {
            String cached = ref.get();
            if (cached != null) {
                stringPoolHits.incrementAndGet();
                allocationAvoidedCounter.increment();
                return cached;
            }
        }
        
        stringPoolMisses.incrementAndGet();
        
        // 限制池大小
        if (stringPool.size() < properties.getZeroAllocation().getStringPoolSize()) {
            stringPool.put(str, new SoftReference<>(str));
        }
        
        return str;
    }
    
    /**
     * 零拷贝字符串转换
     */
    public String fromBytes(byte[] bytes, int offset, int length) {
        if (!properties.isZeroAllocationEnabled()) {
            return new String(bytes, offset, length, StandardCharsets.UTF_8);
        }
        
        String str = new String(bytes, offset, length, StandardCharsets.UTF_8);
        return intern(str);
    }
    
    // ========== 数组池操作 ==========
    
    /**
     * 获取字节数组
     */
    public byte[] acquireByteArray(int minSize) {
        if (!properties.isZeroAllocationEnabled()) {
            return new byte[minSize];
        }
        
        byte[] arr = byteArrayPool.poll();
        if (arr != null && arr.length >= minSize) {
            Arrays.fill(arr, (byte) 0);
            allocationAvoidedCounter.increment();
            return arr;
        }
        
        return new byte[Math.max(minSize, 4096)];
    }
    
    /**
     * 归还字节数组
     */
    public void releaseByteArray(byte[] arr) {
        if (!properties.isZeroAllocationEnabled() || arr == null) return;
        
        if (arr.length <= 65536 && byteArrayPool.size() < 1024) {
            byteArrayPool.offer(arr);
        }
    }
    
    /**
     * 获取对象数组
     */
    public Object[] acquireObjectArray() {
        if (!properties.isZeroAllocationEnabled()) {
            return new Object[properties.getZeroAllocation().getBatchSize()];
        }
        
        Object[] arr = objectArrayPool.poll();
        if (arr != null) {
            Arrays.fill(arr, null);
            allocationAvoidedCounter.increment();
            return arr;
        }
        
        return new Object[properties.getZeroAllocation().getBatchSize()];
    }
    
    /**
     * 归还对象数组
     */
    public void releaseObjectArray(Object[] arr) {
        if (!properties.isZeroAllocationEnabled() || arr == null) return;
        
        if (objectArrayPool.size() < 512) {
            Arrays.fill(arr, null);
            objectArrayPool.offer(arr);
        }
    }
    
    // ========== 批量聚合器 ==========
    
    /**
     * 获取或创建批量聚合器
     */
    @SuppressWarnings("unchecked")
    public <T> BatchAggregator<T> getAggregator(String name, Consumer<T[]> flushHandler, Class<T> type) {
        return (BatchAggregator<T>) batchAggregators.computeIfAbsent(name, 
            k -> new BatchAggregator<>(
                properties.getZeroAllocation().getBatchSize(),
                flushHandler,
                type
            )
        );
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("enabled", properties.isZeroAllocationEnabled());
        stats.put("ringBufferSize", ringBuffer != null ? ringBuffer.length : 0);
        stats.put("producerSequence", producerSequence.get());
        stats.put("consumerSequence", consumerSequence.get());
        stats.put("pendingItems", producerSequence.get() - consumerSequence.get());
        stats.put("objectPoolCount", objectPools.size());
        stats.put("directBufferPoolSize", directBufferPool.size());
        stats.put("activeDirectBuffers", activeDirectBuffers.get());
        stats.put("stringPoolSize", stringPool.size());
        stats.put("stringPoolHitRate", calculateHitRate(stringPoolHits.get(), stringPoolMisses.get()));
        stats.put("byteArrayPoolSize", byteArrayPool.size());
        stats.put("objectArrayPoolSize", objectArrayPool.size());
        
        return stats;
    }
    
    private String calculateHitRate(long hits, long misses) {
        long total = hits + misses;
        if (total == 0) return "0.00%";
        return String.format("%.2f%%", (double) hits / total * 100);
    }
    
    // ========== 内部类 ==========
    
    /**
     * 通用对象池
     */
    private static class ObjectPool<T> {
        private final Queue<T> pool = new ConcurrentLinkedQueue<>();
        private final Supplier<T> factory;
        private final Consumer<T> resetter;
        private final int maxSize;
        private final AtomicInteger currentSize = new AtomicInteger(0);
        
        ObjectPool(Supplier<T> factory, Consumer<T> resetter, int initialSize, int maxSize) {
            this.factory = factory;
            this.resetter = resetter;
            this.maxSize = maxSize;
            
            // 预热
            for (int i = 0; i < initialSize; i++) {
                pool.offer(factory.get());
                currentSize.incrementAndGet();
            }
        }
        
        T acquire() {
            T obj = pool.poll();
            if (obj != null) {
                return obj;
            }
            
            if (currentSize.get() < maxSize) {
                currentSize.incrementAndGet();
                return factory.get();
            }
            
            return null;
        }
        
        void release(T obj) {
            if (obj == null) return;
            
            if (resetter != null) {
                resetter.accept(obj);
            }
            
            if (pool.size() < maxSize) {
                pool.offer(obj);
            } else {
                currentSize.decrementAndGet();
            }
        }
    }
    
    /**
     * 批量聚合器
     */
    @Data
    public static class BatchAggregator<T> {
        private final Object[] buffer;
        private final AtomicInteger index = new AtomicInteger(0);
        private final Consumer<T[]> flushHandler;
        private final Class<T> type;
        private final int batchSize;
        
        @SuppressWarnings("unchecked")
        BatchAggregator(int batchSize, Consumer<T[]> flushHandler, Class<T> type) {
            this.batchSize = batchSize;
            this.buffer = new Object[batchSize];
            this.flushHandler = flushHandler;
            this.type = type;
        }
        
        public void add(T item) {
            int idx = index.getAndIncrement();
            if (idx < batchSize) {
                buffer[idx] = item;
                
                if (idx == batchSize - 1) {
                    flush();
                }
            } else {
                // 等待flush完成后重试
                while (index.get() >= batchSize) {
                    Thread.yield();
                }
                add(item);
            }
        }
        
        @SuppressWarnings("unchecked")
        public void flush() {
            int count = Math.min(index.get(), batchSize);
            if (count == 0) return;
            
            T[] items = (T[]) java.lang.reflect.Array.newInstance(type, count);
            for (int i = 0; i < count; i++) {
                items[i] = (T) buffer[i];
                buffer[i] = null;
            }
            
            index.set(0);
            
            if (flushHandler != null) {
                flushHandler.accept(items);
            }
        }
    }
}
