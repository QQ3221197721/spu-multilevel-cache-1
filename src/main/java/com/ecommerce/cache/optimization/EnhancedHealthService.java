package com.ecommerce.cache.optimization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 健康检查增强模块
 * 
 * 核心功能：
 * 1. 深度健康探针 - 多维度系统健康检查
 * 2. K8s 探针支持 - Liveness/Readiness/Startup 探针
 * 3. 依赖服务检测 - 检测所有外部依赖状态
 * 4. 自愈机制 - 自动恢复常见故障
 * 5. 优雅关闭 - 平滑处理请求后关闭
 * 6. 健康报告 - 详细的系统健康报告
 */
@Service
public class EnhancedHealthService {
    
    private static final Logger log = LoggerFactory.getLogger(EnhancedHealthService.class);
    
    private final MeterRegistry meterRegistry;
    private final List<DependencyChecker> dependencyCheckers;
    
    // 系统状态
    private final AtomicReference<SystemHealth> currentHealth = new AtomicReference<>(SystemHealth.HEALTHY);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    
    // 健康检查历史
    private final ConcurrentLinkedQueue<HealthCheckRecord> healthHistory = new ConcurrentLinkedQueue<>();
    private static final int MAX_HISTORY = 100;
    
    // 指标
    private final Counter healthCheckCounter;
    private final Counter healthCheckFailCounter;
    private final Timer healthCheckTimer;
    
    @Value("${optimization.health.check-interval-seconds:10}")
    private int checkIntervalSeconds;
    
    @Value("${optimization.health.failure-threshold:3}")
    private int failureThreshold;
    
    // 连续失败计数
    private final AtomicLong consecutiveFailures = new AtomicLong(0);
    
    public EnhancedHealthService(MeterRegistry meterRegistry, List<DependencyChecker> dependencyCheckers) {
        this.meterRegistry = meterRegistry;
        this.dependencyCheckers = dependencyCheckers;
        
        this.healthCheckCounter = Counter.builder("health.check.total").register(meterRegistry);
        this.healthCheckFailCounter = Counter.builder("health.check.failures").register(meterRegistry);
        this.healthCheckTimer = Timer.builder("health.check.duration").register(meterRegistry);
        
        // 注册系统状态指标
        Gauge.builder("system.health.status", () -> currentHealth.get().ordinal())
            .register(meterRegistry);
        Gauge.builder("system.ready", () -> ready.get() ? 1 : 0)
            .register(meterRegistry);
        Gauge.builder("system.shutting.down", () -> shuttingDown.get() ? 1 : 0)
            .register(meterRegistry);
    }
    
