package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.*;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * GraalVM 原生镜像优化器
 * 
 * 核心功能：
 * 1. AOT 编译支持 - 预编译热点代码路径
 * 2. 反射元数据注册 - 运行时反射支持
 * 3. JIT 预热管理 - 智能预热关键方法
 * 4. 方法句柄缓存 - 高性能方法调用
 * 5. 内联缓存优化 - 减少虚方法调用开销
 * 6. 启动时间优化 - 懒加载与按需初始化
 * 
 * 性能目标：
 * - 启动时间: <500ms (原生镜像)
 * - 内存占用: 减少 60%
 * - 首次调用延迟: <1ms
 * 
 * @author optimization-team
 * @version 2.0
 */
@Service
@ImportRuntimeHints(GraalVMOptimizer.CacheRuntimeHints.class)
public class GraalVMOptimizer {
    
    private static final Logger log = LoggerFactory.getLogger(GraalVMOptimizer.class);
    
    // ==================== 配置常量 ====================
    private static final int METHOD_HANDLE_CACHE_SIZE = 1024;
    private static final int WARMUP_ITERATIONS = 10000;
    private static final long WARMUP_DELAY_MS = 100;
    
    // ==================== 核心组件 ====================
    private final MeterRegistry meterRegistry;
    private final ExecutorService warmupExecutor;
    
    // ==================== 方法句柄缓存 ====================
    private final ConcurrentHashMap<String, MethodHandle> methodHandleCache;
    private final ConcurrentHashMap<String, Function<Object[], Object>> compiledLambdas;
    
    // ==================== 预编译代码路径 ====================
    private final ConcurrentHashMap<String, CompiledCodePath> compiledPaths;
    private final Set<String> hotPaths;
    
    // ==================== 内联缓存 ====================
    private final InlineCache inlineCache;
    
    // ==================== 性能指标 ====================
    private final Timer methodInvocationTimer;
    private final LongAdder cacheHits;
    private final LongAdder cacheMisses;
    private final AtomicLong warmupProgress;
    
    // ==================== 状态管理 ====================
    private volatile boolean warmedUp = false;
    private volatile boolean nativeImageMode = false;
    
    public GraalVMOptimizer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.warmupExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        this.methodHandleCache = new ConcurrentHashMap<>(METHOD_HANDLE_CACHE_SIZE);
        this.compiledLambdas = new ConcurrentHashMap<>(256);
        this.compiledPaths = new ConcurrentHashMap<>(128);
        this.hotPaths = ConcurrentHashMap.newKeySet();
        this.inlineCache = new InlineCache(512);
        
        this.methodInvocationTimer = Timer.builder("graalvm.method.invocation")
            .description("Method invocation latency")
            .register(meterRegistry);
        this.cacheHits = new LongAdder();
        this.cacheMisses = new LongAdder();
        this.warmupProgress = new AtomicLong(0);
        
