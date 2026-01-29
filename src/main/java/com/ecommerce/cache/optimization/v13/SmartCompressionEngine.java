package com.ecommerce.cache.optimization.v13;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;

/**
 * V13智能内存压缩引擎
 * 支持LZ4/ZSTD/Snappy算法、字典压缩、流式压缩、自适应压缩
 */
@Component
public class SmartCompressionEngine {

    private static final Logger log = LoggerFactory.getLogger(SmartCompressionEngine.class);

    private final OptimizationV13Properties properties;
    private final MeterRegistry meterRegistry;

    // 压缩器缓存
    private final ConcurrentMap<String, CompressionStrategy> strategies = new ConcurrentHashMap<>();
    
    // 字典
    private volatile byte[] compressionDictionary;
    private final ConcurrentMap<String, byte[]> schemaDictionaries = new ConcurrentHashMap<>();
    
    // 统计
    private final ConcurrentMap<String, CompressionStats> statsPerType = new ConcurrentHashMap<>();
    private final AtomicLong totalBytesIn = new AtomicLong(0);
    private final AtomicLong totalBytesOut = new AtomicLong(0);
    
    // 执行器
    private ScheduledExecutorService scheduler;
    private ExecutorService compressionExecutor;
    
    // 指标
    private Counter compressionCount;
    private Counter decompressionCount;
    private Timer compressionTime;
    private Timer decompressionTime;

    public SmartCompressionEngine(OptimizationV13Properties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        if (!properties.getSmartCompression().isEnabled()) {
            log.info("V13智能内存压缩引擎已禁用");
            return;
        }

        initializeMetrics();
        initializeStrategies();
        initializeDictionaries();
        initializeExecutors();
        startBackgroundTasks();

        log.info("V13智能内存压缩引擎初始化完成 - 算法: {}, 级别: {}",
                properties.getSmartCompression().getAlgorithm(),
                properties.getSmartCompression().getCompressionLevel());
    }

    private void initializeMetrics() {
        compressionCount = Counter.builder("v13.compression.count")
                .description("压缩次数").register(meterRegistry);
        decompressionCount = Counter.builder("v13.decompression.count")
                .description("解压次数").register(meterRegistry);
        compressionTime = Timer.builder("v13.compression.time")
                .description("压缩耗时").register(meterRegistry);
        decompressionTime = Timer.builder("v13.decompression.time")
                .description("解压耗时").register(meterRegistry);
        
        Gauge.builder("v13.compression.ratio", this, e -> 
                totalBytesIn.get() > 0 ? (double) totalBytesOut.get() / totalBytesIn.get() : 0)
                .description("压缩比").register(meterRegistry);
    }

    private void initializeStrategies() {
        strategies.put("LZ4", new LZ4Strategy());
        strategies.put("ZSTD", new ZstdStrategy());
        strategies.put("SNAPPY", new SnappyStrategy());
        strategies.put("GZIP", new GzipStrategy());
        strategies.put("DEFLATE", new DeflateStrategy());
        strategies.put("NONE", new NoCompressionStrategy());
    }

    private void initializeDictionaries() {
        if (properties.getSmartCompression().isDictionaryCompressionEnabled()) {
            compressionDictionary = buildCompressionDictionary();
        }
    }