    @PostConstruct
    public void init() {
        // 启动后延迟标记为就绪
        CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {
            if (performHealthCheck().healthy()) {
                ready.set(true);
                log.info("System is ready");
            }
        });
    }
    
    /**
     * 执行完整健康检查
     */
    public HealthReport performHealthCheck() {
        healthCheckCounter.increment();
        
        return healthCheckTimer.record(() -> {
            Instant checkTime = Instant.now();
            Map<String, DependencyHealth> dependencies = new LinkedHashMap<>();
            boolean allHealthy = true;
            
            // 检查所有依赖
            for (DependencyChecker checker : dependencyCheckers) {
                try {
                    DependencyHealth health = checker.check();
                    dependencies.put(checker.getName(), health);
                    if (!health.healthy()) {
                        allHealthy = false;
                    }
                } catch (Exception e) {
                    log.error("Health check failed for: {}", checker.getName(), e);
                    dependencies.put(checker.getName(), 
                        new DependencyHealth(false, "Check failed: " + e.getMessage(), 0));
                    allHealthy = false;
                }
            }
            
            // 检查系统资源
            SystemResourceHealth resourceHealth = checkSystemResources();
            if (!resourceHealth.healthy()) {
                allHealthy = false;
            }
            
            // 更新状态
            if (allHealthy) {
                consecutiveFailures.set(0);
                currentHealth.set(SystemHealth.HEALTHY);
            } else {
                healthCheckFailCounter.increment();
                long failures = consecutiveFailures.incrementAndGet();
                
                if (failures >= failureThreshold) {
                    currentHealth.set(SystemHealth.UNHEALTHY);
                    log.warn("System marked as UNHEALTHY after {} consecutive failures", failures);
                } else {
                    currentHealth.set(SystemHealth.DEGRADED);
                    log.warn("System degraded, failure count: {}", failures);
                }
            }
            
            // 记录历史
            HealthReport report = new HealthReport(
                allHealthy, 
                currentHealth.get(),
                checkTime,
                dependencies,
                resourceHealth
            );
            
            healthHistory.offer(new HealthCheckRecord(checkTime, report));
            while (healthHistory.size() > MAX_HISTORY) {
                healthHistory.poll();
            }
            
            return report;
        });
    }
    
    /**
     * 检查系统资源
     */
    private SystemResourceHealth checkSystemResources() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        
        // 堆内存使用率
        double heapUsage = (double) memoryBean.getHeapMemoryUsage().getUsed() 
            / memoryBean.getHeapMemoryUsage().getMax();
        
        // 线程数
        int threadCount = threadBean.getThreadCount();
        
        // 运行时间
        long uptime = runtimeBean.getUptime();
        
        // CPU 负载
        double cpuLoad = 0;
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            cpuLoad = sunBean.getProcessCpuLoad();
        }
        
        // 判断是否健康
        List<String> warnings = new ArrayList<>();
        boolean healthy = true;
        
        if (heapUsage > 0.9) {
            warnings.add("High heap usage: " + String.format("%.1f%%", heapUsage * 100));
            healthy = false;
        } else if (heapUsage > 0.8) {
            warnings.add("Elevated heap usage: " + String.format("%.1f%%", heapUsage * 100));
        }
        
        if (threadCount > 2000) {
            warnings.add("High thread count: " + threadCount);
            healthy = false;
        } else if (threadCount > 1000) {
            warnings.add("Elevated thread count: " + threadCount);
        }
        
        if (cpuLoad > 0.9) {
            warnings.add("High CPU load: " + String.format("%.1f%%", cpuLoad * 100));
            healthy = false;
        }
        
        return new SystemResourceHealth(healthy, heapUsage, threadCount, uptime, cpuLoad, warnings);
    }
    
    /**
     * K8s Liveness 探针
     */
    public boolean isLive() {
        // 只要 JVM 运行且未被标记为不健康，就是存活的
        return currentHealth.get() != SystemHealth.UNHEALTHY;
    }
    
    /**
     * K8s Readiness 探针
     */
    public boolean isReady() {
        return ready.get() && !shuttingDown.get() && currentHealth.get() == SystemHealth.HEALTHY;
    }
    
    /**
     * K8s Startup 探针
     */
    public boolean isStarted() {
        return ready.get();
    }
    
    /**
     * 开始优雅关闭
     */
    public void startGracefulShutdown() {
        shuttingDown.set(true);
        ready.set(false);
        log.info("Graceful shutdown initiated");
    }
    
    /**
     * 获取当前系统状态
     */
    public SystemHealth getCurrentHealth() {
        return currentHealth.get();
    }
    
    /**
     * 获取健康检查历史
     */
    public List<HealthCheckRecord> getHealthHistory() {
        return new ArrayList<>(healthHistory);
    }
    
    /**
     * 定时健康检查
     */
    @Scheduled(fixedRateString = "${optimization.health.check-interval-seconds:10}000")
    public void scheduledHealthCheck() {
        performHealthCheck();
    }
    
    // 枚举和记录类
    public enum SystemHealth { HEALTHY, DEGRADED, UNHEALTHY }
    
    public record HealthReport(
        boolean healthy,
        SystemHealth status,
        Instant checkTime,
        Map<String, DependencyHealth> dependencies,
        SystemResourceHealth resources
    ) {}
    
    public record DependencyHealth(boolean healthy, String message, long latencyMs) {}
    
    public record SystemResourceHealth(
        boolean healthy, 
        double heapUsage, 
        int threadCount, 
        long uptimeMs, 
        double cpuLoad,
        List<String> warnings
    ) {}
    
    public record HealthCheckRecord(Instant time, HealthReport report) {}
}

/**
 * 依赖检查器接口
 */
interface DependencyChecker {
    String getName();
    EnhancedHealthService.DependencyHealth check();
}

/**
 * Redis 健康检查器
 */
@Component
class RedisHealthChecker implements DependencyChecker {
    
    private static final Logger log = LoggerFactory.getLogger(RedisHealthChecker.class);
    
    private final StringRedisTemplate redisTemplate;
    
    @Value("${optimization.health.redis.timeout-ms:1000}")
    private long timeoutMs;
    
    public RedisHealthChecker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    @Override
    public String getName() {
        return "redis";
    }
    
