package com.ecommerce.cache.optimization.v13;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V13终极巅峰缓存协调器
 * 统一调度分布式事务、多活数据中心、智能压缩、异常检测
 */
@Component
public class CacheCoordinatorV13 {

    private static final Logger log = LoggerFactory.getLogger(CacheCoordinatorV13.class);

    private final OptimizationV13Properties properties;
    private final DistributedTransactionCacheEngine transactionEngine;
    private final MultiActiveDataCenterEngine dataCenterEngine;
    private final SmartCompressionEngine compressionEngine;
    private final RealTimeAnomalyDetector anomalyDetector;
    private final MeterRegistry meterRegistry;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService coordinator;

    public CacheCoordinatorV13(
            OptimizationV13Properties properties,
            DistributedTransactionCacheEngine transactionEngine,
            MultiActiveDataCenterEngine dataCenterEngine,
            SmartCompressionEngine compressionEngine,
            RealTimeAnomalyDetector anomalyDetector,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.transactionEngine = transactionEngine;
        this.dataCenterEngine = dataCenterEngine;
        this.compressionEngine = compressionEngine;
        this.anomalyDetector = anomalyDetector;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("V13终极巅峰模块已禁用");
            return;
        }

        coordinator = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "v13-coordinator");
            t.setDaemon(true);
            return t;
        });

        // 启动协调任务
        coordinator.scheduleAtFixedRate(this::coordinateComponents, 5, 5, TimeUnit.SECONDS);
        
        // 注册默认异常修复策略
        anomalyDetector.registerDefaultRemediation();

        running.set(true);
        log.info("V13终极巅峰协调器初始化完成");
    }

    /**
     * 组件协调
     */
    private void coordinateComponents() {
        try {
            // 收集各组件指标
            collectMetrics();
            
            // 协调多活数据中心
            if (properties.getMultiActiveDataCenter().isEnabled()) {
                coordinateDataCenters();
            }
            
            // 协调事务引擎
            if (properties.getDistributedTransaction().isEnabled()) {
                coordinateTransactions();
            }
            
        } catch (Exception e) {
            log.error("V13协调任务异常", e);
        }
    }

    private void collectMetrics() {
        // 记录事务指标
        if (properties.getDistributedTransaction().isEnabled()) {
            Map<String, Object> txStats = transactionEngine.getStats();
            anomalyDetector.record("v13.tx.active", 
                    ((Number) txStats.get("activeTransactions")).doubleValue());
        }
        
        // 记录数据中心指标
        if (properties.getMultiActiveDataCenter().isEnabled()) {
            Map<String, Object> dcStatus = dataCenterEngine.getStatus();
            anomalyDetector.record("v13.dc.data_count", 
                    ((Number) dcStatus.get("dataCount")).doubleValue());
        }
        
        // 记录压缩指标
        if (properties.getSmartCompression().isEnabled()) {
            Map<String, Object> compStats = compressionEngine.getStats();
            long bytesIn = ((Number) compStats.get("totalBytesIn")).longValue();
            long bytesOut = ((Number) compStats.get("totalBytesOut")).longValue();
            if (bytesIn > 0) {
                anomalyDetector.record("v13.compression.ratio", (double) bytesOut / bytesIn);
            }
        }
    }

    private void coordinateDataCenters() {
        // 检查Leader状态
        if (!dataCenterEngine.isLeader()) {
            log.debug("当前节点非Leader，跳过数据中心协调");
            return;
        }
        
        // 检查复制延迟
        Map<String, Object> status = dataCenterEngine.getStatus();
        int queueSize = (int) status.get("replicationQueueSize");
        
        if (queueSize > 1000) {
            log.warn("复制队列积压: size={}", queueSize);
            anomalyDetector.record("v13.dc.replication_backlog", queueSize);
        }
    }

    private void coordinateTransactions() {
        Map<String, Object> stats = transactionEngine.getStats();
        int activeTx = ((Number) stats.get("activeTransactions")).intValue();
        int maxTx = properties.getDistributedTransaction().getMaxConcurrentTransactions();
        
        if (activeTx > maxTx * 0.8) {
            log.warn("事务数接近上限: {}/{}", activeTx, maxTx);
        }
    }

    /**
     * 事务性写入（带压缩和多DC复制）
     */
    public void putWithTransaction(String key, Object value) {
        String txId = transactionEngine.beginTransaction();
        try {
            // 压缩数据
            byte[] compressed = null;
            if (properties.getSmartCompression().isEnabled() && value instanceof byte[]) {
                SmartCompressionEngine.CompressedData cd = compressionEngine.compress((byte[]) value);
                compressed = cd.getData();
            }
            
            // 事务写入
            transactionEngine.transactionalPut(txId, key, compressed != null ? compressed : value);
            
            // 提交
            transactionEngine.commit(txId);
            
            // 多DC复制
            if (properties.getMultiActiveDataCenter().isEnabled()) {
                dataCenterEngine.put(key, compressed != null ? compressed : value);
            }
            
        } catch (Exception e) {
            transactionEngine.rollback(txId);
            throw e;
        }
    }

    /**
     * 获取综合状态
     */
    public Map<String, Object> getComprehensiveStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("running", running.get());
        
        if (properties.getDistributedTransaction().isEnabled()) {
            status.put("transaction", transactionEngine.getStats());
        }
        
        if (properties.getMultiActiveDataCenter().isEnabled()) {
            status.put("dataCenter", dataCenterEngine.getStatus());
        }
        
        if (properties.getSmartCompression().isEnabled()) {
            status.put("compression", compressionEngine.getStats());
        }
        
        if (properties.getAnomalyDetection().isEnabled()) {
            status.put("anomalyDetection", anomalyDetector.getStats());
        }
        
        return status;
    }

    public boolean isRunning() {
        return running.get();
    }

    public OptimizationV13Properties getProperties() {
        return properties;
    }

    public DistributedTransactionCacheEngine getTransactionEngine() {
        return transactionEngine;
    }

    public MultiActiveDataCenterEngine getDataCenterEngine() {
        return dataCenterEngine;
    }

    public SmartCompressionEngine getCompressionEngine() {
        return compressionEngine;
    }

    public RealTimeAnomalyDetector getAnomalyDetector() {
        return anomalyDetector;
    }
}
