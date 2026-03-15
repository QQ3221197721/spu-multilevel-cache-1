package com.ecommerce.cache.chaos;

import com.ecommerce.cache.optimization.v10.ChaosEngineeringTester;
import com.ecommerce.cache.optimization.v10.ChaosEngineeringTester.*;
import com.ecommerce.cache.optimization.v10.OptimizationV10Properties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 混沌工程测试
 * <p>
 * 验证 ChaosEngineeringTester 的核心功能：
 * 1. 实验生命周期管理（启动 / 停止 / 超时）
 * 2. 故障注入器注册与执行
 * 3. 故障概率控制
 * 4. 全局开关
 * 5. 实验历史记录
 */
@Tag("chaos")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChaosEngineeringTest {

    private ChaosEngineeringTester tester;
    private OptimizationV10Properties properties;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        properties = new OptimizationV10Properties();
        properties.getChaos().setEnabled(false); // 默认禁用
        properties.getChaos().setFailureRate(0.5);
        properties.getChaos().setLatencyInjectionMs(10);
        properties.getChaos().setExperimentDurationSec(5);

        tester = new ChaosEngineeringTester(meterRegistry, properties);
        tester.init();
    }

    @AfterEach
    void tearDown() {
        tester.shutdown();
    }

    // ==================== 1. 全局开关 ====================

    @Test
    @Order(1)
    @DisplayName("全局开关 - 默认禁用状态")
    void testGlobalSwitch_defaultDisabled() {
        assertFalse(tester.isChaosEnabled());
    }

    @Test
    @Order(2)
    @DisplayName("全局开关 - 启用混沌测试")
    void testGlobalSwitch_enable() {
        tester.enableChaos();
        assertTrue(tester.isChaosEnabled());
    }

    @Test
    @Order(3)
    @DisplayName("全局开关 - 禁用混沌测试")
    void testGlobalSwitch_disable() {
        tester.enableChaos();
        assertTrue(tester.isChaosEnabled());

        tester.disableChaos();
        assertFalse(tester.isChaosEnabled());
    }

    @Test
    @Order(4)
    @DisplayName("全局开关 - 配置启用时自动激活")
    void testGlobalSwitch_configEnabled() {
        properties.getChaos().setEnabled(true);
        ChaosEngineeringTester enabledTester = new ChaosEngineeringTester(meterRegistry, properties);
        enabledTester.init();

        assertTrue(enabledTester.isChaosEnabled());
        enabledTester.shutdown();
    }

    // ==================== 2. 实验生命周期 ====================

    @Test
    @Order(5)
    @DisplayName("实验管理 - 禁用时启动实验返回失败")
    void testExperiment_disabledReturnsFailure() {
        ChaosExperimentConfig config = new ChaosExperimentConfig(
                "exp-001", "network-latency", 10, Map.of());

        ExperimentResult result = tester.startExperiment(config);

        assertFalse(result.isSuccess());
        assertEquals("Chaos engineering is disabled", result.getMessage());
    }

    @Test
    @Order(6)
    @DisplayName("实验管理 - 启用后正常启动实验")
    void testExperiment_startSuccess() {
        tester.enableChaos();
        ChaosExperimentConfig config = new ChaosExperimentConfig(
                "exp-002", "network-latency", 60, Map.of());

        ExperimentResult result = tester.startExperiment(config);

        assertTrue(result.isSuccess());
        assertEquals("Experiment started", result.getMessage());
        assertNotNull(result.getStartTime());

        // 清理
        tester.stopExperiment("exp-002");
    }

    @Test
    @Order(7)
    @DisplayName("实验管理 - 停止实验")
    void testExperiment_stopSuccess() {
        tester.enableChaos();
        ChaosExperimentConfig config = new ChaosExperimentConfig(
                "exp-003", "cache-miss", 60, Map.of());
        tester.startExperiment(config);

        ExperimentResult result = tester.stopExperiment("exp-003");

        assertTrue(result.isSuccess());
        assertEquals("Experiment stopped", result.getMessage());
        assertNotNull(result.getEndTime());
    }

    @Test
    @Order(8)
    @DisplayName("实验管理 - 停止不存在的实验")
    void testExperiment_stopNotFound() {
        ExperimentResult result = tester.stopExperiment("non-existent");

        assertFalse(result.isSuccess());
        assertEquals("Experiment not found", result.getMessage());
    }

    @Test
    @Order(9)
    @DisplayName("实验管理 - 停止所有实验")
    void testExperiment_stopAll() {
        tester.enableChaos();

        tester.startExperiment(new ChaosExperimentConfig("exp-a", "network-latency", 60, Map.of()));
        tester.startExperiment(new ChaosExperimentConfig("exp-b", "cache-miss", 60, Map.of()));
        tester.startExperiment(new ChaosExperimentConfig("exp-c", "connection-timeout", 60, Map.of()));

        tester.stopAllExperiments();

        Map<String, Object> status = tester.getStatus();
        assertEquals(0, status.get("activeExperiments"));
    }

    // ==================== 3. 故障注入 ====================

    @Test
    @Order(10)
    @DisplayName("故障注入 - 禁用时不注入")
    void testFaultInjection_disabled() {
        assertFalse(tester.shouldInjectFault("network-latency"));
    }

    @Test
    @Order(11)
    @DisplayName("故障注入 - 启用时按概率注入")
    void testFaultInjection_probabilistic() {
        tester.enableChaos();
        properties.getChaos().setFailureRate(1.0); // 100% 注入

        assertTrue(tester.shouldInjectFault("network-latency"));
    }

    @Test
    @Order(12)
    @DisplayName("故障注入 - 零概率不注入")
    void testFaultInjection_zeroProbability() {
        tester.enableChaos();
        properties.getChaos().setFailureRate(0.0); // 0% 注入

        assertFalse(tester.shouldInjectFault("network-latency"));
    }

    // ==================== 4. 延迟注入 ====================

    @Test
    @Order(13)
    @DisplayName("延迟注入 - 禁用时不注入延迟")
    void testLatencyInjection_disabled() {
        long start = System.currentTimeMillis();
        tester.maybeInjectLatency();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 50, "禁用时不应有延迟注入");
    }

    @Test
    @Order(14)
    @DisplayName("延迟注入 - 100% 概率注入延迟")
    void testLatencyInjection_fullProbability() {
        tester.enableChaos();
        properties.getChaos().setFailureRate(1.0);
        properties.getChaos().setLatencyInjectionMs(50);

        long start = System.currentTimeMillis();
        tester.maybeInjectLatency();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 40, "应有 ~50ms 延迟注入");
    }

    // ==================== 5. 自定义故障注入器 ====================

    @Test
    @Order(15)
    @DisplayName("自定义注入器 - 注册并执行")
    void testCustomInjector() {
        tester.enableChaos();

        final boolean[] injected = {false};
        final boolean[] restored = {false};

        tester.registerFaultInjector("custom-fault", new FaultInjector() {
            @Override
            public void inject(Map<String, Object> params) {
                injected[0] = true;
            }

            @Override
            public void restore() {
                restored[0] = true;
            }
        });

        ChaosExperimentConfig config = new ChaosExperimentConfig(
                "exp-custom", "custom-fault", 60, Map.of("param1", "value1"));

        tester.startExperiment(config);
        assertTrue(injected[0], "应调用 inject()");

        tester.stopExperiment("exp-custom");
        assertTrue(restored[0], "应调用 restore()");
    }

    // ==================== 6. 状态查询 ====================

    @Test
    @Order(16)
    @DisplayName("状态查询 - 返回完整状态信息")
    void testGetStatus() {
        Map<String, Object> status = tester.getStatus();

        assertNotNull(status);
        assertFalse((Boolean) status.get("enabled"));
        assertEquals(0, status.get("activeExperiments"));
        assertNotNull(status.get("registeredInjectors"));
        assertNotNull(status.get("statistics"));
    }

    @Test
    @Order(17)
    @DisplayName("状态查询 - 默认注入器列表")
    @SuppressWarnings("unchecked")
    void testGetStatus_defaultInjectors() {
        Map<String, Object> status = tester.getStatus();
        var injectors = (Iterable<String>) status.get("registeredInjectors");

        List<String> injectorList = new java.util.ArrayList<>();
        injectors.forEach(injectorList::add);

        assertTrue(injectorList.contains("network-latency"));
        assertTrue(injectorList.contains("connection-timeout"));
        assertTrue(injectorList.contains("memory-pressure"));
        assertTrue(injectorList.contains("cpu-spike"));
        assertTrue(injectorList.contains("cache-miss"));
        assertTrue(injectorList.contains("partial-failure"));
    }

    // ==================== 7. 实验历史 ====================

    @Test
    @Order(18)
    @DisplayName("实验历史 - 停止的实验记录到历史")
    void testExperimentHistory() {
        tester.enableChaos();

        tester.startExperiment(new ChaosExperimentConfig("hist-1", "cache-miss", 60, Map.of()));
        tester.stopExperiment("hist-1");
        tester.startExperiment(new ChaosExperimentConfig("hist-2", "network-latency", 60, Map.of()));
        tester.stopExperiment("hist-2");

        List<ExperimentResult> history = tester.getHistory(10);

        assertEquals(2, history.size());
    }

    // ==================== 8. 统计指标 ====================

    @Test
    @Order(19)
    @DisplayName("统计指标 - 实验计数递增")
    @SuppressWarnings("unchecked")
    void testStatistics_experimentCount() {
        tester.enableChaos();

        tester.startExperiment(new ChaosExperimentConfig("stat-1", "cache-miss", 60, Map.of()));
        tester.startExperiment(new ChaosExperimentConfig("stat-2", "cache-miss", 60, Map.of()));

        Map<String, Object> status = tester.getStatus();
        Map<String, Object> statistics = (Map<String, Object>) status.get("statistics");

        assertEquals(2L, statistics.get("totalExperiments"));

        tester.stopAllExperiments();
    }
}