    @Override
    public EnhancedHealthService.DependencyHealth check() {
        long start = System.currentTimeMillis();
        
        try {
            String testKey = "health:redis:" + System.currentTimeMillis();
            String testValue = "ping";
            
            redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));
            String result = redisTemplate.opsForValue().get(testKey);
            redisTemplate.delete(testKey);
            
            long latency = System.currentTimeMillis() - start;
            
            if (testValue.equals(result)) {
                return new EnhancedHealthService.DependencyHealth(true, "OK", latency);
            } else {
                return new EnhancedHealthService.DependencyHealth(false, "Value mismatch", latency);
            }
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return new EnhancedHealthService.DependencyHealth(false, e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}

/**
 * JVM 健康检查器
 */
@Component
class JvmHealthChecker implements DependencyChecker {
    
    @Override
    public String getName() {
        return "jvm";
    }
    
    @Override
    public EnhancedHealthService.DependencyHealth check() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        double heapUsage = (double) memoryBean.getHeapMemoryUsage().getUsed() 
            / memoryBean.getHeapMemoryUsage().getMax();
        
        if (heapUsage > 0.95) {
            return new EnhancedHealthService.DependencyHealth(false, 
                String.format("Critical heap usage: %.1f%%", heapUsage * 100), 0);
        } else if (heapUsage > 0.85) {
            return new EnhancedHealthService.DependencyHealth(true, 
                String.format("High heap usage: %.1f%%", heapUsage * 100), 0);
        }
        
        return new EnhancedHealthService.DependencyHealth(true, "OK", 0);
    }
}

/**
 * Spring Actuator 集成的健康指示器
 */
@Component("enhancedCacheHealth")
class EnhancedCacheHealthIndicator implements HealthIndicator {
    
    private final EnhancedHealthService healthService;
    
    public EnhancedCacheHealthIndicator(EnhancedHealthService healthService) {
        this.healthService = healthService;
    }
    
    @Override
    public Health health() {
        EnhancedHealthService.HealthReport report = healthService.performHealthCheck();
        
        Health.Builder builder = report.healthy() ? Health.up() : Health.down();
        
        builder.withDetail("status", report.status().name())
               .withDetail("checkTime", report.checkTime().toString());
        
        // 添加依赖状态
        report.dependencies().forEach((name, health) -> 
            builder.withDetail("dependency." + name, health.healthy() ? "UP" : "DOWN - " + health.message()));
        
        // 添加资源状态
        builder.withDetail("resources.heapUsage", String.format("%.1f%%", report.resources().heapUsage() * 100))
               .withDetail("resources.threadCount", report.resources().threadCount())
               .withDetail("resources.uptimeMs", report.resources().uptimeMs());
        
        if (!report.resources().warnings().isEmpty()) {
            builder.withDetail("resources.warnings", report.resources().warnings());
        }
        
        return builder.build();
    }
}

/**
 * K8s 探针端点
 */
@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping("/actuator/probes")
class K8sProbeEndpoint {
    
    private final EnhancedHealthService healthService;
    
    public K8sProbeEndpoint(EnhancedHealthService healthService) {
        this.healthService = healthService;
    }
    
    /**
     * Liveness 探针
     * 用于检测应用是否需要重启
     */
    @org.springframework.web.bind.annotation.GetMapping("/liveness")
    public org.springframework.http.ResponseEntity<Map<String, Object>> liveness() {
        boolean live = healthService.isLive();
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", live ? "UP" : "DOWN");
        response.put("checks", Map.of("liveness", live));
        
        return live 
            ? org.springframework.http.ResponseEntity.ok(response)
            : org.springframework.http.ResponseEntity.status(503).body(response);
    }
    
    /**
     * Readiness 探针
     * 用于检测应用是否可以接收流量
     */
    @org.springframework.web.bind.annotation.GetMapping("/readiness")
    public org.springframework.http.ResponseEntity<Map<String, Object>> readiness() {
        boolean ready = healthService.isReady();
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", ready ? "UP" : "DOWN");
        response.put("checks", Map.of(
            "ready", ready,
            "health", healthService.getCurrentHealth().name()
        ));
        
        return ready 
            ? org.springframework.http.ResponseEntity.ok(response)
            : org.springframework.http.ResponseEntity.status(503).body(response);
    }
    
    /**
     * Startup 探针
     * 用于检测应用是否已启动完成
     */
    @org.springframework.web.bind.annotation.GetMapping("/startup")
    public org.springframework.http.ResponseEntity<Map<String, Object>> startup() {
        boolean started = healthService.isStarted();
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", started ? "UP" : "DOWN");
        response.put("checks", Map.of("startup", started));
        
        return started 
            ? org.springframework.http.ResponseEntity.ok(response)
            : org.springframework.http.ResponseEntity.status(503).body(response);
    }
    
