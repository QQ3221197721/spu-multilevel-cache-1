package com.ecommerce.cache.optimization.v14;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * V14 Serverless缓存引擎
 * 支持弹性伸缩、冷启动优化、预置并发、按需付费模式
 */
@Component
public class ServerlessCacheEngine {

    private static final Logger log = LoggerFactory.getLogger(ServerlessCacheEngine.class);

    private final OptimizationV14Properties properties;
    private final MeterRegistry meterRegistry;

    // 函数实例池
    private final ConcurrentMap<String, FunctionDefinition> functionRegistry = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BlockingQueue<FunctionInstance>> warmPools = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> activeInvocations = new ConcurrentHashMap<>();
    
    // 伸缩状态
    private final ConcurrentMap<String, ScalingState> scalingStates = new ConcurrentHashMap<>();
    private final AtomicInteger totalInstances = new AtomicInteger(0);
    
    // 执行统计
    private final ConcurrentMap<String, FunctionMetrics> functionMetrics = new ConcurrentHashMap<>();
    private final AtomicLong totalInvocations = new AtomicLong(0);
    private final AtomicLong coldStarts = new AtomicLong(0);
    
    // 计费统计
    private final AtomicLong totalComputeTimeMs = new AtomicLong(0);
    private final AtomicLong totalMemoryMbMs = new AtomicLong(0);
    
    // 执行器
    private ScheduledExecutorService scheduler;
    private ExecutorService invocationExecutor;
    
    // 指标
    private Counter invocationCounter;
    private Counter coldStartCounter;
    private Timer invocationLatency;
    private Timer coldStartLatency;

    public ServerlessCacheEngine(OptimizationV14Properties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        if (!properties.getServerlessEngine().isEnabled()) {
            log.info("V14 Serverless缓存引擎已禁用");
            return;
        }

        initializeMetrics();
        initializeExecutors();
        registerBuiltinFunctions();
        startBackgroundTasks();

        log.info("V14 Serverless缓存引擎初始化完成 - 预热池: {}, 最大实例: {}",
                properties.getServerlessEngine().getWarmPoolSize(),
                properties.getServerlessEngine().getMaxInstances());
    }

    private void initializeMetrics() {
        invocationCounter = Counter.builder("v14.serverless.invocations")
                .description("函数调用次数").register(meterRegistry);
        coldStartCounter = Counter.builder("v14.serverless.cold_starts")
                .description("冷启动次数").register(meterRegistry);
        invocationLatency = Timer.builder("v14.serverless.invocation.latency")
                .description("调用延迟").register(meterRegistry);
        coldStartLatency = Timer.builder("v14.serverless.cold_start.latency")
                .description("冷启动延迟").register(meterRegistry);
        
        Gauge.builder("v14.serverless.instances", totalInstances, AtomicInteger::get)
                .description("活跃实例数").register(meterRegistry);
    }

