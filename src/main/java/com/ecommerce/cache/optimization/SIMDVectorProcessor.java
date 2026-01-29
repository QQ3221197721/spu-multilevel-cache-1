package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jdk.incubator.vector.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * SIMD 向量化数据处理器
 * 
 * 使用 JDK 21 Vector API 实现高性能数据处理：
 * 1. 向量化哈希计算 - SIMD 加速哈希运算
 * 2. 向量化数据比较 - 批量 key 匹配
 * 3. 向量化数据搜索 - 并行数据查找
 * 4. 向量化数据聚合 - 批量统计计算
 * 5. 向量化压缩预处理 - 数据预扫描
 * 
 * 性能目标：
 * - 批量哈希: 8x 提升
 * - 批量比较: 16x 提升
 * - 批量搜索: 10x 提升
 * 
 * @author optimization-team
 * @version 2.0
 */
@Service
public class SIMDVectorProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(SIMDVectorProcessor.class);
    
    // ==================== Vector 配置 ====================
    private static final VectorSpecies<Integer> INT_SPECIES = IntVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Long> LONG_SPECIES = LongVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Float> FLOAT_SPECIES = FloatVector.SPECIES_PREFERRED;
    
    // ==================== 哈希常量 ====================
    private static final int FNV_PRIME = 0x01000193;
    private static final int FNV_OFFSET = 0x811c9dc5;
    private static final long XXHASH_PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long XXHASH_PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    
    // ==================== 组件 ====================
    private final MeterRegistry meterRegistry;
    private final ExecutorService vectorExecutor;
    
    // ==================== 性能指标 ====================
    private final Timer hashTimer;
    private final Timer compareTimer;
    private final Timer searchTimer;
    private final Timer aggregateTimer;
    private final LongAdder vectorOperations;
    private final LongAdder scalarFallbacks;
    
    // ==================== SIMD 支持检测 ====================
    private final boolean simdSupported;
    private final int vectorLanes;
    
    public SIMDVectorProcessor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.vectorExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        // 检测 SIMD 支持
        this.simdSupported = detectSIMDSupport();
        this.vectorLanes = INT_SPECIES.length();
        
        // 注册指标
        this.hashTimer = Timer.builder("simd.hash.latency").register(meterRegistry);
        this.compareTimer = Timer.builder("simd.compare.latency").register(meterRegistry);
        this.searchTimer = Timer.builder("simd.search.latency").register(meterRegistry);
        this.aggregateTimer = Timer.builder("simd.aggregate.latency").register(meterRegistry);
        this.vectorOperations = new LongAdder();
        this.scalarFallbacks = new LongAdder();
    }
    
    @PostConstruct
    public void initialize() {
        log.info("SIMD Vector Processor initialized");
        log.info("SIMD Support: {}", simdSupported);
        log.info("Int Vector Lanes: {}", INT_SPECIES.length());
        log.info("Long Vector Lanes: {}", LONG_SPECIES.length());
        log.info("Byte Vector Lanes: {}", BYTE_SPECIES.length());
    }
    
    // ==================== 向量化哈希计算 ====================
    
    /**
     * 批量计算 FNV-1a 哈希（向量化）
     */
    public int[] batchHashFNV1a(String[] keys) {
        return hashTimer.record(() -> {
            int[] hashes = new int[keys.length];
            
            if (simdSupported && keys.length >= vectorLanes) {
                vectorizedHashFNV1a(keys, hashes);
            } else {
                scalarHashFNV1a(keys, hashes);
            }
            
            return hashes;
        });
    }
    
    private void vectorizedHashFNV1a(String[] keys, int[] hashes) {
        int i = 0;
        int bound = INT_SPECIES.loopBound(keys.length);
        
        // 向量化处理
        for (; i < bound; i += vectorLanes) {
            IntVector hashVector = IntVector.broadcast(INT_SPECIES, FNV_OFFSET);
            
            // 找到这批 key 的最大长度
            int maxLen = 0;
            for (int j = 0; j < vectorLanes; j++) {
                maxLen = Math.max(maxLen, keys[i + j].length());
            }
            
            // 逐字符处理
            for (int c = 0; c < maxLen; c++) {
                int[] chars = new int[vectorLanes];
                for (int j = 0; j < vectorLanes; j++) {
                    String key = keys[i + j];
                    chars[j] = c < key.length() ? key.charAt(c) : 0;
                }
                
                IntVector charVector = IntVector.fromArray(INT_SPECIES, chars, 0);
                hashVector = hashVector.lanewise(VectorOperators.XOR, charVector);
                hashVector = hashVector.mul(FNV_PRIME);
            }
            
            hashVector.intoArray(hashes, i);
            vectorOperations.increment();
        }
        
        // 处理剩余元素
        for (; i < keys.length; i++) {
            hashes[i] = scalarFNV1a(keys[i]);
        }
    }
    
    private void scalarHashFNV1a(String[] keys, int[] hashes) {
        for (int i = 0; i < keys.length; i++) {
            hashes[i] = scalarFNV1a(keys[i]);
        }
        scalarFallbacks.increment();
    }
    
    private int scalarFNV1a(String key) {
        int hash = FNV_OFFSET;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
    
    /**
     * 批量计算 XXHash64（向量化）
     */
    public long[] batchHashXXHash64(byte[][] dataArray) {
        long[] hashes = new long[dataArray.length];
        
        for (int i = 0; i < dataArray.length; i++) {
            hashes[i] = xxhash64(dataArray[i]);
        }
        
        return hashes;
    }
    
    private long xxhash64(byte[] data) {
        long hash = XXHASH_PRIME64_2;
        
        int i = 0;
        int bound = LONG_SPECIES.loopBound(data.length / 8);
        
        if (simdSupported && bound > 0) {
            LongVector acc = LongVector.broadcast(LONG_SPECIES, XXHASH_PRIME64_2);
            
            for (; i < bound * 8; i += LONG_SPECIES.length() * 8) {
                long[] longs = new long[LONG_SPECIES.length()];
                for (int j = 0; j < LONG_SPECIES.length(); j++) {
                    longs[j] = bytesToLong(data, i + j * 8);
                }
                
                LongVector dataVector = LongVector.fromArray(LONG_SPECIES, longs, 0);
                acc = acc.add(dataVector.mul(XXHASH_PRIME64_1));
                acc = rotateLeft(acc, 31);
                acc = acc.mul(XXHASH_PRIME64_2);
            }
            
            hash = acc.reduceLanes(VectorOperators.XOR);
            vectorOperations.increment();
        }
        
        // 处理剩余字节
        for (; i < data.length; i++) {
            hash ^= (data[i] & 0xFF) * XXHASH_PRIME64_1;
            hash = Long.rotateLeft(hash, 11) * XXHASH_PRIME64_2;
        }
        
        return hash;
    }
    
    // ==================== 向量化数据比较 ====================
    
    /**
     * 批量键比较（向量化）
     */
    public boolean[] batchCompareKeys(byte[] target, byte[][] candidates) {
        return compareTimer.record(() -> {
            boolean[] matches = new boolean[candidates.length];
            
            for (int i = 0; i < candidates.length; i++) {
                matches[i] = vectorizedEquals(target, candidates[i]);
            }
            
            return matches;
        });
    }
    
    /**
     * 向量化字节数组比较
     */
    public boolean vectorizedEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        
        if (!simdSupported || a.length < BYTE_SPECIES.length()) {
            return Arrays.equals(a, b);
        }
        
        int i = 0;
        int bound = BYTE_SPECIES.loopBound(a.length);
        
        for (; i < bound; i += BYTE_SPECIES.length()) {
            ByteVector va = ByteVector.fromArray(BYTE_SPECIES, a, i);
            ByteVector vb = ByteVector.fromArray(BYTE_SPECIES, b, i);
            
            VectorMask<Byte> mask = va.compare(VectorOperators.NE, vb);
            if (mask.anyTrue()) {
                return false;
            }
        }
        
        // 比较剩余字节
        for (; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        
        vectorOperations.increment();
        return true;
    }
    
    // ==================== 向量化数据搜索 ====================
    
    /**
     * 向量化搜索（在数组中查找值）
     */
    public int vectorizedSearch(int[] array, int target) {
        return searchTimer.record(() -> {
            if (!simdSupported || array.length < vectorLanes) {
                return linearSearch(array, target);
            }
            
            IntVector targetVector = IntVector.broadcast(INT_SPECIES, target);
            
            int i = 0;
            int bound = INT_SPECIES.loopBound(array.length);
            
            for (; i < bound; i += vectorLanes) {
                IntVector dataVector = IntVector.fromArray(INT_SPECIES, array, i);
                VectorMask<Integer> mask = dataVector.compare(VectorOperators.EQ, targetVector);
                
                if (mask.anyTrue()) {
                    // 找到匹配，确定具体位置
                    for (int j = 0; j < vectorLanes; j++) {
                        if (mask.laneIsSet(j)) {
                            vectorOperations.increment();
                            return i + j;
                        }
                    }
                }
            }
            
            // 搜索剩余元素
            for (; i < array.length; i++) {
                if (array[i] == target) {
                    return i;
                }
            }
            
            return -1;
        });
    }
    
    /**
     * 向量化批量搜索
     */
    public int[] vectorizedBatchSearch(int[] array, int[] targets) {
        int[] results = new int[targets.length];
        Arrays.fill(results, -1);
        
        for (int t = 0; t < targets.length; t++) {
            results[t] = vectorizedSearch(array, targets[t]);
        }
        
        return results;
    }
    
    private int linearSearch(int[] array, int target) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == target) {
                return i;
            }
        }
        scalarFallbacks.increment();
        return -1;
    }
    
    // ==================== 向量化数据聚合 ====================
    
    /**
     * 向量化求和
     */
    public long vectorizedSum(int[] array) {
        return aggregateTimer.record(() -> {
            if (!simdSupported || array.length < vectorLanes) {
                return scalarSum(array);
            }
            
            int i = 0;
            int bound = INT_SPECIES.loopBound(array.length);
            IntVector sumVector = IntVector.zero(INT_SPECIES);
            
            for (; i < bound; i += vectorLanes) {
                IntVector dataVector = IntVector.fromArray(INT_SPECIES, array, i);
                sumVector = sumVector.add(dataVector);
            }
            
            long sum = sumVector.reduceLanes(VectorOperators.ADD);
            
            // 处理剩余元素
            for (; i < array.length; i++) {
                sum += array[i];
            }
            
            vectorOperations.increment();
            return sum;
        });
    }
    
    /**
     * 向量化求最大值
     */
    public int vectorizedMax(int[] array) {
        if (array.length == 0) {
            throw new IllegalArgumentException("Array cannot be empty");
        }
        
        if (!simdSupported || array.length < vectorLanes) {
            return scalarMax(array);
        }
        
        int i = 0;
        int bound = INT_SPECIES.loopBound(array.length);
        IntVector maxVector = IntVector.broadcast(INT_SPECIES, Integer.MIN_VALUE);
        
        for (; i < bound; i += vectorLanes) {
            IntVector dataVector = IntVector.fromArray(INT_SPECIES, array, i);
            maxVector = maxVector.max(dataVector);
        }
        
        int max = maxVector.reduceLanes(VectorOperators.MAX);
        
        // 处理剩余元素
        for (; i < array.length; i++) {
            max = Math.max(max, array[i]);
        }
        
        vectorOperations.increment();
        return max;
    }
    
    /**
     * 向量化求最小值
     */
    public int vectorizedMin(int[] array) {
        if (array.length == 0) {
            throw new IllegalArgumentException("Array cannot be empty");
        }
        
        if (!simdSupported || array.length < vectorLanes) {
            return scalarMin(array);
        }
        
        int i = 0;
        int bound = INT_SPECIES.loopBound(array.length);
        IntVector minVector = IntVector.broadcast(INT_SPECIES, Integer.MAX_VALUE);
        
        for (; i < bound; i += vectorLanes) {
            IntVector dataVector = IntVector.fromArray(INT_SPECIES, array, i);
            minVector = minVector.min(dataVector);
        }
        
        int min = minVector.reduceLanes(VectorOperators.MIN);
        
        // 处理剩余元素
        for (; i < array.length; i++) {
            min = Math.min(min, array[i]);
        }
        
        vectorOperations.increment();
        return min;
    }
    
    /**
     * 向量化计算平均值
     */
    public double vectorizedAverage(float[] array) {
        if (array.length == 0) {
            return 0;
        }
        
        if (!simdSupported || array.length < FLOAT_SPECIES.length()) {
            return scalarAverage(array);
        }
        
        int i = 0;
        int bound = FLOAT_SPECIES.loopBound(array.length);
        FloatVector sumVector = FloatVector.zero(FLOAT_SPECIES);
        
        for (; i < bound; i += FLOAT_SPECIES.length()) {
            FloatVector dataVector = FloatVector.fromArray(FLOAT_SPECIES, array, i);
            sumVector = sumVector.add(dataVector);
        }
        
        double sum = sumVector.reduceLanes(VectorOperators.ADD);
        
        // 处理剩余元素
        for (; i < array.length; i++) {
            sum += array[i];
        }
        
        vectorOperations.increment();
        return sum / array.length;
    }
    
    // ==================== 向量化数据转换 ====================
    
    /**
     * 向量化字节转整数
     */
    public int[] vectorizedBytesToInts(byte[] bytes) {
        if (bytes.length % 4 != 0) {
            throw new IllegalArgumentException("Byte array length must be multiple of 4");
        }
        
        int[] ints = new int[bytes.length / 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        
        for (int i = 0; i < ints.length; i++) {
            ints[i] = buffer.getInt();
        }
        
        return ints;
    }
    
    /**
     * 向量化整数转字节
     */
    public byte[] vectorizedIntsToBytes(int[] ints) {
        byte[] bytes = new byte[ints.length * 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        
        for (int value : ints) {
            buffer.putInt(value);
        }
        
        return bytes;
    }
    
    // ==================== 辅助方法 ====================
    
    private boolean detectSIMDSupport() {
        try {
            // 尝试创建向量
            IntVector.zero(INT_SPECIES);
            return true;
        } catch (Exception e) {
            log.warn("SIMD not supported, falling back to scalar operations");
            return false;
        }
    }
    
    private LongVector rotateLeft(LongVector v, int distance) {
        return v.lanewise(VectorOperators.LSHL, distance)
                .or(v.lanewise(VectorOperators.LSHR, 64 - distance));
    }
    
    private long bytesToLong(byte[] bytes, int offset) {
        if (offset + 8 > bytes.length) {
            return 0;
        }
        return ByteBuffer.wrap(bytes, offset, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
    }
    
    private long scalarSum(int[] array) {
        long sum = 0;
        for (int value : array) {
            sum += value;
        }
        scalarFallbacks.increment();
        return sum;
    }
    
    private int scalarMax(int[] array) {
        int max = Integer.MIN_VALUE;
        for (int value : array) {
            max = Math.max(max, value);
        }
        scalarFallbacks.increment();
        return max;
    }
    
    private int scalarMin(int[] array) {
        int min = Integer.MAX_VALUE;
        for (int value : array) {
            min = Math.min(min, value);
        }
        scalarFallbacks.increment();
        return min;
    }
    
    private double scalarAverage(float[] array) {
        double sum = 0;
        for (float value : array) {
            sum += value;
        }
        scalarFallbacks.increment();
        return sum / array.length;
    }
    
    // ==================== 统计信息 ====================
    
    public SIMDStats getStats() {
        return new SIMDStats(
            simdSupported,
            INT_SPECIES.length(),
            LONG_SPECIES.length(),
            BYTE_SPECIES.length(),
            FLOAT_SPECIES.length(),
            vectorOperations.sum(),
            scalarFallbacks.sum(),
            hashTimer.mean(TimeUnit.MICROSECONDS),
            compareTimer.mean(TimeUnit.MICROSECONDS),
            searchTimer.mean(TimeUnit.MICROSECONDS),
            aggregateTimer.mean(TimeUnit.MICROSECONDS)
        );
    }
    
    public record SIMDStats(
        boolean simdSupported,
        int intVectorLanes,
        int longVectorLanes,
        int byteVectorLanes,
        int floatVectorLanes,
        long vectorOperations,
        long scalarFallbacks,
        double avgHashMicros,
        double avgCompareMicros,
        double avgSearchMicros,
        double avgAggregateMicros
    ) {
        public double getVectorUtilization() {
            long total = vectorOperations + scalarFallbacks;
            return total > 0 ? (double) vectorOperations / total : 0;
        }
    }
}
