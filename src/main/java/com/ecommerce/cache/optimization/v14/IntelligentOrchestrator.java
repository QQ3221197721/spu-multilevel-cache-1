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
import java.util.stream.Collectors;

/**
 * V14智能缓存编排器
 * 基于强化学习的多目标优化编排系统
 */
@Component
public class IntelligentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IntelligentOrchestrator.class);

    private final OptimizationV14Properties properties;
    private final MeterRegistry meterRegistry;

    // 编排策略
    private final StrategyManager strategyManager;
    private final PerformanceMonitor performanceMonitor;
    private final ResourceAllocator resourceAllocator;
    private final DecisionMaker decisionMaker;
    
    // 统计信息
    private final AtomicLong totalDecisions = new AtomicLong(0);
    private final AtomicLong totalImprovements = new AtomicLong(0);
    
    // 执行器
    private ScheduledExecutorService scheduler;
    private ExecutorService orchestrationExecutor;
    
    // 指标
    private Counter decisionsMade;
    private Counter improvementsMade;
    private Timer decisionLatency;

    public IntelligentOrchestrator(OptimizationV14Properties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.strategyManager = new StrategyManager();
        this.performanceMonitor = new PerformanceMonitor();
        this.resourceAllocator = new ResourceAllocator();
        this.decisionMaker = new DecisionMaker();
    }

    @PostConstruct
    public void init() {
        if (!properties.getIntelligentOrchestrator().isEnabled()) {
            log.info("V14智能缓存编排器已禁用");
            return;
        }

        initializeMetrics();
        initializeExecutors();
        initializeStrategies();
        startBackgroundTasks();

        log.info("V14智能缓存编排器初始化完成 - 策略选择器: {}, 决策间隔: {}ms",
                properties.getIntelligentOrchestrator().getStrategySelector(),
                properties.getIntelligentOrchestrator().getDecisionIntervalMs());
    }

    private void initializeMetrics() {
        decisionsMade = Counter.builder("v14.orchestration.decisions")
                .description("编排决策次数").register(meterRegistry);
        improvementsMade = Counter.builder("v14.orchestration.improvements")
                .description("优化改进次数").register(meterRegistry);
        decisionLatency = Timer.builder("v14.orchestration.decision.latency")
                .description("决策延迟").register(meterRegistry);
        
        Gauge.builder("v14.orchestration.improvement.rate", this, o -> 
                totalDecisions.get() > 0 ? (double) totalImprovements.get() / totalDecisions.get() : 0)
                .description("改进率").register(meterRegistry);
    }

    private void initializeExecutors() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "v14-orchestrator-scheduler");
            t.setDaemon(true);
            return t;
        });
        orchestrationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private void initializeStrategies() {
        // 注册各种编排策略
        strategyManager.registerStrategy("CACHE_LRU_POLICY", new LruCacheStrategy());
        strategyManager.registerStrategy("CACHE_TTL_OPTIMIZATION", new TtlOptimizationStrategy());
        strategyManager.registerStrategy("RESOURCE_AUTO_SCALING", new AutoScalingStrategy());
        strategyManager.registerStrategy("LOAD_BALANCING_OPTIMIZATION", new LoadBalancingStrategy());
        strategyManager.registerStrategy("MEMORY_PRESSURE_MANAGEMENT", new MemoryPressureStrategy());
        strategyManager.registerStrategy("LATENCY_OPTIMIZATION", new LatencyOptimizationStrategy());
        strategyManager.registerStrategy("COST_OPTIMIZATION", new CostOptimizationStrategy());
        strategyManager.registerStrategy("CONSISTENCY_LEVEL_ADJUSTMENT", new ConsistencyAdjustmentStrategy());
    }

    private void startBackgroundTasks() {
        // 定期做出编排决策
        scheduler.scheduleAtFixedRate(this::makeOrchestrationDecision, 
                properties.getIntelligentOrchestrator().getDecisionIntervalMs(),
                properties.getIntelligentOrchestrator().getDecisionIntervalMs(),
                TimeUnit.MILLISECONDS);
        
        // 性能监控
        scheduler.scheduleAtFixedRate(performanceMonitor::collectMetrics, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
        if (orchestrationExecutor != null) orchestrationExecutor.shutdown();
    }

    // ==================== 核心API ====================

    /**
     * 执行编排决策
     */
    public OrchestrationResult executeOrchestration(String scope) {
        return decisionLatency.record(() -> {
            totalDecisions.incrementAndGet();
            
            // 收集当前状态
            SystemState currentState = performanceMonitor.getCurrentState();
            
            // 基于当前状态做出决策
            List<OrchestrationAction> actions = decisionMaker.makeDecision(currentState);
            
            // 执行决策
            List<OrchestrationAction> executedActions = executeActions(actions);
            
            // 评估效果
            boolean improvement = evaluateImprovement(currentState);
            
            if (improvement) {
                totalImprovements.incrementAndGet();
                improvementsMade.increment();
            }
            
            OrchestrationResult result = new OrchestrationResult(
                    scope, 
                    actions.size(), 
                    executedActions.size(), 
                    improvement,
                    System.currentTimeMillis()
            );
            
            decisionsMade.increment();
            
            log.debug("编排决策完成: scope={}, actions={}, executed={}, improved={}", 
                    scope, actions.size(), executedActions.size(), improvement);
            
            return result;
        });
    }

    /**
     * 获取编排建议
     */
    public List<OrchestrationSuggestion> getSuggestions(String scope) {
        SystemState state = performanceMonitor.getCurrentState();
        return decisionMaker.generateSuggestions(state, scope);
    }

    /**
     * 获取编排状态
     */
    public OrchestrationStatus getStatus() {
        return new OrchestrationStatus(
                totalDecisions.get(),
                totalImprovements.get(),
                strategyManager.getActiveStrategies(),
                performanceMonitor.getCurrentState(),
                resourceAllocator.getResourceAllocation()
        );
    }

    /**
     * 动态调整策略权重
     */
    public void adjustStrategyWeights(Map<String, Double> weights) {
        strategyManager.adjustWeights(weights);
        log.info("策略权重已调整: {}", weights);
    }

    /**
     * 启用/禁用特定策略
     */
    public void toggleStrategy(String strategyName, boolean enabled) {
        strategyManager.toggleStrategy(strategyName, enabled);
        log.info("策略 {} 已{}", strategyName, enabled ? "启用" : "禁用");
    }

    // ==================== 决策执行 ====================

    private List<OrchestrationAction> executeActions(List<OrchestrationAction> actions) {
        return actions.parallelStream()
                .filter(action -> {
                    try {
                        return executeAction(action);
                    } catch (Exception e) {
                        log.warn("执行编排动作失败: {}", action.getDescription(), e);
                        return false;
                    }
                })
                .collect(Collectors.toList());
    }

    private boolean executeAction(OrchestrationAction action) {
        switch (action.getType()) {
            case CACHE_SIZE_ADJUSTMENT:
                return resourceAllocator.adjustCacheSize((int) action.getParam("targetSize"));
            case TTL_UPDATE:
                return updateTtlSettings((String) action.getParam("keyPattern"), 
                        (Long) action.getParam("newTtl"));
            case CONSISTENCY_LEVEL_CHANGE:
                return updateConsistencyLevel((String) action.getParam("level"));
            case RESOURCE_SCALING:
                return resourceAllocator.scaleResources(
                        (double) action.getParam("cpuFactor"),
                        (double) action.getParam("memoryFactor"));
            case LOAD_BALANCING:
                return rebalanceLoad((String) action.getParam("source"), 
                        (String) action.getParam("target"));
            default:
                return false;
        }
    }

    private boolean updateTtlSettings(String keyPattern, Long newTtl) {
        // 模拟TTL更新
        log.debug("更新TTL设置: pattern={}, ttl={}", keyPattern, newTtl);
        return true;
    }

    private boolean updateConsistencyLevel(String level) {
        // 模拟一致性级别更新
        log.debug("更新一致性级别: {}", level);
        return true;
    }

    private boolean rebalanceLoad(String source, String target) {
        // 模拟负载均衡
        log.debug("负载均衡: {} -> {}", source, target);
        return true;
    }

    private boolean evaluateImprovement(SystemState beforeState) {
        SystemState afterState = performanceMonitor.getCurrentState();
        
        // 简单评估：如果延迟降低或吞吐量提升，则认为是改进
        boolean latencyImproved = afterState.getAvgLatency() < beforeState.getAvgLatency();
        boolean throughputImproved = afterState.getThroughput() > beforeState.getThroughput();
        boolean costReduced = afterState.getCost() < beforeState.getCost();
        
        return latencyImproved || throughputImproved || costReduced;
    }

    /**
     * 定期编排决策
     */
    private void makeOrchestrationDecision() {
        try {
            executeOrchestration("auto");
        } catch (Exception e) {
            log.error("自动编排决策失败", e);
        }
    }

    // ==================== 内部类 ====================

    /**
     * 策略管理器
     */
    private static class StrategyManager {
        private final ConcurrentMap<String, OrchestrationStrategy> strategies = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Double> strategyWeights = new ConcurrentHashMap<>();
        private final AtomicInteger activeStrategies = new AtomicInteger(0);

        void registerStrategy(String name, OrchestrationStrategy strategy) {
            strategies.put(name, strategy);
            strategyWeights.put(name, 1.0); // 默认权重
            activeStrategies.incrementAndGet();
        }

        void adjustWeights(Map<String, Double> newWeights) {
            newWeights.forEach((name, weight) -> {
                if (strategies.containsKey(name)) {
                    strategyWeights.put(name, weight);
                }
            });
        }

        void toggleStrategy(String name, boolean enabled) {
            if (enabled) {
                if (!strategies.containsKey(name)) {
                    activeStrategies.incrementAndGet();
                }
            } else {
                if (strategies.containsKey(name)) {
                    activeStrategies.decrementAndGet();
                }
            }
        }

        List<OrchestrationStrategy> getActiveStrategies() {
            return strategies.values().stream()
                    .filter(s -> strategyWeights.getOrDefault(s.getClass().getSimpleName(), 1.0) > 0)
                    .collect(Collectors.toList());
        }

        int getActiveStrategyCount() {
            return activeStrategies.get();
        }
    }

    /**
     * 性能监控器
     */
    private static class PerformanceMonitor {
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong totalLatencyNs = new AtomicLong(0);
        private final AtomicLong totalErrors = new AtomicLong(0);
        private final AtomicLong totalCost = new AtomicLong(0);
        private final AtomicInteger currentConnections = new AtomicInteger(0);

        SystemState getCurrentState() {
            long requests = totalRequests.get();
            long latencyNs = totalLatencyNs.get();
            long errors = totalErrors.get();
            long cost = totalCost.get();
            int connections = currentConnections.get();
            
            return new SystemState(
                    requests > 0 ? latencyNs / requests / 1000000.0 : 0, // ms
                    requests,
                    (double) errors / Math.max(1, requests),
                    cost,
                    connections
            );
        }

        void collectMetrics() {
            // 模拟指标收集
            totalRequests.addAndGet(1000); // 模拟请求增长
            totalLatencyNs.addAndGet(500000000); // 模拟延迟累积
            totalCost.addAndGet(100); // 模拟成本累积
        }
    }

    /**
     * 资源分配器
     */
    private static class ResourceAllocator {
        private final AtomicInteger cacheSize = new AtomicInteger(1024); // MB
        private final AtomicInteger cpuCores = new AtomicInteger(4);
        private final AtomicInteger memoryGb = new AtomicInteger(8);

        boolean adjustCacheSize(int newSize) {
            int oldSize = cacheSize.getAndSet(newSize);
            log.debug("缓存大小调整: {}MB -> {}MB", oldSize, newSize);
            return true;
        }

        boolean scaleResources(double cpuFactor, double memoryFactor) {
            int newCpu = (int) (cpuCores.get() * cpuFactor);
            int newMemory = (int) (memoryGb.get() * memoryFactor);
            
            cpuCores.set(Math.max(1, newCpu));
            memoryGb.set(Math.max(1, newMemory));
            
            log.debug("资源伸缩: CPU {} -> {}, Memory {}GB -> {}GB", 
                    cpuCores.get() / cpuFactor, cpuCores.get(),
                    memoryGb.get() / memoryFactor, memoryGb.get());
            
            return true;
        }

        ResourceAllocation getResourceAllocation() {
            return new ResourceAllocation(
                    cacheSize.get(),
                    cpuCores.get(),
                    memoryGb.get()
            );
        }
    }

    /**
     * 决策制定器
     */
    private class DecisionMaker {
        private final Queue<DecisionRecord> decisionHistory = new ConcurrentLinkedQueue<>();
        private final AtomicInteger explorationCount = new AtomicInteger(0);

        List<OrchestrationAction> makeDecision(SystemState state) {
            List<OrchestrationAction> actions = new ArrayList<>();
            
            // 根据当前状态决定采取哪些动作
            if (state.getAvgLatency() > 100) { // 高延迟
                actions.add(new OrchestrationAction(
                        OrchestrationAction.ActionType.CACHE_SIZE_ADJUSTMENT,
                        "增加缓存大小以降低延迟",
                        Map.of("targetSize", state.getCacheSize() + 256)
                ));
            }
            
            if (state.getErrorRate() > 0.05) { // 高错误率
                actions.add(new OrchestrationAction(
                        OrchestrationAction.ActionType.CONSISTENCY_LEVEL_CHANGE,
                        "降低一致性级别以提高可用性",
                        Map.of("level", "EVENTUAL")
                ));
            }
            
            if (state.getThroughput() > 100000) { // 高吞吐量
                actions.add(new OrchestrationAction(
                        OrchestrationAction.ActionType.RESOURCE_SCALING,
                        "扩展资源以支持更高吞吐量",
                        Map.of("cpuFactor", 1.2, "memoryFactor", 1.2)
                ));
            }
            
            // 探索性决策（基于探索率）
            if (Math.random() < properties.getIntelligentOrchestrator().getExplorationRate()) {
                explorationCount.incrementAndGet();
                actions.addAll(generateExplorationActions(state));
            }
            
            // 记录决策
            decisionHistory.offer(new DecisionRecord(state, actions, System.currentTimeMillis()));
            
            // 保持决策历史大小
            while (decisionHistory.size() > properties.getIntelligentOrchestrator().getReplayBufferSize()) {
                decisionHistory.poll();
            }
            
            return actions;
        }

        List<OrchestrationAction> generateExplorationActions(SystemState state) {
            List<OrchestrationAction> actions = new ArrayList<>();
            
            // 随机生成一些探索性动作
            double random = Math.random();
            if (random < 0.3) {
                actions.add(new OrchestrationAction(
                        OrchestrationAction.ActionType.TTL_UPDATE,
                        "随机TTL调整探索",
                        Map.of("keyPattern", "random", "newTtl", 300L)
                ));
            } else if (random < 0.6) {
                actions.add(new OrchestrationAction(
                        OrchestrationAction.ActionType.LOAD_BALANCING,
                        "负载均衡探索",
                        Map.of("source", "node1", "target", "node2")
                ));
            }
            
            return actions;
        }

        List<OrchestrationSuggestion> generateSuggestions(SystemState state, String scope) {
            List<OrchestrationSuggestion> suggestions = new ArrayList<>();
            
            if (state.getAvgLatency() > 200) {
                suggestions.add(new OrchestrationSuggestion(
                        "HIGH_LATENCY_OPTIMIZATION",
                        "系统延迟过高，建议增加缓存大小或启用更快的缓存层",
                        95,
                        System.currentTimeMillis()
                ));
            }
            
            if (state.getErrorRate() > 0.1) {
                suggestions.add(new OrchestrationSuggestion(
                        "ERROR_RATE_REDUCTION",
                        "错误率过高，建议启用熔断机制或增加冗余",
                        90,
                        System.currentTimeMillis()
                ));
            }
            
            if (state.getCost() > 10000) {
                suggestions.add(new OrchestrationSuggestion(
                        "COST_OPTIMIZATION",
                        "成本过高，建议启用资源回收或调整一致性级别",
                        85,
                        System.currentTimeMillis()
                ));
            }
            
            return suggestions;
        }
    }

    // ==================== 接口定义 ====================

    public interface OrchestrationStrategy {
        List<OrchestrationAction> evaluate(SystemState state);
        String getName();
    }

    // ==================== 具体策略实现 ====================

    private static class LruCacheStrategy implements OrchestrationStrategy {
        @Override
        public List<OrchestrationAction> evaluate(SystemState state) {
            List<OrchestrationAction> actions = new ArrayList<>();
            if (state.getHitRate() < 0.8) {
                actions.add(new OrchestrationAction(
                        OrchestrationAction.ActionType.CACHE_SIZE_ADJUSTMENT,
                        "LRU缓存策略：命中率低，增加缓存大小",
                        Map.of("targetSize", (int)(state.getCacheSize() * 1.2))
                ));
            }
            return actions;
        }

        @Override
        public String getName() {
            return "LRU_CACHE_STRATEGY";
        }
    }

    private static class TtlOptimizationStrategy implements OrchestrationStrategy {
        @Override
        public List<OrchestrationAction> evaluate(SystemState state) {
            return List.of(new OrchestrationAction(
                    OrchestrationAction.ActionType.TTL_UPDATE,
                    "TTL优化策略：根据访问模式调整过期时间",
                    Map.of("keyPattern", "dynamic", "newTtl", 600L)
            ));
        }

        @Override
        public String getName() {
            return "TTL_OPTIMIZATION_STRATEGY";
        }
    }

    private static class AutoScalingStrategy implements OrchestrationStrategy {
        @Override
        public List<OrchestrationAction> evaluate(SystemState state) {
            if (state.getThroughput() > 50000) {
                return List.of(new OrchestrationAction(
                        OrchestrationAction.ActionType.RESOURCE_SCALING,
                        "自动伸缩策略：高吞吐量，扩展资源",
                        Map.of("cpuFactor", 1.5, "memoryFactor", 1.3)
                ));
            }
            return Collections.emptyList();
        }

        @Override
        public String getName() {
            return "AUTO_SCALING_STRATEGY";
        }
    }

    private static class LoadBalancingStrategy implements OrchestrationStrategy {
        @Override
        public List<OrchestrationAction> evaluate(SystemState state) {
            if (state.getLoadImbalance() > 0.3) {
                return List.of(new OrchestrationAction(
                        OrchestrationAction.ActionType.LOAD_BALANCING,
                        "负载均衡策略：检测到负载不均，重新分配",
                        Map.of("source", "high_load_node", "target", "low_load_node")
                ));
            }
            return Collections.emptyList();
        }

        @Override
        public String getName() {
            return "LOAD_BALANCING_STRATEGY";
        }
    }

    private static class MemoryPressureStrategy implements OrchestrationStrategy {
        @Override
        public List<OrchestrationAction> evaluate(SystemState state) {
            if (state.getMemoryPressure() > 0.8) {
                return List.of(new OrchestrationAction(
                        OrchestrationAction.ActionType.CACHE_SIZE_ADJUSTMENT,
                        "内存压力策略：内存压力高，减少缓存大小",
                        Map.of("targetSize", (int)(state.getCacheSize() * 0.8))
                ));
            }
            return Collections.emptyList();
        }

        @Override
        public String getName() {
            return "MEMORY_PRESSURE_STRATEGY";
        }
    }

    private static class LatencyOptimizationStrategy implements OrchestrationStrategy {
        @Override
        public List<OrchestrationAction> evaluate(SystemState state) {
            if (state.getAvgLatency() > 150) {
                return List.of(new OrchestrationAction(
                        OrchestrationAction.ActionType.CONSISTENCY_LEVEL_CHANGE,
                        "延迟优化策略：降低一致性级别以减少延迟",
                        Map.of("level", "EVENTUAL")
                ));
            }
            return Collections.emptyList();
        }

        @Override
        public String getName() {
            return "LATENCY_OPTIMIZATION_STRATEGY";
        }
    }

    private static class CostOptimizationStrategy implements OrchestrationStrategy {
        @Override
        public List<OrchestrationAction> evaluate(SystemState state) {
            if (state.getCost() > 5000) {
                return List.of(new OrchestrationAction(
                        OrchestrationAction.ActionType.CACHE_SIZE_ADJUSTMENT,
                        "成本优化策略：降低成本，减少缓存大小",
                        Map.of("targetSize", (int)(state.getCacheSize() * 0.9))
                ));
            }
            return Collections.emptyList();
        }

        @Override
        public String getName() {
            return "COST_OPTIMIZATION_STRATEGY";
        }
    }

    private static class ConsistencyAdjustmentStrategy implements OrchestrationStrategy {
        @Override
        public List<OrchestrationAction> evaluate(SystemState state) {
            if (state.getConsistencyRequirement() < 0.5) {
                return List.of(new OrchestrationAction(
                        OrchestrationAction.ActionType.CONSISTENCY_LEVEL_CHANGE,
                        "一致性调整策略：降低一致性要求以提高性能",
                        Map.of("level", "EVENTUAL")
                ));
            }
            return Collections.emptyList();
        }

        @Override
        public String getName() {
            return "CONSISTENCY_ADJUSTMENT_STRATEGY";
        }
    }

    // ==================== 数据类 ====================

    public static class SystemState {
        private final double avgLatency; // ms
        private final long throughput; // req/s
        private final double errorRate;
        private final long cost;
        private final int connections;
        private final int cacheSize = 1024; // MB
        private final double hitRate = 0.85;
        private final double loadImbalance = 0.1;
        private final double memoryPressure = 0.6;
        private final double consistencyRequirement = 0.8;

        SystemState(double avgLatency, long throughput, double errorRate, long cost, int connections) {
            this.avgLatency = avgLatency;
            this.throughput = throughput;
            this.errorRate = errorRate;
            this.cost = cost;
            this.connections = connections;
        }

        double getAvgLatency() { return avgLatency; }
        long getThroughput() { return throughput; }
        double getErrorRate() { return errorRate; }
        long getCost() { return cost; }
        int getConnections() { return connections; }
        int getCacheSize() { return cacheSize; }
        double getHitRate() { return hitRate; }
        double getLoadImbalance() { return loadImbalance; }
        double getMemoryPressure() { return memoryPressure; }
        double getConsistencyRequirement() { return consistencyRequirement; }
    }

    public static class OrchestrationAction {
        public enum ActionType {
            CACHE_SIZE_ADJUSTMENT,
            TTL_UPDATE,
            CONSISTENCY_LEVEL_CHANGE,
            RESOURCE_SCALING,
            LOAD_BALANCING
        }

        private final ActionType type;
        private final String description;
        private final Map<String, Object> params;

        OrchestrationAction(ActionType type, String description, Map<String, Object> params) {
            this.type = type;
            this.description = description;
            this.params = params;
        }

        ActionType getType() { return type; }
        String getDescription() { return description; }
        Object getParam(String key) { return params.get(key); }
        Map<String, Object> getParams() { return params; }
    }

    public static class OrchestrationResult {
        private final String scope;
        private final int plannedActions;
        private final int executedActions;
        private final boolean improvement;
        private final long timestamp;

        OrchestrationResult(String scope, int plannedActions, int executedActions, boolean improvement, long timestamp) {
            this.scope = scope;
            this.plannedActions = plannedActions;
            this.executedActions = executedActions;
            this.improvement = improvement;
            this.timestamp = timestamp;
        }

        String getScope() { return scope; }
        int getPlannedActions() { return plannedActions; }
        int getExecutedActions() { return executedActions; }
        boolean isImprovement() { return improvement; }
        long getTimestamp() { return timestamp; }
    }

    public static class OrchestrationSuggestion {
        private final String id;
        private final String description;
        private final int priority; // 1-100
        private final long timestamp;

        OrchestrationSuggestion(String id, String description, int priority, long timestamp) {
            this.id = id;
            this.description = description;
            this.priority = priority;
            this.timestamp = timestamp;
        }

        String getId() { return id; }
        String getDescription() { return description; }
        int getPriority() { return priority; }
        long getTimestamp() { return timestamp; }
    }

    public static class OrchestrationStatus {
        private final long totalDecisions;
        private final long totalImprovements;
        private final List<OrchestrationStrategy> activeStrategies;
        private final SystemState currentState;
        private final ResourceAllocation resourceAllocation;

        OrchestrationStatus(long totalDecisions, long totalImprovements,
                           List<OrchestrationStrategy> activeStrategies,
                           SystemState currentState,
                           ResourceAllocation resourceAllocation) {
            this.totalDecisions = totalDecisions;
            this.totalImprovements = totalImprovements;
            this.activeStrategies = activeStrategies;
            this.currentState = currentState;
            this.resourceAllocation = resourceAllocation;
        }

        long getTotalDecisions() { return totalDecisions; }
        long getTotalImprovements() { return totalImprovements; }
        List<OrchestrationStrategy> getActiveStrategies() { return activeStrategies; }
        SystemState getCurrentState() { return currentState; }
        ResourceAllocation getResourceAllocation() { return resourceAllocation; }
    }

    public static class ResourceAllocation {
        private final int cacheSizeMb;
        private final int cpuCores;
        private final int memoryGb;

        ResourceAllocation(int cacheSizeMb, int cpuCores, int memoryGb) {
            this.cacheSizeMb = cacheSizeMb;
            this.cpuCores = cpuCores;
            this.memoryGb = memoryGb;
        }

        int getCacheSizeMb() { return cacheSizeMb; }
        int getCpuCores() { return cpuCores; }
        int getMemoryGb() { return memoryGb; }
    }

    private static class DecisionRecord {
        private final SystemState state;
        private final List<OrchestrationAction> actions;
        private final long timestamp;

        DecisionRecord(SystemState state, List<OrchestrationAction> actions, long timestamp) {
            this.state = state;
            this.actions = actions;
            this.timestamp = timestamp;
        }
    }
}
