package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 零GC环形缓冲区 - 高性能无垃圾回收的数据结构
 * 
 * 核心特性:
 * 1. 预分配固定内存 - 避免运行时内存分配
 * 2. 无锁并发设计 - 使用CAS操作实现线程安全
 * 3. 缓存行填充 - 避免伪共享
 * 4. 批量操作优化 - 减少内存屏障开销
 * 5. 内存复用 - 对象槽位循环使用
 * 
 * 适用场景: 高频访问日志、实时指标收集、事件流处理
 * 目标: 百万级TPS, 零GC停顿
 */
@Component
public class ZeroGCRingBuffer {
    
    private static final Logger log = LoggerFactory.getLogger(ZeroGCRingBuffer.class);
    
    // ========== 缓存行填充 ==========
    // 防止伪共享，缓存行通常是64字节
    
    private static final int CACHE_LINE_SIZE = 64;
    private static final int BUFFER_PAD = CACHE_LINE_SIZE / 8; // long是8字节
    
    // ========== 核心数据结构 ==========
    
    // 默认缓冲区大小 (必须是2的幂)
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024; // 1M条目
    
    // 缓冲区掩码（用于快速取模）
    private final int bufferMask;
    
    // 环形缓冲区
    private final Entry[] buffer;
    
    // 生产者序列（带缓存行填充）
    private final PaddedAtomicLong producerSequence = new PaddedAtomicLong(-1);
    
    // 消费者序列（带缓存行填充）
    private final PaddedAtomicLong consumerSequence = new PaddedAtomicLong(-1);
    
    // 最高已发布序列（用于多生产者场景）
    private final AtomicLong highestPublishedSequence = new AtomicLong(-1);
    
    // 统计
    private final AtomicLong totalPublished = new AtomicLong(0);
    private final AtomicLong totalConsumed = new AtomicLong(0);
    private final AtomicLong overflowCount = new AtomicLong(0);
    
    // VarHandle用于volatile语义
    private static final VarHandle ENTRY_VALUE_HANDLE;
    
