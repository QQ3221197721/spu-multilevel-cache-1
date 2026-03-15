package com.ecommerce.cache.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ecommerce.cache.config.CanalProperties;
import com.ecommerce.cache.service.CacheConsistencyService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Canal Binlog 监听器 — 缓存一致性核心组件
 * <p>
 * 数据流:
 *   MySQL binlog → Canal Server → Kafka(CANAL_BINLOG_TOPIC) → 本监听器 → CacheConsistencyService → L1/L2/L3 缓存失效
 * <p>
 * Canal 以 FlatMessage(JSON) 格式发送 binlog 事件，本监听器负责：
 * 1. 解析 Canal FlatMessage（表名、事件类型、变更数据、主键）
 * 2. 根据表名映射生成对应的缓存 Key 列表
 * 3. 委托 CacheConsistencyService 执行缓存失效（延迟双删 + 版本校验）
 * 4. 记录 Prometheus 指标（binlog 延迟、处理量、失败量）
 */
@Component
@ConditionalOnProperty(name = "canal.enabled", havingValue = "true", matchIfMissing = true)
public class CanalBinlogListener {

    private static final Logger log = LoggerFactory.getLogger(CanalBinlogListener.class);

    private final CanalProperties canalProperties;
    private final CacheConsistencyService consistencyService;
    private final MeterRegistry meterRegistry;

    // 指标
    private Counter binlogReceivedCounter;
    private Counter binlogProcessedCounter;
    private Counter binlogErrorCounter;
    private Counter binlogSkippedCounter;
    private Timer binlogProcessTimer;

    // 统计
    private final LongAdder totalReceived = new LongAdder();
    private final LongAdder totalProcessed = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();

    // 表名 → 处理器映射
    private final Map<String, TableChangeHandler> tableHandlers = new ConcurrentHashMap<>();

    public CanalBinlogListener(CanalProperties canalProperties,
                                CacheConsistencyService consistencyService,
                                MeterRegistry meterRegistry) {
        this.canalProperties = canalProperties;
        this.consistencyService = consistencyService;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        // 注册 Prometheus 指标
        binlogReceivedCounter = Counter.builder("canal.binlog.received")
                .description("Canal binlog events received").register(meterRegistry);
        binlogProcessedCounter = Counter.builder("canal.binlog.processed")
                .description("Canal binlog events processed").register(meterRegistry);
        binlogErrorCounter = Counter.builder("canal.binlog.errors")
                .description("Canal binlog processing errors").register(meterRegistry);
        binlogSkippedCounter = Counter.builder("canal.binlog.skipped")
                .description("Canal binlog events skipped (DDL/QUERY)").register(meterRegistry);
        binlogProcessTimer = Timer.builder("canal.binlog.process.latency")
                .publishPercentileHistogram().register(meterRegistry);

        // 注册表处理器
        CanalProperties.TableMapping mapping = canalProperties.getTableMapping();
        tableHandlers.put("t_spu", new TableChangeHandler("t_spu", mapping.getSpuKeyPrefix(), "spu_id"));
        tableHandlers.put("t_sku", new TableChangeHandler("t_sku", mapping.getSkuKeyPrefix(), "sku_id"));
        tableHandlers.put("t_spu_detail", new TableChangeHandler("t_spu_detail", mapping.getSpuDetailKeyPrefix(), "spu_id"));
        tableHandlers.put("t_spu_attribute", new TableChangeHandler("t_spu_attribute", mapping.getSpuAttributeKeyPrefix(), "spu_id"));

        log.info("[CanalBinlogListener] Initialized with {} table handlers, topics=[{}, {}, {}]",
                tableHandlers.size(),
                canalProperties.getBinlogTopic(),
                canalProperties.getSpuBinlogTopic(),
                canalProperties.getSkuBinlogTopic());
    }

    @KafkaListener(
            topics = "${canal.binlog-topic:CANAL_BINLOG_TOPIC}",
            groupId = "${canal.consumer-group:CG_CANAL_BINLOG}",
            concurrency = "4"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        totalReceived.increment();
        binlogReceivedCounter.increment();

        binlogProcessTimer.record(() -> {
            try {
                processFlatMessage(record.value());
            } catch (Exception e) {
                totalErrors.increment();
                binlogErrorCounter.increment();
                log.error("[CanalBinlogListener] Failed to process binlog event", e);
                throw e; // 抛出触发 Kafka 重试
            }
        });
    }

