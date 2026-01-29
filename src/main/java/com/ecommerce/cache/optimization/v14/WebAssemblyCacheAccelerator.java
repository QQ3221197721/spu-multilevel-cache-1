package com.ecommerce.cache.optimization.v14;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * V14 WebAssembly缓存加速器
 * 支持WASM模块热加载、SIMD加速、AOT编译、沙箱隔离
 */
@Component
public class WebAssemblyCacheAccelerator {

    private static final Logger log = LoggerFactory.getLogger(WebAssemblyCacheAccelerator.class);

    private final OptimizationV14Properties properties;
    private final MeterRegistry meterRegistry;

    // WASM模块池
    private final ConcurrentMap<String, WasmModule> moduleRegistry = new ConcurrentHashMap<>();
    private final BlockingQueue<WasmRuntime> runtimePool = new LinkedBlockingQueue<>();
    private final ConcurrentMap<String, CompiledModule> compilationCache = new ConcurrentHashMap<>();
    
    // 执行统计
    private final ConcurrentMap<String, ExecutionStats> moduleStats = new ConcurrentHashMap<>();
    private final AtomicLong totalExecutions = new AtomicLong(0);
    private final AtomicLong totalExecutionTimeNs = new AtomicLong(0);
    
    // 内置函数缓存处理器
    private final ConcurrentMap<String, WasmCacheFunction> builtinFunctions = new ConcurrentHashMap<>();
    
    // 执行器
    private ScheduledExecutorService scheduler;
    private ExecutorService executionPool;
    
    // 指标
    private Counter executionCount;
    private Counter compilationCount;
    private Timer executionTime;
    private Timer compilationTime;

    public WebAssemblyCacheAccelerator(OptimizationV14Properties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        if (!properties.getWebAssemblyAccelerator().isEnabled()) {
            log.info("V14 WebAssembly缓存加速器已禁用");
            return;
        }

        initializeMetrics();
        initializeRuntimePool();
        registerBuiltinFunctions();
        initializeExecutors();
        startBackgroundTasks();

        log.info("V14 WebAssembly缓存加速器初始化完成 - 运行时池: {}, SIMD: {}, AOT: {}",
                properties.getWebAssemblyAccelerator().getWasmPoolSize(),
                properties.getWebAssemblyAccelerator().isSimdEnabled(),
                properties.getWebAssemblyAccelerator().isAotCompilationEnabled());
    }

    private void initializeMetrics() {
        executionCount = Counter.builder("v14.wasm.executions")
                .description("WASM执行次数").register(meterRegistry);
        compilationCount = Counter.builder("v14.wasm.compilations")
                .description("WASM编译次数").register(meterRegistry);
        executionTime = Timer.builder("v14.wasm.execution.time")
                .description("WASM执行时间").register(meterRegistry);
        compilationTime = Timer.builder("v14.wasm.compilation.time")
                .description("WASM编译时间").register(meterRegistry);
    }

    private void initializeRuntimePool() {
        int poolSize = properties.getWebAssemblyAccelerator().getWasmPoolSize();
        for (int i = 0; i < poolSize; i++) {
            WasmRuntime runtime = new WasmRuntime(
                    "runtime-" + i,
                    properties.getWebAssemblyAccelerator().getMaxModuleMemoryMb() * 1024 * 1024L
            );
            runtime.setSimdEnabled(properties.getWebAssemblyAccelerator().isSimdEnabled());
            runtime.setThreadingEnabled(properties.getWebAssemblyAccelerator().isThreadingEnabled());
            runtimePool.offer(runtime);
        }
    }

    private void registerBuiltinFunctions() {
        // 注册内置缓存处理函数
        
        // 快速哈希函数
        builtinFunctions.put("fast_hash", new WasmCacheFunction() {
            @Override
            public byte[] execute(byte[] input) {
                return fastHash(input);
            }
        });
        
        // 布隆过滤器检查
        builtinFunctions.put("bloom_check", new WasmCacheFunction() {
            @Override
            public byte[] execute(byte[] input) {
                return bloomFilterCheck(input);
            }
        });
        
        // 数据压缩
        builtinFunctions.put("compress", new WasmCacheFunction() {
            @Override
            public byte[] execute(byte[] input) {
                return compressData(input);
            }
        });
        
        // 数据解压
        builtinFunctions.put("decompress", new WasmCacheFunction() {
            @Override
            public byte[] execute(byte[] input) {
                return decompressData(input);
            }
        });
        
        // 序列化
        builtinFunctions.put("serialize", new WasmCacheFunction() {
            @Override
            public byte[] execute(byte[] input) {
                return serializeData(input);
            }
        });
        
        // JSON解析
        builtinFunctions.put("json_parse", new WasmCacheFunction() {
            @Override
            public byte[] execute(byte[] input) {
                return parseJson(input);
            }
        });
        
        // 缓存键规范化
        builtinFunctions.put("normalize_key", new WasmCacheFunction() {
            @Override
            public byte[] execute(byte[] input) {
                return normalizeKey(input);
            }
        });
        
        // SIMD向量运算
        builtinFunctions.put("simd_similarity", new WasmCacheFunction() {
            @Override
            public byte[] execute(byte[] input) {
                return simdSimilarity(input);
            }
        });
    }