    static {
        try {
            ENTRY_VALUE_HANDLE = MethodHandles.lookup()
                .findVarHandle(Entry.class, "value", Object.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    
    private final MeterRegistry meterRegistry;
    
    public ZeroGCRingBuffer(MeterRegistry meterRegistry) {
        this(DEFAULT_BUFFER_SIZE, meterRegistry);
    }
    
    public ZeroGCRingBuffer(int bufferSize, MeterRegistry meterRegistry) {
        // 确保是2的幂
        int actualSize = nextPowerOfTwo(bufferSize);
        this.bufferMask = actualSize - 1;
        this.buffer = new Entry[actualSize];
        this.meterRegistry = meterRegistry;
        
        // 预分配所有条目
        for (int i = 0; i < actualSize; i++) {
            buffer[i] = new Entry();
        }
        
        log.info("ZeroGCRingBuffer initialized: size={}, mask=0x{}", 
            actualSize, Integer.toHexString(bufferMask));
    }
    
    @PostConstruct
    public void registerMetrics() {
        Gauge.builder("ringbuffer.size", () -> bufferMask + 1)
            .tag("type", "capacity")
            .register(meterRegistry);
        Gauge.builder("ringbuffer.published", totalPublished, AtomicLong::get)
            .register(meterRegistry);
        Gauge.builder("ringbuffer.consumed", totalConsumed, AtomicLong::get)
            .register(meterRegistry);
        Gauge.builder("ringbuffer.overflow", overflowCount, AtomicLong::get)
            .register(meterRegistry);
        Gauge.builder("ringbuffer.pending", this, ZeroGCRingBuffer::getPendingCount)
            .register(meterRegistry);
    }
    
    /**
     * 发布单个事件 - 无锁实现
     */
    public boolean publish(Object event) {
        long sequence;
        Entry entry;
        
        // CAS获取序列号
        do {
            sequence = producerSequence.get() + 1;
            
            // 检查是否会覆盖未消费的数据
            long consumerPos = consumerSequence.get();
            if (sequence - consumerPos > bufferMask) {
                overflowCount.incrementAndGet();
                return false; // 缓冲区满
            }
        } while (!producerSequence.compareAndSet(sequence - 1, sequence));
        
        // 写入数据
        int index = (int) (sequence & bufferMask);
        entry = buffer[index];
        
        // 使用volatile写入
        ENTRY_VALUE_HANDLE.setRelease(entry, event);
        entry.sequence = sequence;
        
        // 更新最高已发布序列
        updateHighestPublished(sequence);
        
        totalPublished.incrementAndGet();
        return true;
    }
    
    /**
     * 批量发布事件
     */
    public int publishBatch(Object[] events, int offset, int length) {
        int published = 0;
        for (int i = 0; i < length; i++) {
            if (publish(events[offset + i])) {
                published++;
            } else {
                break;
            }
        }
        return published;
    }
    
    /**
     * 消费单个事件
     */
    @SuppressWarnings("unchecked")
    public <T> T consume() {
        long sequence = consumerSequence.get() + 1;
        
        // 检查是否有可消费的数据
        if (sequence > highestPublishedSequence.get()) {
            return null;
        }
        
        int index = (int) (sequence & bufferMask);
        Entry entry = buffer[index];
        
        // 等待数据可见
        while (entry.sequence != sequence) {
            Thread.onSpinWait();
        }
        
        // 读取数据
        Object value = ENTRY_VALUE_HANDLE.getAcquire(entry);
        
        // 更新消费者序列
        consumerSequence.set(sequence);
        
        // 清理条目（复用）
        ENTRY_VALUE_HANDLE.setRelease(entry, null);
        
        totalConsumed.incrementAndGet();
        return (T) value;
    }
    
    /**
     * 批量消费事件
     */
    public int consumeBatch(Object[] output, int maxCount) {
        int consumed = 0;
        for (int i = 0; i < maxCount; i++) {
            Object event = consume();
            if (event == null) break;
            output[i] = event;
            consumed++;
        }
        return consumed;
    }
    
    /**
     * 带处理器的批量消费
     */
    public <T> int consumeBatch(Consumer<T> processor, int maxCount) {
        int consumed = 0;
        for (int i = 0; i < maxCount; i++) {
            T event = consume();
            if (event == null) break;
            processor.accept(event);
            consumed++;
        }
        return consumed;
    }
    
    /**
     * 查看但不消费
     */
    @SuppressWarnings("unchecked")
    public <T> T peek() {
        long sequence = consumerSequence.get() + 1;
        
        if (sequence > highestPublishedSequence.get()) {
            return null;
        }
        
        int index = (int) (sequence & bufferMask);
        Entry entry = buffer[index];
        
        return (T) ENTRY_VALUE_HANDLE.getAcquire(entry);
    }
    
    /**
     * 更新最高已发布序列
     */
    private void updateHighestPublished(long sequence) {
        long expected;
        do {
            expected = highestPublishedSequence.get();
            if (sequence <= expected) return;
        } while (!highestPublishedSequence.compareAndSet(expected, sequence));
    }
    
    /**
     * 获取待消费数量
     */
    public long getPendingCount() {
        return highestPublishedSequence.get() - consumerSequence.get();
    }
    
    /**
     * 获取缓冲区容量
     */
    public int getCapacity() {
        return bufferMask + 1;
    }
    
    /**
     * 获取缓冲区使用率
     */
    public double getUsageRate() {
        return (double) getPendingCount() / getCapacity();
    }
    
    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return consumerSequence.get() >= highestPublishedSequence.get();
    }
    
    /**
     * 检查是否已满
     */
    public boolean isFull() {
        return producerSequence.get() - consumerSequence.get() >= bufferMask;
    }
    
    /**
     * 重置缓冲区
     */
    public void reset() {
        producerSequence.set(-1);
        consumerSequence.set(-1);
        highestPublishedSequence.set(-1);
        
        for (Entry entry : buffer) {
            entry.sequence = -1;
            ENTRY_VALUE_HANDLE.setRelease(entry, null);
        }
        
        log.info("RingBuffer reset");
    }
    
    /**
     * 获取统计信息
     */
    public RingBufferStats getStats() {
        return new RingBufferStats(
            getCapacity(),
            totalPublished.get(),
            totalConsumed.get(),
            getPendingCount(),
            overflowCount.get(),
            getUsageRate()
        );
    }
    
    /**
     * 计算大于等于n的最小2的幂
     */
    private static int nextPowerOfTwo(int n) {
        if (n <= 0) return 1;
        n--;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
        return n + 1;
    }
    
    // ========== 内部类 ==========
    
    /**
     * 缓冲区条目
     */
    private static class Entry {
        volatile long sequence = -1;
        volatile Object value;
    }
    
    /**
     * 带缓存行填充的AtomicLong
     */
    private static class PaddedAtomicLong extends AtomicLong {
        // 前置填充
        private long p1, p2, p3, p4, p5, p6, p7;
        
        PaddedAtomicLong(long initialValue) {
            super(initialValue);
        }
        
        // 防止JVM优化掉填充字段
        public long preventOptimization() {
            return p1 + p2 + p3 + p4 + p5 + p6 + p7;
        }
    }
    
    /**
     * 统计信息
     */
    public record RingBufferStats(
        int capacity,
        long totalPublished,
        long totalConsumed,
        long pendingCount,
        long overflowCount,
        double usageRate
    ) {}
}

/**
 * 零GC对象池 - 高性能对象复用池
 */
@Component
class ZeroGCObjectPool<T> {
    
    private static final Logger log = LoggerFactory.getLogger(ZeroGCObjectPool.class);
    
    private final Object[] pool;
    private final int mask;
    private final AtomicLong acquireIndex = new AtomicLong(0);
    private final AtomicLong releaseIndex = new AtomicLong(0);
    
    private final java.util.function.Supplier<T> factory;
    private final Consumer<T> resetter;
    
    private final AtomicLong allocations = new AtomicLong(0);
    private final AtomicLong borrows = new AtomicLong(0);
    private final AtomicLong returns = new AtomicLong(0);
    
    public ZeroGCObjectPool(java.util.function.Supplier<T> factory, Consumer<T> resetter, int size) {
        int actualSize = nextPowerOfTwo(size);
        this.pool = new Object[actualSize];
        this.mask = actualSize - 1;
        this.factory = factory;
        this.resetter = resetter;
        
        // 预填充
        for (int i = 0; i < actualSize; i++) {
            pool[i] = factory.get();
            allocations.incrementAndGet();
        }
        releaseIndex.set(actualSize);
        
        log.info("ZeroGCObjectPool initialized: size={}", actualSize);
    }
    
    /**
     * 获取对象
     */
    @SuppressWarnings("unchecked")
    public T acquire() {
        borrows.incrementAndGet();
        
        long index = acquireIndex.getAndIncrement();
        long release = releaseIndex.get();
        
        if (index < release) {
            int slot = (int) (index & mask);
            T obj = (T) pool[slot];
            pool[slot] = null;
            return obj;
        }
        
        // 池空，创建新对象
        allocations.incrementAndGet();
        return factory.get();
    }
    
    /**
     * 归还对象
     */
    public void release(T obj) {
        if (obj == null) return;
        
        returns.incrementAndGet();
        
        // 重置对象状态
        if (resetter != null) {
            resetter.accept(obj);
        }
        
        long index = releaseIndex.getAndIncrement();
        int slot = (int) (index & mask);
        pool[slot] = obj;
    }
    
    /**
     * 使用对象执行操作
     */
    public <R> R execute(java.util.function.Function<T, R> action) {
        T obj = acquire();
        try {
            return action.apply(obj);
        } finally {
            release(obj);
        }
    }
    
    public ObjectPoolStats getStats() {
        return new ObjectPoolStats(
            mask + 1,
            allocations.get(),
            borrows.get(),
            returns.get()
        );
    }
    
    private static int nextPowerOfTwo(int n) {
        if (n <= 0) return 1;
        n--;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
        return n + 1;
    }
    
    public record ObjectPoolStats(int capacity, long allocations, long borrows, long returns) {}
}

/**
 * 零GC字符串缓冲区 - 高性能字符串构建
 */
class ZeroGCStringBuffer {
    
    private final char[] buffer;
    private int position = 0;
    
    public ZeroGCStringBuffer(int capacity) {
        this.buffer = new char[capacity];
    }
    
    public ZeroGCStringBuffer append(String s) {
        if (s == null) return this;
        int len = s.length();
        if (position + len > buffer.length) {
            // 截断
            len = buffer.length - position;
        }
        s.getChars(0, len, buffer, position);
        position += len;
        return this;
    }
    
    public ZeroGCStringBuffer append(long value) {
        // 直接写入数字，避免Long.toString的对象分配
        if (value == 0) {
            if (position < buffer.length) {
                buffer[position++] = '0';
            }
            return this;
        }
        
        if (value < 0) {
            if (position < buffer.length) {
                buffer[position++] = '-';
            }
            value = -value;
        }
        
        // 计算数字位数
        int digits = 0;
        long temp = value;
        while (temp > 0) {
            digits++;
            temp /= 10;
        }
        
        // 从后往前填充
        int endPos = position + digits;
        if (endPos > buffer.length) {
            endPos = buffer.length;
        }
        
        int writePos = endPos - 1;
        while (value > 0 && writePos >= position) {
            buffer[writePos--] = (char) ('0' + (value % 10));
            value /= 10;
        }
        
        position = endPos;
        return this;
    }
    
    public ZeroGCStringBuffer append(char c) {
        if (position < buffer.length) {
            buffer[position++] = c;
        }
        return this;
    }
    
    public String toString() {
        return new String(buffer, 0, position);
    }
    
    public void reset() {
        position = 0;
    }
    
    public int length() {
        return position;
    }
    
    public int capacity() {
        return buffer.length;
    }
}