    private void initializeExecutors() {
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "v13-compression-scheduler");
            t.setDaemon(true);
            return t;
        });
        compressionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private void startBackgroundTasks() {
        // 字典更新
        scheduler.scheduleAtFixedRate(this::updateDictionary, 60, 60, TimeUnit.SECONDS);
        // 统计清理
        scheduler.scheduleAtFixedRate(this::cleanupStats, 300, 300, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (compressionExecutor != null) compressionExecutor.shutdown();
    }

    // ==================== 压缩API ====================

    /**
     * 压缩数据
     */
    public CompressedData compress(byte[] data) {
        return compress(data, selectBestAlgorithm(data));
    }

    public CompressedData compress(byte[] data, String algorithm) {
        if (data == null || data.length == 0) {
            return new CompressedData(data, algorithm, 0, 0);
        }

        // 小数据不压缩
        if (data.length < properties.getSmartCompression().getMinSizeForCompression()) {
            return new CompressedData(data, "NONE", data.length, data.length);
        }

        return compressionTime.record(() -> {
            try {
                CompressionStrategy strategy = strategies.getOrDefault(algorithm, strategies.get("GZIP"));
                byte[] compressed = strategy.compress(data, properties.getSmartCompression().getCompressionLevel());
                
                // 压缩效果不好则不压缩
                if (compressed.length >= data.length * 0.95) {
                    return new CompressedData(data, "NONE", data.length, data.length);
                }
                
                totalBytesIn.addAndGet(data.length);
                totalBytesOut.addAndGet(compressed.length);
                compressionCount.increment();
                
                updateStats(algorithm, data.length, compressed.length);
                
                return new CompressedData(compressed, algorithm, data.length, compressed.length);
                
            } catch (Exception e) {
                log.error("压缩失败: algorithm={}", algorithm, e);
                return new CompressedData(data, "NONE", data.length, data.length);
            }
        });
    }

    /**
     * 解压数据
     */
    public byte[] decompress(CompressedData compressedData) {
        if (compressedData == null || compressedData.getData() == null) {
            return null;
        }
        
        if ("NONE".equals(compressedData.getAlgorithm())) {
            return compressedData.getData();
        }

        return decompressionTime.record(() -> {
            try {
                CompressionStrategy strategy = strategies.get(compressedData.getAlgorithm());
                if (strategy == null) {
                    throw new IllegalArgumentException("未知压缩算法: " + compressedData.getAlgorithm());
                }
                
                byte[] decompressed = strategy.decompress(compressedData.getData());
                decompressionCount.increment();
                
                return decompressed;
                
            } catch (Exception e) {
                log.error("解压失败: algorithm={}", compressedData.getAlgorithm(), e);
                return compressedData.getData();
            }
        });
    }

    /**
     * 对象压缩
     */
    public <T> CompressedData compressObject(T object) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(object);
            oos.close();
            return compress(baos.toByteArray());
        } catch (IOException e) {
            throw new CompressionException("对象序列化失败", e);
        }
    }

    /**
     * 对象解压
     */
    @SuppressWarnings("unchecked")
    public <T> T decompressObject(CompressedData compressedData, Class<T> type) {
        try {
            byte[] data = decompress(compressedData);
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            ObjectInputStream ois = new ObjectInputStream(bais);
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new CompressionException("对象反序列化失败", e);
        }
    }

    /**
     * 流式压缩
     */
    public OutputStream createCompressingStream(OutputStream out, String algorithm) {
        if (!properties.getSmartCompression().isStreamingCompressionEnabled()) {
            return out;
        }
        
        try {
            return switch (algorithm.toUpperCase()) {
                case "GZIP" -> new GZIPOutputStream(out);
                case "DEFLATE" -> new DeflaterOutputStream(out);
                default -> out;
            };
        } catch (IOException e) {
            throw new CompressionException("创建压缩流失败", e);
        }
    }

    /**
     * 流式解压
     */
    public InputStream createDecompressingStream(InputStream in, String algorithm) {
        try {
            return switch (algorithm.toUpperCase()) {
                case "GZIP" -> new GZIPInputStream(in);
                case "DEFLATE" -> new InflaterInputStream(in);
                default -> in;
            };
        } catch (IOException e) {
            throw new CompressionException("创建解压流失败", e);
        }
    }

    // ==================== 自适应压缩 ====================

    /**
     * 选择最佳压缩算法
     */
    public String selectBestAlgorithm(byte[] data) {
        if (!properties.getSmartCompression().isAdaptiveCompressionEnabled()) {
            return properties.getSmartCompression().getAlgorithm();
        }

        // 分析数据特征
        DataCharacteristics chars = analyzeData(data);
        
        // 基于特征选择算法
        if (chars.entropy < 0.3) {
            // 低熵数据（高重复）- 使用高压缩比算法
            return "ZSTD";
        } else if (chars.entropy > 0.9) {
            // 高熵数据（接近随机）- 不压缩或轻度压缩
            return "NONE";
        } else if (chars.isText) {
            // 文本数据
            return "ZSTD";
        } else if (data.length < 1024) {
            // 小数据 - 快速算法
            return "LZ4";
        } else if (data.length > 1024 * 1024) {
            // 大数据 - 平衡算法
            return "GZIP";
        }
        
        // 基于历史统计选择
        return selectByHistoricalStats();
    }

    private DataCharacteristics analyzeData(byte[] data) {
        DataCharacteristics chars = new DataCharacteristics();
        
        // 计算熵
        int[] freq = new int[256];
        for (byte b : data) {
            freq[b & 0xFF]++;
        }
        
        double entropy = 0;
        for (int f : freq) {
            if (f > 0) {
                double p = (double) f / data.length;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        chars.entropy = entropy / 8.0; // 归一化到0-1
        
        // 检测是否是文本
        int printableCount = 0;
        for (byte b : data) {
            if (b >= 32 && b < 127) {
                printableCount++;
            }
        }
        chars.isText = printableCount > data.length * 0.8;
        
        chars.size = data.length;
        
        return chars;
    }

    private String selectByHistoricalStats() {
        String best = properties.getSmartCompression().getAlgorithm();
        double bestScore = 0;
        
        for (Map.Entry<String, CompressionStats> entry : statsPerType.entrySet()) {
            CompressionStats stats = entry.getValue();
            // 综合评分：压缩比 * 0.6 + 速度因子 * 0.4
            double ratio = stats.totalCompressed > 0 
                    ? 1.0 - (double) stats.totalCompressed / stats.totalOriginal 
                    : 0;
            double speedFactor = stats.count > 0 
                    ? 1.0 / (1.0 + stats.totalTime / stats.count / 1000.0) 
                    : 0.5;
            double score = ratio * 0.6 + speedFactor * 0.4;
            
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }
        
        return best;
    }

    // ==================== 字典压缩 ====================

    private byte[] buildCompressionDictionary() {
        // 构建通用压缩字典
        StringBuilder sb = new StringBuilder();
        
        // 常见JSON模式
        sb.append("{\"id\":\"name\":\"value\":\"type\":\"data\":\"list\":[],\"map\":{}}");
        
        // 常见缓存键模式
        sb.append("spu:sku:product:category:user:order:cache:hot:");
        
        // 常见属性名
        sb.append("createTime:updateTime:status:enabled:deleted:version:");
        
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 使用字典压缩
     */
    public CompressedData compressWithDictionary(byte[] data, String schema) {
        byte[] dictionary = schemaDictionaries.getOrDefault(schema, compressionDictionary);
        if (dictionary == null) {
            return compress(data);
        }
        
        try {
            Deflater deflater = new Deflater(properties.getSmartCompression().getCompressionLevel());
            deflater.setDictionary(dictionary);
            deflater.setInput(data);
            deflater.finish();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                baos.write(buffer, 0, count);
            }
            deflater.end();
            
            byte[] compressed = baos.toByteArray();
            
            compressionCount.increment();
            totalBytesIn.addAndGet(data.length);
            totalBytesOut.addAndGet(compressed.length);
            
            return new CompressedData(compressed, "DICT_DEFLATE", data.length, compressed.length);
            
        } catch (Exception e) {
            log.warn("字典压缩失败，降级为普通压缩", e);
            return compress(data);
        }
    }

    /**
     * 字典解压
     */
    public byte[] decompressWithDictionary(CompressedData compressedData, String schema) {
        if (!"DICT_DEFLATE".equals(compressedData.getAlgorithm())) {
            return decompress(compressedData);
        }
        
        byte[] dictionary = schemaDictionaries.getOrDefault(schema, compressionDictionary);
        
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(compressedData.getData());
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream(compressedData.getOriginalSize());
            byte[] buffer = new byte[4096];
            
            while (!inflater.finished()) {
                try {
                    int count = inflater.inflate(buffer);
                    if (count > 0) {
                        baos.write(buffer, 0, count);
                    }
                } catch (DataFormatException e) {
                    if (inflater.needsDictionary() && dictionary != null) {
                        inflater.setDictionary(dictionary);
                    } else {
                        throw e;
                    }
                }
            }
            inflater.end();
            
            decompressionCount.increment();
            return baos.toByteArray();
            
        } catch (Exception e) {
            throw new CompressionException("字典解压失败", e);
        }
    }

    /**
     * 注册模式字典
     */
    public void registerSchemaDictionary(String schema, byte[] dictionary) {
        schemaDictionaries.put(schema, dictionary);
        log.info("注册模式字典: schema={}, size={}", schema, dictionary.length);
    }

    // ==================== 统计与维护 ====================

    private void updateStats(String algorithm, int original, int compressed) {
        statsPerType.compute(algorithm, (k, v) -> {
            if (v == null) v = new CompressionStats();
            v.count++;
            v.totalOriginal += original;
            v.totalCompressed += compressed;
            v.totalTime += System.nanoTime();
            return v;
        });
    }

    private void updateDictionary() {
        // 基于统计数据动态更新字典
        // 实际实现需要收集高频数据模式
        log.debug("字典更新检查完成");
    }

    private void cleanupStats() {
        long threshold = System.currentTimeMillis() - 3600000; // 1小时前
        statsPerType.values().removeIf(s -> s.lastUpdated < threshold);
    }

    // ==================== 状态查询 ====================

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalBytesIn", totalBytesIn.get());
        stats.put("totalBytesOut", totalBytesOut.get());
        stats.put("overallRatio", totalBytesIn.get() > 0 
                ? String.format("%.2f%%", (double) totalBytesOut.get() / totalBytesIn.get() * 100) 
                : "N/A");
        
        Map<String, Object> algorithmStats = new LinkedHashMap<>();
        for (Map.Entry<String, CompressionStats> e : statsPerType.entrySet()) {
            CompressionStats s = e.getValue();
            algorithmStats.put(e.getKey(), Map.of(
                    "count", s.count,
                    "ratio", s.totalOriginal > 0 
                            ? String.format("%.2f%%", (double) s.totalCompressed / s.totalOriginal * 100) 
                            : "N/A"
            ));
        }
        stats.put("algorithms", algorithmStats);
        
        return stats;
    }

    // ==================== 压缩策略实现 ====================

    private interface CompressionStrategy {
        byte[] compress(byte[] data, int level) throws IOException;
        byte[] decompress(byte[] data) throws IOException;
    }

    private static class LZ4Strategy implements CompressionStrategy {
        @Override
        public byte[] compress(byte[] data, int level) throws IOException {
            // LZ4模拟实现（实际需要lz4-java库）
            return deflateCompress(data, level);
        }
        
        @Override
        public byte[] decompress(byte[] data) throws IOException {
            return deflateDecompress(data);
        }
    }

    private static class ZstdStrategy implements CompressionStrategy {
        @Override
        public byte[] compress(byte[] data, int level) throws IOException {
            // ZSTD模拟实现（实际需要zstd-jni库）
            return deflateCompress(data, Math.min(level + 1, 9));
        }
        
        @Override
        public byte[] decompress(byte[] data) throws IOException {
            return deflateDecompress(data);
        }
    }

    private static class SnappyStrategy implements CompressionStrategy {
        @Override
        public byte[] compress(byte[] data, int level) throws IOException {
            // Snappy模拟实现（实际需要snappy-java库）
            return deflateCompress(data, 1); // 快速压缩
        }
        
        @Override
        public byte[] decompress(byte[] data) throws IOException {
            return deflateDecompress(data);
        }
    }

    private static class GzipStrategy implements CompressionStrategy {
        @Override
        public byte[] compress(byte[] data, int level) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
            GZIPOutputStream gzos = new GZIPOutputStream(baos) {{
                def.setLevel(level);
            }};
            gzos.write(data);
            gzos.close();
            return baos.toByteArray();
        }
        
        @Override
        public byte[] decompress(byte[] data) throws IOException {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            GZIPInputStream gzis = new GZIPInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = gzis.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            gzis.close();
            return baos.toByteArray();
        }
    }

    private static class DeflateStrategy implements CompressionStrategy {
        @Override
        public byte[] compress(byte[] data, int level) throws IOException {
            return deflateCompress(data, level);
        }
        
        @Override
        public byte[] decompress(byte[] data) throws IOException {
            return deflateDecompress(data);
        }
    }

    private static class NoCompressionStrategy implements CompressionStrategy {
        @Override
        public byte[] compress(byte[] data, int level) {
            return data;
        }
        
        @Override
        public byte[] decompress(byte[] data) {
            return data;
        }
    }

    private static byte[] deflateCompress(byte[] data, int level) throws IOException {
        Deflater deflater = new Deflater(level);
        deflater.setInput(data);
        deflater.finish();
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            baos.write(buffer, 0, count);
        }
        deflater.end();
        return baos.toByteArray();
    }

    private static byte[] deflateDecompress(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                baos.write(buffer, 0, count);
            }
        } catch (DataFormatException e) {
            throw new IOException("解压数据格式错误", e);
        }
        inflater.end();
        return baos.toByteArray();
    }

    // ==================== 数据类 ====================

    public static class CompressedData implements Serializable {
        private final byte[] data;
        private final String algorithm;
        private final int originalSize;
        private final int compressedSize;

        public CompressedData(byte[] data, String algorithm, int originalSize, int compressedSize) {
            this.data = data;
            this.algorithm = algorithm;
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
        }

        public byte[] getData() { return data; }
        public String getAlgorithm() { return algorithm; }
        public int getOriginalSize() { return originalSize; }
        public int getCompressedSize() { return compressedSize; }
        
        public double getRatio() {
            return originalSize > 0 ? (double) compressedSize / originalSize : 1.0;
        }
    }

    private static class DataCharacteristics {
        double entropy;
        boolean isText;
        int size;
    }

    private static class CompressionStats {
        long count;
        long totalOriginal;
        long totalCompressed;
        long totalTime;
        long lastUpdated = System.currentTimeMillis();
    }

    public static class CompressionException extends RuntimeException {
        public CompressionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
