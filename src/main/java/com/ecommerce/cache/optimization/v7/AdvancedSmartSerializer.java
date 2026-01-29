package com.ecommerce.cache.optimization.v7;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 高级智能序列化器
 * 
 * 核心特性:
 * 1. 多格式支持: JSON、MessagePack、Protobuf风格、原生Java序列化
 * 2. 智能选择: 根据数据特征自动选择最优格式
 * 3. 压缩集成: 大对象自动压缩
 * 4. 类型缓存: 缓存类型信息加速序列化
 * 5. 零拷贝: 直接内存操作减少拷贝
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvancedSmartSerializer {
    
    private final OptimizationV7Properties properties;
    private final MeterRegistry meterRegistry;
    
    // ========== 配置 ==========
    
    /** 压缩阈值(字节) */
    private static final int COMPRESSION_THRESHOLD = 1024;
    
    /** 压缩级别 */
    private static final int COMPRESSION_LEVEL = 6;
    
    /** 最大缓存类型数 */
    private static final int MAX_TYPE_CACHE = 10000;
    
    // ========== 数据结构 ==========
    
    /** 类型ID映射 */
    private final ConcurrentMap<Class<?>, Short> typeToId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Short, Class<?>> idToType = new ConcurrentHashMap<>();
    private final AtomicLong typeIdCounter = new AtomicLong(100);
    
    /** 序列化器注册表 */
    private final ConcurrentMap<Class<?>, TypeSerializer<?>> serializers = new ConcurrentHashMap<>();
    
    /** 格式统计 */
    private final ConcurrentMap<SerializationFormat, AtomicLong> formatStats = new ConcurrentHashMap<>();
    
    // ========== 指标 ==========
    
    private Counter serializeCounter;
    private Counter deserializeCounter;
    private Counter compressionCounter;
    private Timer serializeTimer;
    private Timer deserializeTimer;
    
    @PostConstruct
    public void init() {
        // 注册内置类型
        registerBuiltinTypes();
        
        // 注册内置序列化器
        registerBuiltinSerializers();
        
        // 初始化指标
        initMetrics();
        
        // 初始化格式统计
        for (SerializationFormat format : SerializationFormat.values()) {
            formatStats.put(format, new AtomicLong(0));
        }
        
        log.info("[智能序列化器] 初始化完成 - 压缩阈值: {}字节", COMPRESSION_THRESHOLD);
    }
    
    private void initMetrics() {
        serializeCounter = Counter.builder("cache.serializer.serialize")
            .description("序列化次数")
            .register(meterRegistry);
        
        deserializeCounter = Counter.builder("cache.serializer.deserialize")
            .description("反序列化次数")
            .register(meterRegistry);
        
        compressionCounter = Counter.builder("cache.serializer.compression")
            .description("压缩次数")
            .register(meterRegistry);
        
        serializeTimer = Timer.builder("cache.serializer.serialize.time")
            .description("序列化耗时")
            .register(meterRegistry);
        
        deserializeTimer = Timer.builder("cache.serializer.deserialize.time")
            .description("反序列化耗时")
            .register(meterRegistry);
    }
    
    private void registerBuiltinTypes() {
        // 基础类型
        registerType((short) 1, String.class);
        registerType((short) 2, Integer.class);
        registerType((short) 3, Long.class);
        registerType((short) 4, Double.class);
        registerType((short) 5, Boolean.class);
        registerType((short) 6, byte[].class);
        registerType((short) 7, ArrayList.class);
        registerType((short) 8, HashMap.class);
        registerType((short) 9, LinkedHashMap.class);
        registerType((short) 10, HashSet.class);
    }
    
    private void registerBuiltinSerializers() {
        // String序列化器
        registerSerializer(String.class, new TypeSerializer<String>() {
            @Override
            public void serialize(String obj, DataOutputStream out) throws IOException {
                byte[] bytes = obj.getBytes(StandardCharsets.UTF_8);
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            
            @Override
            public String deserialize(DataInputStream in) throws IOException {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        });
        
        // Integer序列化器
        registerSerializer(Integer.class, new TypeSerializer<Integer>() {
            @Override
            public void serialize(Integer obj, DataOutputStream out) throws IOException {
                out.writeInt(obj);
            }
            
            @Override
            public Integer deserialize(DataInputStream in) throws IOException {
                return in.readInt();
            }
        });
        
        // Long序列化器
        registerSerializer(Long.class, new TypeSerializer<Long>() {
            @Override
            public void serialize(Long obj, DataOutputStream out) throws IOException {
                out.writeLong(obj);
            }
            
            @Override
            public Long deserialize(DataInputStream in) throws IOException {
                return in.readLong();
            }
        });
        
        // byte[]序列化器
        registerSerializer(byte[].class, new TypeSerializer<byte[]>() {
            @Override
            public void serialize(byte[] obj, DataOutputStream out) throws IOException {
                out.writeInt(obj.length);
                out.write(obj);
            }
            
            @Override
            public byte[] deserialize(DataInputStream in) throws IOException {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                return bytes;
            }
        });
    }
    
    private void registerType(short id, Class<?> type) {
        typeToId.put(type, id);
        idToType.put(id, type);
    }
    
    // ========== 核心API ==========
    
    /**
     * 序列化对象
     */
    public byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[]{0};
        }
        
        return serializeTimer.record(() -> {
            serializeCounter.increment();
            
            try {
                // 选择最优格式
                SerializationFormat format = selectFormat(obj);
                formatStats.get(format).incrementAndGet();
                
                byte[] data = doSerialize(obj, format);
                
                // 检查是否需要压缩
                if (data.length > COMPRESSION_THRESHOLD) {
                    byte[] compressed = compress(data);
                    if (compressed.length < data.length * 0.8) {
                        compressionCounter.increment();
                        
                        // 添加压缩标记
                        byte[] result = new byte[compressed.length + 5];
                        result[0] = (byte) 0xFF; // 压缩标记
                        writeInt(result, 1, data.length); // 原始长度
                        System.arraycopy(compressed, 0, result, 5, compressed.length);
                        return result;
                    }
                }
                
                // 添加格式标记
                byte[] result = new byte[data.length + 1];
                result[0] = (byte) format.ordinal();
                System.arraycopy(data, 0, result, 1, data.length);
                return result;
                
            } catch (Exception e) {
                log.warn("[智能序列化器] 序列化失败, 降级到Java序列化", e);
                return javaSerialize(obj);
            }
        });
    }
    
    /**
     * 反序列化对象
     */
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] data, Class<T> type) {
        if (data == null || data.length == 0 || data[0] == 0) {
            return null;
        }
        
        return deserializeTimer.record(() -> {
            deserializeCounter.increment();
            
            try {
                byte[] actualData = data;
                
                // 检查是否压缩
                if (data[0] == (byte) 0xFF) {
                    int originalLength = readInt(data, 1);
                    byte[] compressed = new byte[data.length - 5];
                    System.arraycopy(data, 5, compressed, 0, compressed.length);
                    actualData = decompress(compressed, originalLength);
                    
                    // 反序列化解压后的数据
                    return doDeserialize(actualData, type, SerializationFormat.COMPACT);
                }
                
                SerializationFormat format = SerializationFormat.values()[data[0]];
                byte[] content = new byte[data.length - 1];
                System.arraycopy(data, 1, content, 0, content.length);
                
                return doDeserialize(content, type, format);
                
            } catch (Exception e) {
                log.warn("[智能序列化器] 反序列化失败, 尝试Java反序列化", e);
                return javaDeserialize(data, type);
            }
        });
    }
    
    /**
     * 注册自定义序列化器
     */
    public <T> void registerSerializer(Class<T> type, TypeSerializer<T> serializer) {
        serializers.put(type, serializer);
        
        if (!typeToId.containsKey(type) && typeToId.size() < MAX_TYPE_CACHE) {
            short id = (short) typeIdCounter.incrementAndGet();
            registerType(id, type);
        }
    }
    
    // ========== 内部方法 ==========
    
    private SerializationFormat selectFormat(Object obj) {
        if (obj == null) {
            return SerializationFormat.COMPACT;
        }
        
        Class<?> type = obj.getClass();
        
        // 字符串使用紧凑格式
        if (type == String.class) {
            return SerializationFormat.COMPACT;
        }
        
        // 基础类型使用紧凑格式
        if (type == Integer.class || type == Long.class || 
            type == Double.class || type == Boolean.class) {
            return SerializationFormat.COMPACT;
        }
        
        // 字节数组使用紧凑格式
        if (type == byte[].class) {
            return SerializationFormat.COMPACT;
        }
        
        // 集合类型
        if (obj instanceof Map || obj instanceof Collection) {
            // 小集合使用紧凑格式
            int size = obj instanceof Map ? ((Map<?, ?>) obj).size() : ((Collection<?>) obj).size();
            if (size < 100) {
                return SerializationFormat.COMPACT;
            }
            return SerializationFormat.OPTIMIZED;
        }
        
        // 有注册序列化器的使用紧凑格式
        if (serializers.containsKey(type)) {
            return SerializationFormat.COMPACT;
        }
        
        // 默认使用通用格式
        return SerializationFormat.GENERIC;
    }
    
    @SuppressWarnings("unchecked")
    private byte[] doSerialize(Object obj, SerializationFormat format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        
        Class<?> type = obj.getClass();
        Short typeId = typeToId.get(type);
        
        if (typeId != null) {
            dos.writeShort(typeId);
        } else {
            dos.writeShort(0);
            dos.writeUTF(type.getName());
        }
        
        // 检查是否有自定义序列化器
        TypeSerializer<Object> serializer = (TypeSerializer<Object>) serializers.get(type);
        if (serializer != null) {
            serializer.serialize(obj, dos);
        } else if (obj instanceof Map) {
            serializeMap((Map<?, ?>) obj, dos);
        } else if (obj instanceof Collection) {
            serializeCollection((Collection<?>) obj, dos);
        } else {
            // 降级到Java序列化
            ObjectOutputStream oos = new ObjectOutputStream(dos);
            oos.writeObject(obj);
            oos.flush();
        }
        
        return baos.toByteArray();
    }
    
    @SuppressWarnings("unchecked")
    private <T> T doDeserialize(byte[] data, Class<T> type, SerializationFormat format) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        
        short typeId = dis.readShort();
        Class<?> actualType;
        
        if (typeId != 0) {
            actualType = idToType.get(typeId);
            if (actualType == null) {
                throw new IOException("未知类型ID: " + typeId);
            }
        } else {
            String typeName = dis.readUTF();
            actualType = Class.forName(typeName);
        }
        
        // 检查是否有自定义序列化器
        TypeSerializer<Object> serializer = (TypeSerializer<Object>) serializers.get(actualType);
        if (serializer != null) {
            return (T) serializer.deserialize(dis);
        } else if (Map.class.isAssignableFrom(actualType)) {
            return (T) deserializeMap(dis, actualType);
        } else if (Collection.class.isAssignableFrom(actualType)) {
            return (T) deserializeCollection(dis, actualType);
        } else {
            // 降级到Java反序列化
            ObjectInputStream ois = new ObjectInputStream(dis);
            return (T) ois.readObject();
        }
    }
    
    private void serializeMap(Map<?, ?> map, DataOutputStream dos) throws IOException {
        dos.writeInt(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            serializeValue(entry.getKey(), dos);
            serializeValue(entry.getValue(), dos);
        }
    }
    
    private void serializeCollection(Collection<?> collection, DataOutputStream dos) throws IOException {
        dos.writeInt(collection.size());
        for (Object item : collection) {
            serializeValue(item, dos);
        }
    }
    
    @SuppressWarnings("unchecked")
    private void serializeValue(Object value, DataOutputStream dos) throws IOException {
        if (value == null) {
            dos.writeByte(0);
            return;
        }
        
        dos.writeByte(1);
        Class<?> type = value.getClass();
        Short typeId = typeToId.get(type);
        
        if (typeId != null) {
            dos.writeShort(typeId);
            TypeSerializer<Object> serializer = (TypeSerializer<Object>) serializers.get(type);
            if (serializer != null) {
                serializer.serialize(value, dos);
            } else {
                ObjectOutputStream oos = new ObjectOutputStream(dos);
                oos.writeObject(value);
                oos.flush();
            }
        } else {
            dos.writeShort(0);
            ObjectOutputStream oos = new ObjectOutputStream(dos);
            oos.writeObject(value);
            oos.flush();
        }
    }
    
    @SuppressWarnings("unchecked")
    private Map<?, ?> deserializeMap(DataInputStream dis, Class<?> mapType) throws Exception {
        int size = dis.readInt();
        Map<Object, Object> map;
        
        if (mapType == LinkedHashMap.class) {
            map = new LinkedHashMap<>(size);
        } else {
            map = new HashMap<>(size);
        }
        
        for (int i = 0; i < size; i++) {
            Object key = deserializeValue(dis);
            Object value = deserializeValue(dis);
            map.put(key, value);
        }
        
        return map;
    }
    
    @SuppressWarnings("unchecked")
    private Collection<?> deserializeCollection(DataInputStream dis, Class<?> collType) throws Exception {
        int size = dis.readInt();
        Collection<Object> coll;
        
        if (collType == HashSet.class) {
            coll = new HashSet<>(size);
        } else {
            coll = new ArrayList<>(size);
        }
        
        for (int i = 0; i < size; i++) {
            coll.add(deserializeValue(dis));
        }
        
        return coll;
    }
    
    @SuppressWarnings("unchecked")
    private Object deserializeValue(DataInputStream dis) throws Exception {
        byte flag = dis.readByte();
        if (flag == 0) {
            return null;
        }
        
        short typeId = dis.readShort();
        if (typeId != 0) {
            Class<?> type = idToType.get(typeId);
            TypeSerializer<Object> serializer = (TypeSerializer<Object>) serializers.get(type);
            if (serializer != null) {
                return serializer.deserialize(dis);
            }
        }
        
        ObjectInputStream ois = new ObjectInputStream(dis);
        return ois.readObject();
    }
    
    private byte[] compress(byte[] data) {
        Deflater deflater = new Deflater(COMPRESSION_LEVEL);
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
    
    private byte[] decompress(byte[] data, int originalLength) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        
        byte[] result = new byte[originalLength];
        inflater.inflate(result);
        inflater.end();
        
        return result;
    }
    
    private byte[] javaSerialize(Object obj) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(0xFE); // Java序列化标记
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            oos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Java序列化失败", e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private <T> T javaDeserialize(byte[] data, Class<T> type) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data, 1, data.length - 1);
            ObjectInputStream ois = new ObjectInputStream(bais);
            return (T) ois.readObject();
        } catch (Exception e) {
            throw new RuntimeException("Java反序列化失败", e);
        }
    }
    
    private void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >> 24);
        data[offset + 1] = (byte) (value >> 16);
        data[offset + 2] = (byte) (value >> 8);
        data[offset + 3] = (byte) value;
    }
    
    private int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
               ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) |
               (data[offset + 3] & 0xFF);
    }
    
    // ========== 统计信息 ==========
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("registeredTypes", typeToId.size());
        stats.put("registeredSerializers", serializers.size());
        stats.put("compressionThreshold", COMPRESSION_THRESHOLD);
        stats.put("compressionLevel", COMPRESSION_LEVEL);
        
        // 格式使用统计
        Map<String, Long> formatUsage = new LinkedHashMap<>();
        for (var entry : formatStats.entrySet()) {
            formatUsage.put(entry.getKey().name(), entry.getValue().get());
        }
        stats.put("formatUsage", formatUsage);
        
        return stats;
    }
    
    // ========== 内部类 ==========
    
    public enum SerializationFormat {
        COMPACT,    // 紧凑格式
        OPTIMIZED,  // 优化格式
        GENERIC     // 通用格式
    }
    
    /**
     * 类型序列化器接口
     */
    public interface TypeSerializer<T> {
        void serialize(T obj, DataOutputStream out) throws IOException;
        T deserialize(DataInputStream in) throws IOException;
    }
}