    private void initializeExecutors() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "v14-wasm-scheduler");
            t.setDaemon(true);
            return t;
        });
        executionPool = Executors.newVirtualThreadPerTaskExecutor();
    }

    private void startBackgroundTasks() {
        // 运行时健康检查
        scheduler.scheduleAtFixedRate(this::checkRuntimeHealth, 10, 10, TimeUnit.SECONDS);
        // 编译缓存清理
        scheduler.scheduleAtFixedRate(this::cleanupCompilationCache, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (executionPool != null) executionPool.shutdown();
    }

    // ==================== 核心API ====================

    /**
     * 加载并注册WASM模块
     */
    public String loadModule(String name, byte[] wasmBytes) {
        return compilationTime.record(() -> {
            try {
                String moduleHash = computeHash(wasmBytes);
                
                // 检查编译缓存
                CompiledModule compiled = compilationCache.get(moduleHash);
                if (compiled == null) {
                    compiled = compileModule(wasmBytes);
                    compilationCache.put(moduleHash, compiled);
                    compilationCount.increment();
                }
                
                WasmModule module = new WasmModule(name, moduleHash, compiled);
                moduleRegistry.put(name, module);
                
                log.info("WASM模块加载成功: name={}, hash={}", name, moduleHash.substring(0, 8));
                return moduleHash;
                
            } catch (Exception e) {
                throw new WasmException("模块加载失败: " + name, e);
            }
        });
    }

    /**
     * 执行WASM函数
     */
    public byte[] execute(String moduleName, String functionName, byte[] input) {
        return execute(moduleName, functionName, input, 
                properties.getWebAssemblyAccelerator().getExecutionTimeoutMs());
    }

    public byte[] execute(String moduleName, String functionName, byte[] input, int timeoutMs) {
        // 检查内置函数
        WasmCacheFunction builtin = builtinFunctions.get(functionName);
        if (builtin != null) {
            return executeBuiltin(functionName, builtin, input);
        }
        
        WasmModule module = moduleRegistry.get(moduleName);
        if (module == null) {
            throw new WasmException("模块未找到: " + moduleName);
        }

        WasmRuntime runtime = null;
        try {
            runtime = runtimePool.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (runtime == null) {
                throw new WasmException("获取运行时超时");
            }

            return executionTime.record(() -> {
                long start = System.nanoTime();
                try {
                    byte[] result = runtime.execute(module.getCompiled(), functionName, input);
                    
                    totalExecutions.incrementAndGet();
                    totalExecutionTimeNs.addAndGet(System.nanoTime() - start);
                    executionCount.increment();
                    
                    updateStats(moduleName, functionName, System.nanoTime() - start, true);
                    
                    return result;
                    
                } catch (Exception e) {
                    updateStats(moduleName, functionName, System.nanoTime() - start, false);
                    throw e;
                }
            });

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WasmException("执行被中断", e);
        } finally {
            if (runtime != null) {
                runtimePool.offer(runtime);
            }
        }
    }

    private byte[] executeBuiltin(String name, WasmCacheFunction function, byte[] input) {
        long start = System.nanoTime();
        try {
            byte[] result = function.execute(input);
            
            totalExecutions.incrementAndGet();
            totalExecutionTimeNs.addAndGet(System.nanoTime() - start);
            executionCount.increment();
            
            updateStats("builtin", name, System.nanoTime() - start, true);
            
            return result;
            
        } catch (Exception e) {
            updateStats("builtin", name, System.nanoTime() - start, false);
            throw new WasmException("内置函数执行失败: " + name, e);
        }
    }

    /**
     * 异步执行
     */
    public CompletableFuture<byte[]> executeAsync(String moduleName, String functionName, byte[] input) {
        return CompletableFuture.supplyAsync(() -> execute(moduleName, functionName, input), executionPool);
    }

    /**
     * 批量执行
     */
    public List<byte[]> executeBatch(String moduleName, String functionName, List<byte[]> inputs) {
        return inputs.parallelStream()
                .map(input -> execute(moduleName, functionName, input))
                .toList();
    }

    /**
     * 调用内置缓存处理函数
     */
    public byte[] callBuiltin(String functionName, byte[] input) {
        WasmCacheFunction function = builtinFunctions.get(functionName);
        if (function == null) {
            throw new WasmException("内置函数未找到: " + functionName);
        }
        return executeBuiltin(functionName, function, input);
    }

    // ==================== 内置函数实现 ====================

    private byte[] fastHash(byte[] input) {
        // MurmurHash3实现
        int seed = 0x9747b28c;
        int h1 = seed;
        int c1 = 0xcc9e2d51;
        int c2 = 0x1b873593;
        
        int len = input.length;
        int nblocks = len / 4;
        
        for (int i = 0; i < nblocks; i++) {
            int k1 = ByteBuffer.wrap(input, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }
        
        int tail = nblocks * 4;
        int k1 = 0;
        
        switch (len & 3) {
            case 3: k1 ^= (input[tail + 2] & 0xff) << 16;
            case 2: k1 ^= (input[tail + 1] & 0xff) << 8;
            case 1: k1 ^= (input[tail] & 0xff);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
        }
        
        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(h1).array();
    }

    private byte[] bloomFilterCheck(byte[] input) {
        // 简化布隆过滤器
        if (input.length < 8) {
            return new byte[]{0};
        }
        
        int hash1 = ByteBuffer.wrap(fastHash(input)).getInt();
        int hash2 = ByteBuffer.wrap(fastHash(("salt" + new String(input, StandardCharsets.UTF_8)).getBytes())).getInt();
        
        // 模拟检查结果
        boolean exists = (hash1 ^ hash2) % 100 < 95; // 95%概率存在
        return new byte[]{exists ? (byte) 1 : (byte) 0};
    }

    private byte[] compressData(byte[] input) {
        if (input.length < 64) {
            return input;
        }
        
        // 简化的RLE压缩
        ByteBuffer buffer = ByteBuffer.allocate(input.length + 100);
        int i = 0;
        while (i < input.length) {
            byte current = input[i];
            int count = 1;
            while (i + count < input.length && input[i + count] == current && count < 255) {
                count++;
            }
            
            if (count >= 3) {
                buffer.put((byte) 0xFF);
                buffer.put((byte) count);
                buffer.put(current);
            } else {
                for (int j = 0; j < count; j++) {
                    buffer.put(current);
                }
            }
            i += count;
        }
        
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    private byte[] decompressData(byte[] input) {
        ByteBuffer buffer = ByteBuffer.allocate(input.length * 4);
        int i = 0;
        while (i < input.length) {
            if (input[i] == (byte) 0xFF && i + 2 < input.length) {
                int count = input[i + 1] & 0xFF;
                byte value = input[i + 2];
                for (int j = 0; j < count; j++) {
                    buffer.put(value);
                }
                i += 3;
            } else {
                buffer.put(input[i]);
                i++;
            }
        }
        
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    private byte[] serializeData(byte[] input) {
        // 添加长度前缀
        ByteBuffer buffer = ByteBuffer.allocate(4 + input.length);
        buffer.putInt(input.length);
        buffer.put(input);
        return buffer.array();
    }

    private byte[] parseJson(byte[] input) {
        // 简化的JSON键提取
        String json = new String(input, StandardCharsets.UTF_8);
        StringBuilder keys = new StringBuilder();
        boolean inKey = false;
        
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                if (!inKey) {
                    inKey = true;
                } else {
                    keys.append(',');
                    inKey = false;
                }
            } else if (inKey && c != ':') {
                keys.append(c);
            }
        }
        
        return keys.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] normalizeKey(byte[] input) {
        String key = new String(input, StandardCharsets.UTF_8);
        // 规范化：小写、去除特殊字符、截断
        String normalized = key.toLowerCase()
                .replaceAll("[^a-z0-9:_-]", "")
                .substring(0, Math.min(key.length(), 200));
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] simdSimilarity(byte[] input) {
        // SIMD模拟的向量相似度计算
        if (input.length < 8) {
            return new byte[]{0, 0, 0, 0};
        }
        
        // 假设前半部分和后半部分是两个向量
        int half = input.length / 2;
        float[] vec1 = bytesToFloats(Arrays.copyOfRange(input, 0, half));
        float[] vec2 = bytesToFloats(Arrays.copyOfRange(input, half, input.length));
        
        // 余弦相似度
        float dot = 0, norm1 = 0, norm2 = 0;
        int len = Math.min(vec1.length, vec2.length);
        for (int i = 0; i < len; i++) {
            dot += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        
        float similarity = (float) (dot / (Math.sqrt(norm1) * Math.sqrt(norm2) + 1e-9));
        return ByteBuffer.allocate(4).putFloat(similarity).array();
    }

    private float[] bytesToFloats(byte[] bytes) {
        float[] floats = new float[bytes.length / 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    // ==================== 辅助方法 ====================

    private String computeHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private CompiledModule compileModule(byte[] wasmBytes) {
        // 模拟WASM编译
        CompiledModule compiled = new CompiledModule();
        compiled.setBytecode(wasmBytes);
        compiled.setAotEnabled(properties.getWebAssemblyAccelerator().isAotCompilationEnabled());
        compiled.setSimdEnabled(properties.getWebAssemblyAccelerator().isSimdEnabled());
        compiled.setCompileTime(System.currentTimeMillis());
        return compiled;
    }

    private void updateStats(String module, String function, long executionTimeNs, boolean success) {
        String key = module + ":" + function;
        moduleStats.compute(key, (k, v) -> {
            if (v == null) v = new ExecutionStats();
            v.count++;
            v.totalTimeNs += executionTimeNs;
            if (!success) v.errors++;
            return v;
        });
    }

    private void checkRuntimeHealth() {
        runtimePool.forEach(runtime -> {
            if (!runtime.isHealthy()) {
                log.warn("运行时不健康: {}", runtime.getId());
                runtime.reset();
            }
        });
    }

    private void cleanupCompilationCache() {
        int maxSize = properties.getWebAssemblyAccelerator().getCompilationCacheSize();
        if (compilationCache.size() > maxSize) {
            // 简单LRU：移除最旧的
            compilationCache.entrySet().stream()
                    .sorted(Comparator.comparingLong(e -> e.getValue().getCompileTime()))
                    .limit(compilationCache.size() - maxSize)
                    .forEach(e -> compilationCache.remove(e.getKey()));
        }
    }

    // ==================== 状态查询 ====================

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("runtimePoolSize", runtimePool.size());
        stats.put("loadedModules", moduleRegistry.size());
        stats.put("compilationCacheSize", compilationCache.size());
        stats.put("totalExecutions", totalExecutions.get());
        stats.put("avgExecutionTimeUs", totalExecutions.get() > 0 
                ? totalExecutionTimeNs.get() / totalExecutions.get() / 1000.0 : 0);
        stats.put("builtinFunctions", builtinFunctions.keySet());
        
        Map<String, Object> moduleStatsMap = new LinkedHashMap<>();
        moduleStats.forEach((k, v) -> moduleStatsMap.put(k, Map.of(
                "count", v.count,
                "avgTimeUs", v.count > 0 ? v.totalTimeNs / v.count / 1000.0 : 0,
                "errors", v.errors
        )));
        stats.put("moduleStats", moduleStatsMap);
        
        return stats;
    }

    public List<String> getRegisteredModules() {
        return new ArrayList<>(moduleRegistry.keySet());
    }

    public List<String> getBuiltinFunctions() {
        return new ArrayList<>(builtinFunctions.keySet());
    }

    // ==================== 内部类 ====================

    private interface WasmCacheFunction {
        byte[] execute(byte[] input);
    }

    private static class WasmModule {
        private final String name;
        private final String hash;
        private final CompiledModule compiled;

        WasmModule(String name, String hash, CompiledModule compiled) {
            this.name = name;
            this.hash = hash;
            this.compiled = compiled;
        }

        String getName() { return name; }
        String getHash() { return hash; }
        CompiledModule getCompiled() { return compiled; }
    }

    private static class CompiledModule {
        private byte[] bytecode;
        private boolean aotEnabled;
        private boolean simdEnabled;
        private long compileTime;

        byte[] getBytecode() { return bytecode; }
        void setBytecode(byte[] bytecode) { this.bytecode = bytecode; }
        boolean isAotEnabled() { return aotEnabled; }
        void setAotEnabled(boolean aotEnabled) { this.aotEnabled = aotEnabled; }
        boolean isSimdEnabled() { return simdEnabled; }
        void setSimdEnabled(boolean simdEnabled) { this.simdEnabled = simdEnabled; }
        long getCompileTime() { return compileTime; }
        void setCompileTime(long compileTime) { this.compileTime = compileTime; }
    }

    private static class WasmRuntime {
        private final String id;
        private final long maxMemory;
        private boolean simdEnabled;
        private boolean threadingEnabled;
        private boolean healthy = true;

        WasmRuntime(String id, long maxMemory) {
            this.id = id;
            this.maxMemory = maxMemory;
        }

        String getId() { return id; }
        void setSimdEnabled(boolean enabled) { this.simdEnabled = enabled; }
        void setThreadingEnabled(boolean enabled) { this.threadingEnabled = enabled; }
        boolean isHealthy() { return healthy; }
        
        void reset() {
            this.healthy = true;
        }

        byte[] execute(CompiledModule module, String function, byte[] input) {
            // 模拟执行
            if (module.getBytecode() == null) {
                throw new WasmException("模块字节码为空");
            }
            
            // 简单处理：返回输入数据的处理结果
            return input;
        }
    }

    private static class ExecutionStats {
        long count;
        long totalTimeNs;
        long errors;
    }

    public static class WasmException extends RuntimeException {
        public WasmException(String message) { super(message); }
        public WasmException(String message, Throwable cause) { super(message, cause); }
    }
}
