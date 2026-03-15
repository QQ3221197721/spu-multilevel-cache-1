package com.ecommerce.cache.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.management.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JVM 深度监控端点
 *
 * 提供：
 * 1. GC 详情（各收集器暂停次数、暂停时间、内存池前后变化）
 * 2. 线程 dump（包含死锁检测）
 * 3. 内存池分区明细（Eden / Survivor / Old / Metaspace / CodeCache）
 * 4. 缓冲区池状态（Direct / Mapped）
 * 5. 类加载统计
 */
@RestController
@RequestMapping("/api/monitor/jvm")
public class JvmMonitorEndpoint {

    private static final Logger log = LoggerFactory.getLogger(JvmMonitorEndpoint.class);

    private final MeterRegistry meterRegistry;

    public JvmMonitorEndpoint(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ==================== GC 详情 ====================

    /**
     * GC 收集器详细信息
     * 返回每个 GC 收集器的名称、次数、累计耗时、管理的内存池
     */
    @GetMapping("/gc")
    public ResponseEntity<Map<String, Object>> gcDetails() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        List<Map<String, Object>> collectors = new ArrayList<>();
        long totalCollections = 0;
        long totalTimeMs = 0;

        for (GarbageCollectorMXBean gc : gcBeans) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", gc.getName());
            info.put("collectionCount", gc.getCollectionCount());
            info.put("collectionTimeMs", gc.getCollectionTime());
            info.put("memoryPoolNames", Arrays.asList(gc.getMemoryPoolNames()));

            // 尝试获取详细 GC 信息（如果是 com.sun 实现）
            if (gc instanceof com.sun.management.GarbageCollectorMXBean sunGc) {
                var lastGcInfo = sunGc.getLastGcInfo();
                if (lastGcInfo != null) {
                    info.put("lastGcDurationMs", lastGcInfo.getDuration());
                    info.put("lastGcStartTime", lastGcInfo.getStartTime());
                    info.put("lastGcEndTime", lastGcInfo.getEndTime());

                    // 内存池变化
                    Map<String, Map<String, Long>> poolChanges = new LinkedHashMap<>();
                    lastGcInfo.getMemoryUsageBeforeGc().forEach((pool, before) -> {
                        MemoryUsage after = lastGcInfo.getMemoryUsageAfterGc().get(pool);
                        Map<String, Long> change = new LinkedHashMap<>();
                        change.put("beforeUsed", before.getUsed());
                        change.put("afterUsed", after != null ? after.getUsed() : -1);
                        change.put("freed", before.getUsed() - (after != null ? after.getUsed() : 0));
                        poolChanges.put(pool, change);
                    });
                    info.put("lastGcPoolChanges", poolChanges);
                }
            }

            totalCollections += gc.getCollectionCount();
            totalTimeMs += gc.getCollectionTime();
            collectors.add(info);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("totalCollections", totalCollections);
        result.put("totalTimeMs", totalTimeMs);
        result.put("collectors", collectors);

        return ResponseEntity.ok(result);
    }

    // ==================== 线程 dump ====================