        // 检测是否运行在 GraalVM 原生镜像中
        this.nativeImageMode = detectNativeImageMode();
    }
    
    @PostConstruct
    public void initialize() {
        log.info("Initializing GraalVM Optimizer...");
        log.info("Native Image Mode: {}", nativeImageMode);
        
        // 注册核心类的运行时提示
        registerCoreRuntimeHints();
        
        // 预编译关键代码路径
        precompileCriticalPaths();
        
        // 异步预热
        if (!nativeImageMode) {
            scheduleWarmup();
        }
        
        log.info("GraalVM Optimizer initialized");
    }
    
    @PreDestroy
    public void shutdown() {
        warmupExecutor.shutdown();
        try {
            if (!warmupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                warmupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // ==================== 核心 API ====================
    
    /**
     * 高性能方法调用 - 使用方法句柄
     */
    @SuppressWarnings("unchecked")
    public <T> T invokeMethod(Object target, String methodName, Class<T> returnType, Object... args) {
        String cacheKey = buildMethodKey(target.getClass(), methodName, args);
        
        return methodInvocationTimer.record(() -> {
            MethodHandle handle = methodHandleCache.computeIfAbsent(cacheKey, k -> {
                cacheMisses.increment();
                return createMethodHandle(target.getClass(), methodName, args);
            });
            
            if (handle != null) {
                cacheHits.increment();
                try {
                    Object[] fullArgs = new Object[args.length + 1];
                    fullArgs[0] = target;
                    System.arraycopy(args, 0, fullArgs, 1, args.length);
                    return (T) handle.invokeWithArguments(fullArgs);
                } catch (Throwable e) {
                    log.error("Method invocation failed: {}.{}", target.getClass().getSimpleName(), methodName, e);
                    return null;
                }
            }
            return null;
        });
    }
    
    /**
     * 编译并缓存 Lambda 表达式
     */
    public <R> R executeCompiledLambda(String key, Supplier<R> supplier) {
        @SuppressWarnings("unchecked")
        Function<Object[], Object> compiled = compiledLambdas.computeIfAbsent(key, k -> 
            args -> supplier.get()
        );
        
        @SuppressWarnings("unchecked")
        R result = (R) compiled.apply(new Object[0]);
        return result;
    }
    
    /**
     * 注册热点代码路径
     */
    public void registerHotPath(String pathId, Runnable codeBlock) {
        hotPaths.add(pathId);
        compiledPaths.computeIfAbsent(pathId, k -> new CompiledCodePath(pathId, codeBlock));
    }
    
    /**
     * 执行预编译代码路径
     */
    public void executeHotPath(String pathId) {
        CompiledCodePath path = compiledPaths.get(pathId);
        if (path != null) {
            path.execute();
        }
    }
    
    /**
     * 内联缓存查找
     */
    public Object inlineCacheLookup(Object receiver, String selector) {
        return inlineCache.lookup(receiver.getClass(), selector);
    }
    
    /**
     * 更新内联缓存
     */
    public void updateInlineCache(Object receiver, String selector, Object value) {
        inlineCache.update(receiver.getClass(), selector, value);
    }
    
    // ==================== JIT 预热 ====================
    
    /**
     * 执行 JIT 预热
     */
    public void warmup() {
        if (warmedUp) {
            return;
        }
        
        log.info("Starting JIT warmup...");
        long startTime = System.currentTimeMillis();
        
        // 预热核心方法
        warmupCoreMethods();
        
        // 预热热点代码路径
        warmupHotPaths();
        
        // 触发编译
        triggerCompilation();
        
        warmedUp = true;
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("JIT warmup completed in {}ms", elapsed);
    }
    
    private void scheduleWarmup() {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(WARMUP_DELAY_MS);
                warmup();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, warmupExecutor);
    }
    
    private void warmupCoreMethods() {
        // 预热字符串操作
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            String key = "warmup:key:" + i;
            key.hashCode();
            key.length();
            key.substring(0, Math.min(10, key.length()));
            warmupProgress.incrementAndGet();
        }
        
        // 预热集合操作
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
            map.put("key" + i, "value" + i);
            map.get("key" + i);
            warmupProgress.incrementAndGet();
        }
    }
    
    private void warmupHotPaths() {
        for (CompiledCodePath path : compiledPaths.values()) {
            for (int i = 0; i < 100; i++) {
                path.execute();
            }
        }
    }
    
    private void triggerCompilation() {
        // 强制 GC 触发编译
        System.gc();
        
        // 休眠让编译器工作
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // ==================== 辅助方法 ====================
    
    private boolean detectNativeImageMode() {
        try {
            String vmName = System.getProperty("java.vm.name", "");
            return vmName.toLowerCase().contains("substrate") || 
                   vmName.toLowerCase().contains("graalvm");
        } catch (Exception e) {
            return false;
        }
    }
    
    private void registerCoreRuntimeHints() {
        // 在 AOT 编译时由 CacheRuntimeHints 处理
    }
    
    private void precompileCriticalPaths() {
        // 注册默认热点路径
        registerHotPath("cache.get", () -> {
            // 缓存读取路径的预编译占位
        });
        
        registerHotPath("cache.put", () -> {
            // 缓存写入路径的预编译占位
        });
        
        registerHotPath("hash.compute", () -> {
            // 哈希计算路径的预编译占位
        });
    }
    
    private String buildMethodKey(Class<?> clazz, String methodName, Object[] args) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(clazz.getName()).append('#').append(methodName);
        for (Object arg : args) {
            sb.append(':').append(arg != null ? arg.getClass().getSimpleName() : "null");
        }
        return sb.toString();
    }
    
    private MethodHandle createMethodHandle(Class<?> clazz, String methodName, Object[] args) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            
            // 查找匹配的方法
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(methodName) && 
                    method.getParameterCount() == args.length) {
                    
                    MethodType type = MethodType.methodType(
                        method.getReturnType(), 
                        method.getParameterTypes()
                    );
                    
                    return lookup.findVirtual(clazz, methodName, type);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to create method handle for {}.{}", clazz.getSimpleName(), methodName);
        }
        return null;
    }
    
    // ==================== 统计信息 ====================
    
    public GraalVMStats getStats() {
        return new GraalVMStats(
            nativeImageMode,
            warmedUp,
            methodHandleCache.size(),
            compiledLambdas.size(),
            compiledPaths.size(),
            cacheHits.sum(),
            cacheMisses.sum(),
            warmupProgress.get(),
            inlineCache.size(),
            methodInvocationTimer.mean(TimeUnit.MICROSECONDS)
        );
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 预编译代码路径
     */
    private static class CompiledCodePath {
        private final String id;
        private final Runnable codeBlock;
        private final LongAdder executionCount;
        
        CompiledCodePath(String id, Runnable codeBlock) {
            this.id = id;
            this.codeBlock = codeBlock;
            this.executionCount = new LongAdder();
        }
        
        void execute() {
            codeBlock.run();
            executionCount.increment();
        }
        
        long getExecutionCount() {
            return executionCount.sum();
        }
    }
    
    /**
     * 内联缓存实现
     */
    private static class InlineCache {
        private final ConcurrentHashMap<String, Object> cache;
        private final int maxSize;
        
        InlineCache(int maxSize) {
            this.maxSize = maxSize;
            this.cache = new ConcurrentHashMap<>(maxSize);
        }
        
        Object lookup(Class<?> receiverClass, String selector) {
            String key = receiverClass.getName() + "#" + selector;
            return cache.get(key);
        }
        
        void update(Class<?> receiverClass, String selector, Object value) {
            if (cache.size() < maxSize) {
                String key = receiverClass.getName() + "#" + selector;
                cache.put(key, value);
            }
        }
        
        int size() {
            return cache.size();
        }
    }
    
    /**
     * GraalVM 运行时提示注册器
     */
    static class CacheRuntimeHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // 注册需要反射的类
            hints.reflection()
                .registerType(HashMap.class, MemberCategory.values())
                .registerType(ConcurrentHashMap.class, MemberCategory.values())
                .registerType(ArrayList.class, MemberCategory.values());
            
            // 注册序列化类
            hints.serialization()
                .registerType(HashMap.class)
                .registerType(ArrayList.class);
            
            // 注册资源文件
            hints.resources()
                .registerPattern("application*.yml")
                .registerPattern("META-INF/*");
        }
    }
    
    /**
     * 统计信息记录
     */
    public record GraalVMStats(
        boolean nativeImageMode,
        boolean warmedUp,
        int methodHandleCacheSize,
        int compiledLambdasCount,
        int compiledPathsCount,
        long cacheHits,
        long cacheMisses,
        long warmupProgress,
        int inlineCacheSize,
        double avgInvocationMicros
    ) {
        public double getHitRate() {
            long total = cacheHits + cacheMisses;
            return total > 0 ? (double) cacheHits / total : 0;
        }
    }
}
