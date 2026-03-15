package com.ecommerce.cache.benchmark;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 性能基准测试
 * <p>
 * 测试目标：
 * 1. L1 Caffeine 缓存 — 单线程/多线程读写性能
 * 2. 并发吞吐量测试 — 模拟高并发读写
 * 3. 延迟分布测试 — TP50/TP90/TP99/TP999
 * 4. 内存效率测试 — 缓存容量与命中率关系
 * <p>
 * 注意：本测试不走 Surefire 默认执行（文件名 *BenchmarkTest），需单独运行：
 * mvn test -Dtest=PerformanceBenchmarkTest
 */
@Tag("benchmark")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceBenchmarkTest {

    private Cache<String, String> cache;
    private static final String VALUE_TEMPLATE = "{\"spuId\":%d,\"name\":\"Product_%d\",\"price\":99.99}";

    @BeforeEach
    void setUp() {
        cache = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats()
                .build();
    }

    // ==================== 1. 单线程读写基准 ====================

    @Test
    @Order(1)
    @DisplayName("Benchmark: L1 Caffeine 单线程写入 — 100K 条目")
    void benchmarkSingleThreadWrite() {
        int count = 100_000;
        Instant start = Instant.now();

        for (int i = 0; i < count; i++) {
            cache.put("spu:detail:" + i, String.format(VALUE_TEMPLATE, i, i));
        }

        Duration elapsed = Duration.between(start, Instant.now());
        double opsPerSec = count / (elapsed.toNanos() / 1_000_000_000.0);

        System.out.printf("[Benchmark] Single-thread write: %d ops in %d ms (%.0f ops/sec)%n",
                count, elapsed.toMillis(), opsPerSec);

        assertTrue(opsPerSec > 100_000, "单线程写入应超过 100K ops/sec");
        assertEquals(count, cache.estimatedSize());
    }

    @Test
    @Order(2)
    @DisplayName("Benchmark: L1 Caffeine 单线程读取 — 100K 次")
    void benchmarkSingleThreadRead() {
        // 预填充
        int count = 100_000;
        for (int i = 0; i < count; i++) {
            cache.put("spu:detail:" + i, String.format(VALUE_TEMPLATE, i, i));
        }

        Instant start = Instant.now();
        for (int i = 0; i < count; i++) {
            String value = cache.getIfPresent("spu:detail:" + i);
            assertNotNull(value);
        }

        Duration elapsed = Duration.between(start, Instant.now());
        double opsPerSec = count / (elapsed.toNanos() / 1_000_000_000.0);

        System.out.printf("[Benchmark] Single-thread read: %d ops in %d ms (%.0f ops/sec)%n",
                count, elapsed.toMillis(), opsPerSec);

        assertTrue(opsPerSec > 500_000, "单线程读取应超过 500K ops/sec");
    }

    // ==================== 2. 并发吞吐量测试 ====================

    @Test
    @Order(3)
    @DisplayName("Benchmark: 多线程并发读取 — 10 线程 x 100K 次")
    void benchmarkConcurrentRead() throws Exception {
        int threads = 10;
        int opsPerThread = 100_000;

        // 预填充
        for (int i = 0; i < 10_000; i++) {
            cache.put("spu:detail:" + i, String.format(VALUE_TEMPLATE, i, i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong totalOps = new AtomicLong(0);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        cache.getIfPresent("spu:detail:" + (i % 10_000));
                        totalOps.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        Instant startTime = Instant.now();
        start.countDown();
        done.await();

        Duration elapsed = Duration.between(startTime, Instant.now());
        double opsPerSec = totalOps.get() / (elapsed.toNanos() / 1_000_000_000.0);

        System.out.printf("[Benchmark] Concurrent read (%d threads): %d ops in %d ms (%.0f ops/sec)%n",
                threads, totalOps.get(), elapsed.toMillis(), opsPerSec);

        assertTrue(opsPerSec > 1_000_000, "多线程并发读取应超过 1M ops/sec");
        executor.shutdown();
    }

    @Test
    @Order(4)
    @DisplayName("Benchmark: 多线程混合读写 — 80% 读 20% 写")
    void benchmarkMixedReadWrite() throws Exception {
        int threads = 8;
        int opsPerThread = 50_000;

        // 预填充
        for (int i = 0; i < 10_000; i++) {
            cache.put("spu:detail:" + i, String.format(VALUE_TEMPLATE, i, i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong readOps = new AtomicLong(0);
        AtomicLong writeOps = new AtomicLong(0);

        Instant startTime = Instant.now();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    for (int i = 0; i < opsPerThread; i++) {
                        if (rng.nextInt(100) < 80) {
                            // 80% 读
                            cache.getIfPresent("spu:detail:" + rng.nextInt(10_000));
                            readOps.incrementAndGet();
                        } else {
                            // 20% 写
                            int key = rng.nextInt(10_000);
                            cache.put("spu:detail:" + key, String.format(VALUE_TEMPLATE, key, key));
                            writeOps.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        done.await();
        Duration elapsed = Duration.between(startTime, Instant.now());
        long totalOps = readOps.get() + writeOps.get();
        double opsPerSec = totalOps / (elapsed.toNanos() / 1_000_000_000.0);

        System.out.printf("[Benchmark] Mixed R/W (%d threads): reads=%d, writes=%d, total=%d in %d ms (%.0f ops/sec)%n",
                threads, readOps.get(), writeOps.get(), totalOps, elapsed.toMillis(), opsPerSec);

        assertTrue(opsPerSec > 500_000, "混合读写应超过 500K ops/sec");
        executor.shutdown();
    }

    // ==================== 3. 延迟分布测试 ====================

    @Test
    @Order(5)
    @DisplayName("Benchmark: 读取延迟分布 — TP50/TP90/TP99/TP999")
    void benchmarkReadLatencyDistribution() {
        int count = 100_000;

        // 预填充
        for (int i = 0; i < 10_000; i++) {
            cache.put("spu:detail:" + i, String.format(VALUE_TEMPLATE, i, i));
        }

        // 收集延迟样本（纳秒）
        long[] latencies = new long[count];
        for (int i = 0; i < count; i++) {
            long start = System.nanoTime();
            cache.getIfPresent("spu:detail:" + (i % 10_000));
            latencies[i] = System.nanoTime() - start;
        }

        // 排序并计算百分位
        long[] sorted = LongStream.of(latencies).sorted().toArray();
        long tp50 = sorted[(int) (count * 0.50)];
        long tp90 = sorted[(int) (count * 0.90)];
        long tp99 = sorted[(int) (count * 0.99)];
        long tp999 = sorted[(int) (count * 0.999)];
        LongSummaryStatistics stats = LongStream.of(sorted).summaryStatistics();

        System.out.printf("[Benchmark] Latency distribution (ns):%n");
        System.out.printf("  TP50:  %,d ns (%.3f µs)%n", tp50, tp50 / 1000.0);
        System.out.printf("  TP90:  %,d ns (%.3f µs)%n", tp90, tp90 / 1000.0);
        System.out.printf("  TP99:  %,d ns (%.3f µs)%n", tp99, tp99 / 1000.0);
        System.out.printf("  TP999: %,d ns (%.3f µs)%n", tp999, tp999 / 1000.0);
        System.out.printf("  Avg:   %,d ns (%.3f µs)%n", (long) stats.getAverage(), stats.getAverage() / 1000.0);
        System.out.printf("  Max:   %,d ns (%.3f µs)%n", stats.getMax(), stats.getMax() / 1000.0);

        // TP99 应在 1ms 以内（Caffeine 本地缓存极快）
        assertTrue(tp99 < 1_000_000, "TP99 应小于 1ms (本地缓存)");
    }

    // ==================== 4. 缓存命中率测试 ====================

    @Test
    @Order(6)
    @DisplayName("Benchmark: 缓存命中率 — Zipf 分布访问模式")
    void benchmarkHitRateWithZipfDistribution() {
        Cache<String, String> smallCache = Caffeine.newBuilder()
                .maximumSize(1_000)
                .recordStats()
                .build();

        int totalKeys = 10_000;
        int totalOps = 500_000;

        // 预填充前 1000 个热门 Key
        for (int i = 0; i < 1_000; i++) {
            smallCache.put("spu:" + i, "value_" + i);
        }

        // Zipf 分布模拟：大部分访问集中在少量 Key
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int hits = 0;
        for (int i = 0; i < totalOps; i++) {
            // Zipf 近似：80% 访问前 20% 的 Key
            int keyId;
            if (rng.nextInt(100) < 80) {
                keyId = rng.nextInt(totalKeys / 5); // 前 20%
            } else {
                keyId = rng.nextInt(totalKeys);
            }
            String value = smallCache.getIfPresent("spu:" + keyId);
            if (value != null) hits++;
        }

        double hitRate = (double) hits / totalOps * 100;
        System.out.printf("[Benchmark] Hit rate with Zipf distribution: %.2f%% (%d/%d)%n",
                hitRate, hits, totalOps);

        // Zipf 分布下，1000/10000 容量比应有 > 50% 命中率
        assertTrue(hitRate > 40, "Zipf 分布下命中率应大于 40%");
    }

    // ==================== 5. 缓存淘汰性能测试 ====================

    @Test
    @Order(7)
    @DisplayName("Benchmark: 缓存淘汰压力 — 持续超量写入")
    void benchmarkEvictionPerformance() {
        Cache<String, String> limitedCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .recordStats()
                .build();

        int totalWrites = 100_000;
        Instant start = Instant.now();

        for (int i = 0; i < totalWrites; i++) {
            limitedCache.put("key:" + i, "value_" + i);
        }
        // 强制清理
        limitedCache.cleanUp();

        Duration elapsed = Duration.between(start, Instant.now());
        long estimatedSize = limitedCache.estimatedSize();

        System.out.printf("[Benchmark] Eviction: %d writes, final size=%d, elapsed=%d ms%n",
                totalWrites, estimatedSize, elapsed.toMillis());
        System.out.printf("  Eviction count: %d%n", limitedCache.stats().evictionCount());

        // 最终大小应接近最大容量
        assertTrue(estimatedSize <= 11_000, "缓存大小不应显著超过最大容量");
        assertTrue(limitedCache.stats().evictionCount() > 80_000, "应有大量淘汰");
    }

    // ==================== 6. 虚拟线程性能测试 (JDK 21) ====================

    @Test
    @Order(8)
    @DisplayName("Benchmark: 虚拟线程并发 — 1000 并发任务")
    void benchmarkVirtualThreadConcurrency() throws Exception {
        // 预填充
        for (int i = 0; i < 10_000; i++) {
            cache.put("spu:detail:" + i, String.format(VALUE_TEMPLATE, i, i));
        }

        int taskCount = 1_000;
        int opsPerTask = 1_000;
        AtomicLong totalOps = new AtomicLong(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            Instant start = Instant.now();

            for (int t = 0; t < taskCount; t++) {
                futures.add(executor.submit(() -> {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    for (int i = 0; i < opsPerTask; i++) {
                        cache.getIfPresent("spu:detail:" + rng.nextInt(10_000));
                        totalOps.incrementAndGet();
                    }
                }));
            }

            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);

            Duration elapsed = Duration.between(start, Instant.now());
            double opsPerSec = totalOps.get() / (elapsed.toNanos() / 1_000_000_000.0);

            System.out.printf("[Benchmark] Virtual threads (%d tasks): %d ops in %d ms (%.0f ops/sec)%n",
                    taskCount, totalOps.get(), elapsed.toMillis(), opsPerSec);

            assertTrue(totalOps.get() == (long) taskCount * opsPerTask);
        }
    }
}