    private void initializeExecutors() {
        scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "v14-serverless-scheduler");
            t.setDaemon(true);
            return t;
        });
        invocationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private void registerBuiltinFunctions() {
        // 缓存读取函数
        registerFunction("cache_get", CacheGetFunction::new, 128);
        
        // 缓存写入函数
        registerFunction("cache_put", CachePutFunction::new, 128);
        
        // 缓存删除函数
        registerFunction("cache_delete", CacheDeleteFunction::new, 64);
        
        // 批量读取函数
        registerFunction("cache_mget", CacheMGetFunction::new, 256);
        
        // 缓存计算函数
        registerFunction("cache_compute", CacheComputeFunction::new, 256);
        
        // 缓存过滤函数
        registerFunction("cache_filter", CacheFilterFunction::new, 256);
        
        // 缓存聚合函数
        registerFunction("cache_aggregate", CacheAggregateFunction::new, 512);
        
        // 预热预置并发
        if (properties.getServerlessEngine().isKeepWarmEnabled()) {
            prewarmProvisionedConcurrency();
        }
    }

    private void startBackgroundTasks() {
        // 伸缩决策
        scheduler.scheduleAtFixedRate(this::makeScalingDecisions, 1, 1, TimeUnit.SECONDS);
        
        // 空闲实例清理
        scheduler.scheduleAtFixedRate(this::cleanupIdleInstances, 30, 30, TimeUnit.SECONDS);
        
        // 指标收集
        scheduler.scheduleAtFixedRate(this::collectMetrics, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (invocationExecutor != null) invocationExecutor.shutdown();
    }

    // ==================== 核心API ====================

    /**
     * 注册函数
     */
    public void registerFunction(String name, Function<Void, CacheFunction> factory, int memoryMb) {
        FunctionDefinition def = new FunctionDefinition(name, factory, memoryMb);
        functionRegistry.put(name, def);
        warmPools.put(name, new LinkedBlockingQueue<>());
        activeInvocations.put(name, new AtomicInteger(0));
        scalingStates.put(name, new ScalingState());
        functionMetrics.put(name, new FunctionMetrics());
        
        log.info("注册Serverless函数: name={}, memory={}MB", name, memoryMb);
    }

    /**
     * 调用函数
     */
    public <T> T invoke(String functionName, Object input, Class<T> returnType) {
        return invoke(functionName, input, returnType, 
                properties.getServerlessEngine().getColdStartTimeoutMs());
    }

    @SuppressWarnings("unchecked")
    public <T> T invoke(String functionName, Object input, Class<T> returnType, int timeoutMs) {
        FunctionDefinition def = functionRegistry.get(functionName);
        if (def == null) {
            throw new ServerlessException("函数未注册: " + functionName);
        }

        // 检查并发限制
        int maxConcurrent = properties.getServerlessEngine().getMaxConcurrentInvocations();
        AtomicInteger active = activeInvocations.get(functionName);
        if (active.get() >= maxConcurrent) {
            throw new ServerlessException("达到最大并发限制: " + functionName);
        }

        active.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        try {
            return invocationLatency.record(() -> {
                FunctionInstance instance = getOrCreateInstance(functionName, def, timeoutMs);
                
                try {
                    Object result = instance.getFunction().execute(input);
                    
                    // 更新指标
                    totalInvocations.incrementAndGet();
                    invocationCounter.increment();
                    
                    long duration = System.currentTimeMillis() - startTime;
                    totalComputeTimeMs.addAndGet(duration);
                    totalMemoryMbMs.addAndGet(duration * def.getMemoryMb());
                    
                    updateFunctionMetrics(functionName, duration, true);
                    
                    return (T) result;
                    
                } finally {
                    returnInstance(functionName, instance);
                }
            });
            
        } finally {
            active.decrementAndGet();
        }
    }

    /**
     * 异步调用
     */
    public <T> CompletableFuture<T> invokeAsync(String functionName, Object input, Class<T> returnType) {
        return CompletableFuture.supplyAsync(() -> invoke(functionName, input, returnType), invocationExecutor);
    }

    /**
     * 批量调用
     */
    public <T> List<T> invokeBatch(String functionName, List<Object> inputs, Class<T> returnType) {
        return inputs.parallelStream()
                .map(input -> invoke(functionName, input, returnType))
                .toList();
    }

    /**
     * 流式处理
     */
    public <T> void invokeStream(String functionName, Iterator<Object> inputs, 
            java.util.function.Consumer<T> consumer, Class<T> returnType) {
        while (inputs.hasNext()) {
            T result = invoke(functionName, inputs.next(), returnType);
            consumer.accept(result);
        }
    }

    // ==================== 实例管理 ====================

    private FunctionInstance getOrCreateInstance(String functionName, FunctionDefinition def, int timeoutMs) {
        BlockingQueue<FunctionInstance> pool = warmPools.get(functionName);
        
        // 尝试从预热池获取
        FunctionInstance instance = pool.poll();
        if (instance != null && !instance.isExpired()) {
            instance.setLastUsed(System.currentTimeMillis());
            return instance;
        }
        
        // 冷启动
        return coldStartLatency.record(() -> {
            coldStarts.incrementAndGet();
            coldStartCounter.increment();
            
            log.debug("函数冷启动: {}", functionName);
            
            FunctionInstance newInstance = createInstance(functionName, def);
            totalInstances.incrementAndGet();
            
            updateFunctionMetrics(functionName, 0, true);
            functionMetrics.get(functionName).coldStarts++;
            
            return newInstance;
        });
    }

    private FunctionInstance createInstance(String functionName, FunctionDefinition def) {
        CacheFunction function = def.getFactory().apply(null);
        FunctionInstance instance = new FunctionInstance(
                UUID.randomUUID().toString(),
                functionName,
                function,
                def.getMemoryMb()
        );
        instance.setCreated(System.currentTimeMillis());
        instance.setLastUsed(System.currentTimeMillis());
        return instance;
    }

    private void returnInstance(String functionName, FunctionInstance instance) {
        instance.setLastUsed(System.currentTimeMillis());
        
        BlockingQueue<FunctionInstance> pool = warmPools.get(functionName);
        int warmPoolSize = properties.getServerlessEngine().getWarmPoolSize();
        
        if (pool.size() < warmPoolSize) {
            pool.offer(instance);
        } else {
            // 销毁实例
            totalInstances.decrementAndGet();
        }
    }

    private void prewarmProvisionedConcurrency() {
        int provisioned = properties.getServerlessEngine().getProvisionedConcurrency();
        
        for (String functionName : functionRegistry.keySet()) {
            FunctionDefinition def = functionRegistry.get(functionName);
            BlockingQueue<FunctionInstance> pool = warmPools.get(functionName);
            
            for (int i = 0; i < provisioned && pool.size() < provisioned; i++) {
                FunctionInstance instance = createInstance(functionName, def);
                pool.offer(instance);
            }
            
            log.debug("预热函数: {}，实例数: {}", functionName, pool.size());
        }
    }

    // ==================== 伸缩管理 ====================

    private void makeScalingDecisions() {
        String policy = properties.getServerlessEngine().getScalingPolicy();
        
        for (String functionName : functionRegistry.keySet()) {
            ScalingState state = scalingStates.get(functionName);
            FunctionMetrics metrics = functionMetrics.get(functionName);
            AtomicInteger active = activeInvocations.get(functionName);
            
            int currentActive = active.get();
            int warmPoolSize = warmPools.get(functionName).size();
            int totalCurrent = currentActive + warmPoolSize;
            
            int targetInstances;
            
            switch (policy) {
                case "PREDICTIVE":
                    targetInstances = predictiveScaling(functionName, metrics, currentActive);
                    break;
                case "REACTIVE":
                    targetInstances = reactiveScaling(currentActive, warmPoolSize);
                    break;
                case "STEP":
                    targetInstances = stepScaling(currentActive, totalCurrent);
                    break;
                default:
                    targetInstances = totalCurrent;
            }
            
            // 应用伸缩
            applyScaling(functionName, targetInstances, totalCurrent);
            
            state.lastDecisionTime = System.currentTimeMillis();
            state.targetInstances = targetInstances;
        }
    }

    private int predictiveScaling(String functionName, FunctionMetrics metrics, int currentActive) {
        // 基于历史数据预测
        double avgLatency = metrics.totalLatency / Math.max(1, metrics.invocations);
        double invocationsPerSec = metrics.invocations / Math.max(1, 
                (System.currentTimeMillis() - metrics.startTime) / 1000.0);
        
        // 预测下一分钟的负载
        double predictedLoad = invocationsPerSec * 1.2; // 20%余量
        
        // 计算需要的实例数
        int minInstances = properties.getServerlessEngine().getMinInstances();
        int maxInstances = properties.getServerlessEngine().getMaxInstances();
        
        int needed = (int) Math.ceil(predictedLoad / 10.0); // 假设每实例处理10QPS
        return Math.max(minInstances, Math.min(maxInstances, needed));
    }

    private int reactiveScaling(int currentActive, int warmPoolSize) {
        int minInstances = properties.getServerlessEngine().getMinInstances();
        int maxInstances = properties.getServerlessEngine().getMaxInstances();
        
        // 响应式：根据当前活跃连接数调整
        int target = currentActive + Math.max(2, warmPoolSize / 2);
        return Math.max(minInstances, Math.min(maxInstances, target));
    }

    private int stepScaling(int currentActive, int totalCurrent) {
        int minInstances = properties.getServerlessEngine().getMinInstances();
        int maxInstances = properties.getServerlessEngine().getMaxInstances();
        
        // 阶梯式伸缩
        if (currentActive > totalCurrent * 0.8) {
            return Math.min(maxInstances, totalCurrent + 5);
        } else if (currentActive < totalCurrent * 0.3) {
            return Math.max(minInstances, totalCurrent - 2);
        }
        return totalCurrent;
    }

    private void applyScaling(String functionName, int target, int current) {
        FunctionDefinition def = functionRegistry.get(functionName);
        BlockingQueue<FunctionInstance> pool = warmPools.get(functionName);
        
        if (target > current) {
            // 扩容
            int toCreate = target - current;
            for (int i = 0; i < toCreate; i++) {
                FunctionInstance instance = createInstance(functionName, def);
                pool.offer(instance);
            }
            log.debug("函数扩容: {}，新增实例: {}", functionName, toCreate);
            
        } else if (target < current && pool.size() > 0) {
            // 缩容（只从预热池移除）
            int toRemove = Math.min(current - target, pool.size());
            for (int i = 0; i < toRemove; i++) {
                pool.poll();
                totalInstances.decrementAndGet();
            }
            log.debug("函数缩容: {}，移除实例: {}", functionName, toRemove);
        }
    }

    private void cleanupIdleInstances() {
        long idleTimeout = properties.getServerlessEngine().getIdleTimeoutMs();
        long now = System.currentTimeMillis();
        
        for (String functionName : warmPools.keySet()) {
            BlockingQueue<FunctionInstance> pool = warmPools.get(functionName);
            int minInstances = properties.getServerlessEngine().getMinInstances();
            
            // 移除超时的空闲实例
            pool.removeIf(instance -> {
                if (instance.getLastUsed() + idleTimeout < now && pool.size() > minInstances) {
                    totalInstances.decrementAndGet();
                    return true;
                }
                return false;
            });
        }
    }

    private void collectMetrics() {
        for (FunctionMetrics metrics : functionMetrics.values()) {
            // 重置短期指标用于下一个窗口
            metrics.windowInvocations = 0;
            metrics.windowLatency = 0;
        }
    }

    private void updateFunctionMetrics(String functionName, long latency, boolean success) {
        FunctionMetrics metrics = functionMetrics.get(functionName);
        if (metrics != null) {
            metrics.invocations++;
            metrics.windowInvocations++;
            metrics.totalLatency += latency;
            metrics.windowLatency += latency;
            if (!success) metrics.errors++;
        }
    }

    // ==================== 状态查询 ====================

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalInstances", totalInstances.get());
        stats.put("totalInvocations", totalInvocations.get());
        stats.put("coldStarts", coldStarts.get());
        stats.put("coldStartRate", totalInvocations.get() > 0 
                ? String.format("%.2f%%", (double) coldStarts.get() / totalInvocations.get() * 100) 
                : "N/A");
        stats.put("totalComputeTimeMs", totalComputeTimeMs.get());
        stats.put("totalMemoryMbMs", totalMemoryMbMs.get());
        
        Map<String, Object> functionStats = new LinkedHashMap<>();
        for (String name : functionRegistry.keySet()) {
            FunctionMetrics metrics = functionMetrics.get(name);
            AtomicInteger active = activeInvocations.get(name);
            BlockingQueue<FunctionInstance> pool = warmPools.get(name);
            
            functionStats.put(name, Map.of(
                    "activeInvocations", active.get(),
                    "warmPoolSize", pool.size(),
                    "totalInvocations", metrics.invocations,
                    "avgLatencyMs", metrics.invocations > 0 ? metrics.totalLatency / metrics.invocations : 0,
                    "coldStarts", metrics.coldStarts,
                    "errors", metrics.errors
            ));
        }
        stats.put("functions", functionStats);
        
        return stats;
    }

    public List<String> getRegisteredFunctions() {
        return new ArrayList<>(functionRegistry.keySet());
    }

    // ==================== 内部类 ====================

    public interface CacheFunction {
        Object execute(Object input);
    }

    private static class FunctionDefinition {
        private final String name;
        private final Function<Void, CacheFunction> factory;
        private final int memoryMb;

        FunctionDefinition(String name, Function<Void, CacheFunction> factory, int memoryMb) {
            this.name = name;
            this.factory = factory;
            this.memoryMb = memoryMb;
        }

        String getName() { return name; }
        Function<Void, CacheFunction> getFactory() { return factory; }
        int getMemoryMb() { return memoryMb; }
    }

    private static class FunctionInstance {
        private final String id;
        private final String functionName;
        private final CacheFunction function;
        private final int memoryMb;
        private long created;
        private long lastUsed;

        FunctionInstance(String id, String functionName, CacheFunction function, int memoryMb) {
            this.id = id;
            this.functionName = functionName;
            this.function = function;
            this.memoryMb = memoryMb;
        }

        String getId() { return id; }
        CacheFunction getFunction() { return function; }
        long getLastUsed() { return lastUsed; }
        void setCreated(long created) { this.created = created; }
        void setLastUsed(long lastUsed) { this.lastUsed = lastUsed; }
        
        boolean isExpired() {
            return System.currentTimeMillis() - created > 3600000; // 1小时
        }
    }

    private static class ScalingState {
        long lastDecisionTime;
        int targetInstances;
        int currentInstances;
    }

    private static class FunctionMetrics {
        long startTime = System.currentTimeMillis();
        long invocations;
        long windowInvocations;
        long totalLatency;
        long windowLatency;
        long coldStarts;
        long errors;
    }

    // ==================== 内置缓存函数 ====================

    private static class CacheGetFunction implements CacheFunction {
        @Override
        public Object execute(Object input) {
            // 模拟缓存读取
            return "cached_value_for_" + input;
        }
    }

    private static class CachePutFunction implements CacheFunction {
        @Override
        public Object execute(Object input) {
            // 模拟缓存写入
            return Map.of("status", "stored", "key", input);
        }
    }

    private static class CacheDeleteFunction implements CacheFunction {
        @Override
        public Object execute(Object input) {
            // 模拟缓存删除
            return Map.of("status", "deleted", "key", input);
        }
    }

    private static class CacheMGetFunction implements CacheFunction {
        @Override
        public Object execute(Object input) {
            // 模拟批量读取
            if (input instanceof List) {
                return ((List<?>) input).stream()
                        .map(k -> "cached_value_for_" + k)
                        .toList();
            }
            return Collections.emptyList();
        }
    }

    private static class CacheComputeFunction implements CacheFunction {
        @Override
        public Object execute(Object input) {
            // 模拟缓存计算
            if (input instanceof Map) {
                Map<?, ?> params = (Map<?, ?>) input;
                String key = String.valueOf(params.get("key"));
                return "computed_" + key + "_" + System.currentTimeMillis();
            }
            return null;
        }
    }

    private static class CacheFilterFunction implements CacheFunction {
        @Override
        public Object execute(Object input) {
            // 模拟缓存过滤
            if (input instanceof Map) {
                Map<?, ?> params = (Map<?, ?>) input;
                String pattern = String.valueOf(params.get("pattern"));
                return List.of("key1_" + pattern, "key2_" + pattern);
            }
            return Collections.emptyList();
        }
    }

    private static class CacheAggregateFunction implements CacheFunction {
        @Override
        public Object execute(Object input) {
            // 模拟缓存聚合
            if (input instanceof List) {
                List<?> values = (List<?>) input;
                return Map.of(
                        "count", values.size(),
                        "sum", values.stream().mapToDouble(v -> v instanceof Number ? ((Number) v).doubleValue() : 0).sum()
                );
            }
            return Map.of("count", 0, "sum", 0.0);
        }
    }

    public static class ServerlessException extends RuntimeException {
        public ServerlessException(String message) { super(message); }
    }
}