    /**
     * 解析并处理 Canal FlatMessage
     */
    private void processFlatMessage(String messageBody) {
        JSONObject flatMsg = JSON.parseObject(messageBody);

        // 跳过 DDL
        Boolean isDdl = flatMsg.getBoolean("isDdl");
        if (Boolean.TRUE.equals(isDdl)) {
            binlogSkippedCounter.increment();
            log.debug("[CanalBinlogListener] Skipped DDL event: {}", flatMsg.getString("sql"));
            return;
        }

        String database = flatMsg.getString("database");
        String table = flatMsg.getString("table");
        String type = flatMsg.getString("type");
        Long binlogTs = flatMsg.getLong("ts");
        JSONArray dataArray = flatMsg.getJSONArray("data");
        JSONArray pkNames = flatMsg.getJSONArray("pkNames");

        if (table == null || type == null || dataArray == null || dataArray.isEmpty()) {
            binlogSkippedCounter.increment();
            return;
        }

        // 检查 binlog 延迟
        if (binlogTs != null) {
            long delaySeconds = (System.currentTimeMillis() / 1000) - binlogTs;
            if (delaySeconds > canalProperties.getConsistency().getMaxBinlogDelayAlertSeconds()) {
                log.warn("[CanalBinlogListener] Binlog delay too high: {}s, table={}, type={}",
                        delaySeconds, table, type);
            }
        }

        // 查找表处理器
        TableChangeHandler handler = tableHandlers.get(table);
        if (handler == null) {
            binlogSkippedCounter.increment();
            log.debug("[CanalBinlogListener] No handler for table: {}.{}", database, table);
            return;
        }

        // 提取变更的主键值 → 生成缓存 Key
        List<String> cacheKeys = handler.extractCacheKeys(dataArray, pkNames);
        if (cacheKeys.isEmpty()) {
            binlogSkippedCounter.increment();
            return;
        }

        log.info("[CanalBinlogListener] binlog event: db={}, table={}, type={}, keys={}",
                database, table, type, cacheKeys.size());

        // 委托一致性服务处理
        consistencyService.onBinlogEvent(table, type, cacheKeys, binlogTs);

        totalProcessed.increment();
        binlogProcessedCounter.increment();
    }

    /**
     * 表变更处理器 — 负责从 Canal data 中提取缓存 Key
     */
    private static class TableChangeHandler {
        private final String tableName;
        private final String cacheKeyPrefix;
        private final String primaryKeyColumn;

        TableChangeHandler(String tableName, String cacheKeyPrefix, String primaryKeyColumn) {
            this.tableName = tableName;
            this.cacheKeyPrefix = cacheKeyPrefix;
            this.primaryKeyColumn = primaryKeyColumn;
        }

        List<String> extractCacheKeys(JSONArray dataArray, JSONArray pkNames) {
            Set<String> keys = new LinkedHashSet<>();

            for (int i = 0; i < dataArray.size(); i++) {
                JSONObject row = dataArray.getJSONObject(i);
                if (row == null) continue;

                String pkValue = row.getString(primaryKeyColumn);
                if (pkValue == null && pkNames != null) {
                    for (int j = 0; j < pkNames.size(); j++) {
                        pkValue = row.getString(pkNames.getString(j));
                        if (pkValue != null) break;
                    }
                }

                if (pkValue != null) {
                    keys.add(cacheKeyPrefix + pkValue);

                    if ("t_sku".equals(tableName)) {
                        String spuId = row.getString("spu_id");
                        if (spuId != null) {
                            keys.add("spu:detail:" + spuId);
                        }
                    }
                }
            }

            return new ArrayList<>(keys);
        }
    }

    /**
     * 获取统计信息
     */
    public Map<String, Long> getStats() {
        return Map.of(
                "totalReceived", totalReceived.sum(),
                "totalProcessed", totalProcessed.sum(),
                "totalErrors", totalErrors.sum()
        );
    }
}