    /**
     * 完整健康报告
     */
    @org.springframework.web.bind.annotation.GetMapping("/health/detail")
    public org.springframework.http.ResponseEntity<EnhancedHealthService.HealthReport> healthDetail() {
        return org.springframework.http.ResponseEntity.ok(healthService.performHealthCheck());
    }
}

/**
 * 自愈服务 - 自动处理常见故障
 */
@Service
class SelfHealingService {
    
    private static final Logger log = LoggerFactory.getLogger(SelfHealingService.class);
    
    private final EnhancedHealthService healthService;
    private final MeterRegistry meterRegistry;
    
    @Value("${optimization.health.self-healing.enabled:true}")
    private boolean enabled;
    
    @Value("${optimization.health.self-healing.gc-threshold:0.85}")
    private double gcThreshold;
    
    private final Counter healingActionsCounter;
    private final AtomicLong lastGcTime = new AtomicLong(0);
    
    public SelfHealingService(EnhancedHealthService healthService, MeterRegistry meterRegistry) {
        this.healthService = healthService;
        this.meterRegistry = meterRegistry;
        this.healingActionsCounter = Counter.builder("health.healing.actions").register(meterRegistry);
    }
    
    /**
     * 定时检查并执行自愈操作
     */
    @Scheduled(fixedRate = 30000)
    public void checkAndHeal() {
        if (!enabled) return;
        
        // 检查内存压力
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        double heapUsage = (double) memoryBean.getHeapMemoryUsage().getUsed() 
            / memoryBean.getHeapMemoryUsage().getMax();
        
        if (heapUsage > gcThreshold) {
            // 距离上次 GC 至少 60 秒
            long now = System.currentTimeMillis();
            if (now - lastGcTime.get() > 60000) {
                log.warn("High memory usage detected ({}%), triggering GC", String.format("%.1f", heapUsage * 100));
                System.gc();
                lastGcTime.set(now);
                healingActionsCounter.increment();
            }
        }
        
        // 检查线程数
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        if (deadlockedThreads != null && deadlockedThreads.length > 0) {
            log.error("Deadlock detected! Thread IDs: {}", Arrays.toString(deadlockedThreads));
            // 记录死锁信息用于后续分析
            healingActionsCounter.increment();
        }
    }
    
    /**
     * 手动触发 GC
     */
    public void forceGc() {
        log.info("Forcing garbage collection...");
        long beforeMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.gc();
        long afterMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        log.info("GC completed, freed {} MB", (beforeMem - afterMem) / 1024 / 1024);
        healingActionsCounter.increment();
    }
    
    /**
     * 获取自愈统计
     */
    public HealingStats getStats() {
        return new HealingStats(healingActionsCounter.count(), lastGcTime.get());
    }
    
    public record HealingStats(double actions, long lastGcTime) {}
}

/**
 * 优雅关闭处理器
 */
@Component
class GracefulShutdownHandler {
    
    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);
    
    private final EnhancedHealthService healthService;
    
    @Value("${optimization.health.graceful-shutdown.wait-seconds:30}")
    private int waitSeconds;
    
    // 正在处理的请求计数
    private final AtomicLong activeRequests = new AtomicLong(0);
    
    public GracefulShutdownHandler(EnhancedHealthService healthService) {
        this.healthService = healthService;
    }
    
    /**
     * 请求开始
     */
    public void requestStarted() {
        activeRequests.incrementAndGet();
    }
    
    /**
     * 请求结束
     */
    public void requestCompleted() {
        activeRequests.decrementAndGet();
    }
    
    /**
     * 执行优雅关闭
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("Initiating graceful shutdown...");
        
        // 标记为关闭中，停止接收新请求
        healthService.startGracefulShutdown();
        
        // 等待现有请求完成
        int waited = 0;
        while (activeRequests.get() > 0 && waited < waitSeconds) {
            try {
                log.info("Waiting for {} active requests to complete...", activeRequests.get());
                Thread.sleep(1000);
                waited++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        if (activeRequests.get() > 0) {
            log.warn("Shutdown timeout, {} requests still active", activeRequests.get());
        } else {
            log.info("Graceful shutdown completed");
        }
    }
    
    public long getActiveRequests() {
        return activeRequests.get();
    }
}