    /**
     * 线程 dump 和死锁检测
     * @param includeStackTrace 是否包含完整堆栈
     * @param maxDepth 堆栈深度
     */
    @GetMapping("/threads")
    public ResponseEntity<Map<String, Object>> threadDump(
            @RequestParam(defaultValue = "true") boolean includeStackTrace,
            @RequestParam(defaultValue = "50") int maxDepth) {

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("threadCount", threadBean.getThreadCount());
        result.put("peakThreadCount", threadBean.getPeakThreadCount());
        result.put("daemonThreadCount", threadBean.getDaemonThreadCount());
        result.put("totalStartedThreadCount", threadBean.getTotalStartedThreadCount());

        // 死锁检测
        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        if (deadlockedThreads != null && deadlockedThreads.length > 0) {
            result.put("deadlockDetected", true);
            List<Map<String, Object>> deadlocks = new ArrayList<>();
            for (ThreadInfo ti : threadBean.getThreadInfo(deadlockedThreads, maxDepth)) {
                if (ti != null) {
                    deadlocks.add(formatThreadInfo(ti, true));
                }
            }
            result.put("deadlockedThreads", deadlocks);
        } else {
            result.put("deadlockDetected", false);
        }

        // 线程状态统计
        ThreadInfo[] allThreads = threadBean.getThreadInfo(threadBean.getAllThreadIds(), includeStackTrace ? maxDepth : 0);
        Map<String, Long> stateCount = Arrays.stream(allThreads)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        ti -> ti.getThreadState().name(),
                        Collectors.counting()
                ));
        result.put("stateDistribution", stateCount);

        // 完整线程列表（按状态分组）
        if (includeStackTrace) {
            List<Map<String, Object>> threadList = Arrays.stream(allThreads)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ti -> ti.getThreadState().name()))
                    .map(ti -> formatThreadInfo(ti, true))
                    .collect(Collectors.toList());
            result.put("threads", threadList);
        }

        return ResponseEntity.ok(result);
    }

    // ==================== 内存池 ====================

    /**
     * 内存池详细信息（Eden / Survivor / Old / Metaspace / CodeCache 等）
     */
    @GetMapping("/memory")
    public ResponseEntity<Map<String, Object>> memoryDetails() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());

        // 堆内存总览
        result.put("heap", formatMemoryUsage(memoryBean.getHeapMemoryUsage()));
        result.put("nonHeap", formatMemoryUsage(memoryBean.getNonHeapMemoryUsage()));

        // 各内存池
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        Map<String, Map<String, Object>> poolDetails = new LinkedHashMap<>();
        for (MemoryPoolMXBean pool : pools) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", pool.getType().name());
            info.put("usage", formatMemoryUsage(pool.getUsage()));
            info.put("peakUsage", formatMemoryUsage(pool.getPeakUsage()));
            if (pool.getCollectionUsage() != null) {
                info.put("collectionUsage", formatMemoryUsage(pool.getCollectionUsage()));
            }
            info.put("managers", Arrays.asList(pool.getMemoryManagerNames()));
            poolDetails.put(pool.getName(), info);
        }
        result.put("pools", poolDetails);

        // Buffer 池（Direct / Mapped）
        List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        Map<String, Map<String, Object>> bufferDetails = new LinkedHashMap<>();
        for (BufferPoolMXBean bp : bufferPools) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("count", bp.getCount());
            info.put("memoryUsedBytes", bp.getMemoryUsed());
            info.put("totalCapacityBytes", bp.getTotalCapacity());
            bufferDetails.put(bp.getName(), info);
        }
        result.put("bufferPools", bufferDetails);

        // Runtime 信息
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        result.put("uptimeMs", runtimeBean.getUptime());
        result.put("vmName", runtimeBean.getVmName());
        result.put("vmVersion", runtimeBean.getVmVersion());
        result.put("jvmArgs", runtimeBean.getInputArguments());

        // CPU 负载
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            result.put("processCpuLoad", String.format("%.2f%%", sunOs.getProcessCpuLoad() * 100));
            result.put("systemCpuLoad", String.format("%.2f%%", sunOs.getCpuLoad() * 100));
            result.put("processCpuTimeNs", sunOs.getProcessCpuTime());
        }

        return ResponseEntity.ok(result);
    }

    // ==================== 类加载 ====================

    /**
     * 类加载统计
     */
    @GetMapping("/classloading")
    public ResponseEntity<Map<String, Object>> classLoadingInfo() {
        ClassLoadingMXBean classBean = ManagementFactory.getClassLoadingMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("loadedClassCount", classBean.getLoadedClassCount());
        result.put("totalLoadedClassCount", classBean.getTotalLoadedClassCount());
        result.put("unloadedClassCount", classBean.getUnloadedClassCount());

        return ResponseEntity.ok(result);
    }

    // ==================== 综合摘要 ====================

    /**
     * JVM 综合摘要（一次性获取所有关键指标）
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> jvmSummary() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());

        // 内存
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        result.put("heapUsedMB", heap.getUsed() / 1024 / 1024);
        result.put("heapMaxMB", heap.getMax() / 1024 / 1024);
        result.put("heapUsagePercent", String.format("%.1f%%", (double) heap.getUsed() / heap.getMax() * 100));

        // GC
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long totalGcCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long totalGcTimeMs = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        result.put("totalGcCount", totalGcCount);
        result.put("totalGcTimeMs", totalGcTimeMs);
        result.put("gcCollectors", gcBeans.stream().map(GarbageCollectorMXBean::getName).toList());

        // 线程
        result.put("threadCount", threadBean.getThreadCount());
        result.put("peakThreadCount", threadBean.getPeakThreadCount());
        long[] deadlocked = threadBean.findDeadlockedThreads();
        result.put("deadlockDetected", deadlocked != null && deadlocked.length > 0);

        // 运行时
        result.put("uptimeMs", runtimeBean.getUptime());
        result.put("vmName", runtimeBean.getVmName());

        // CPU
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        result.put("availableProcessors", osBean.getAvailableProcessors());
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            result.put("processCpuLoad", String.format("%.2f%%", sunOs.getProcessCpuLoad() * 100));
        }

        return ResponseEntity.ok(result);
    }

    // ==================== 内部方法 ====================

    private Map<String, Object> formatThreadInfo(ThreadInfo ti, boolean includeStack) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", ti.getThreadName());
        info.put("id", ti.getThreadId());
        info.put("state", ti.getThreadState().name());
        info.put("blockedCount", ti.getBlockedCount());
        info.put("blockedTimeMs", ti.getBlockedTime());
        info.put("waitedCount", ti.getWaitedCount());
        info.put("waitedTimeMs", ti.getWaitedTime());

        if (ti.getLockName() != null) {
            info.put("lockName", ti.getLockName());
            info.put("lockOwnerId", ti.getLockOwnerId());
            info.put("lockOwnerName", ti.getLockOwnerName());
        }

        if (includeStack && ti.getStackTrace().length > 0) {
            List<String> stack = Arrays.stream(ti.getStackTrace())
                    .map(StackTraceElement::toString)
                    .collect(Collectors.toList());
            info.put("stackTrace", stack);
        }

        return info;
    }

    private Map<String, Object> formatMemoryUsage(MemoryUsage usage) {
        if (usage == null) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("initMB", usage.getInit() / 1024 / 1024);
        map.put("usedMB", usage.getUsed() / 1024 / 1024);
        map.put("committedMB", usage.getCommitted() / 1024 / 1024);
        map.put("maxMB", usage.getMax() > 0 ? usage.getMax() / 1024 / 1024 : -1);
        if (usage.getMax() > 0) {
            map.put("usagePercent", String.format("%.1f%%", (double) usage.getUsed() / usage.getMax() * 100));
        }
        return map;
    }
}
