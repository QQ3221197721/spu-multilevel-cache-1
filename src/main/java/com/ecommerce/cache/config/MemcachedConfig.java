package com.ecommerce.cache.config;

import net.spy.memcached.*;
import net.spy.memcached.transcoders.SerializingTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Memcached 配置
 * 双集群 20 节点，采用 Ketama 一致性哈希
 * 目标：命中率 4.3%，延迟 3ms，3 次重试后回源
 */
@Configuration
@ConfigurationProperties(prefix = "memcached")
public class MemcachedConfig {
    
    private static final Logger log = LoggerFactory.getLogger(MemcachedConfig.class);

    private ClusterConfig primary;
    private ClusterConfig backup;
    private int defaultExpiration = 600;
    private int retryCount = 3;
    private int retryInterval = 100;

    public static class ClusterConfig {
        private String servers;
        private PoolConfig pool;
        private String hashAlgorithm = "XXHASH";
        private String locatorType = "CONSISTENT";
        private String failureMode = "Redistribute";
        
        // Getters and Setters
        public String getServers() { return servers; }
        public void setServers(String servers) { this.servers = servers; }
        public PoolConfig getPool() { return pool; }
        public void setPool(PoolConfig pool) { this.pool = pool; }
        public String getHashAlgorithm() { return hashAlgorithm; }
        public void setHashAlgorithm(String hashAlgorithm) { this.hashAlgorithm = hashAlgorithm; }
        public String getLocatorType() { return locatorType; }
        public void setLocatorType(String locatorType) { this.locatorType = locatorType; }
        public String getFailureMode() { return failureMode; }
        public void setFailureMode(String failureMode) { this.failureMode = failureMode; }
    }

    public static class PoolConfig {
        private int minConnections = 10;
        private int maxConnections = 100;
        private long operationTimeout = 1000;
        private long connectionTimeout = 2000;
        
        // Getters and Setters
        public int getMinConnections() { return minConnections; }
        public void setMinConnections(int minConnections) { this.minConnections = minConnections; }
        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
        public long getOperationTimeout() { return operationTimeout; }
        public void setOperationTimeout(long operationTimeout) { this.operationTimeout = operationTimeout; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(long connectionTimeout) { this.connectionTimeout = connectionTimeout; }
    }

    /**
     * 主 Memcached 客户端
     */
    @Bean(name = "primaryMemcachedClient", destroyMethod = "shutdown")
    @Primary
    public MemcachedClient primaryMemcachedClient() throws IOException {
        log.info("Initializing primary Memcached client: {}", primary.getServers());
        return createMemcachedClient(primary);
    }

    /**
     * 灾备 Memcached 客户端
     */
    @Bean(name = "backupMemcachedClient", destroyMethod = "shutdown")
    public MemcachedClient backupMemcachedClient() throws IOException {
        log.info("Initializing backup Memcached client: {}", backup.getServers());
        return createMemcachedClient(backup);
    }

    private MemcachedClient createMemcachedClient(ClusterConfig config) throws IOException {
        // 解析服务器地址
        List<InetSocketAddress> addresses = Arrays.stream(config.getServers().split(","))
            .map(server -> {
                String[] parts = server.trim().split(":");
                return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
            })
            .collect(Collectors.toList());

        // 构建连接工厂
        ConnectionFactoryBuilder builder = new ConnectionFactoryBuilder()
            // Ketama 一致性哈希
            .setLocatorType(ConnectionFactoryBuilder.Locator.CONSISTENT)
            // 使用 Ketama Hash
            .setHashAlg(DefaultHashAlgorithm.KETAMA_HASH)
            // 失败模式：重新分发
            .setFailureMode(FailureMode.Redistribute)
            // 操作超时
            .setOpTimeout(config.getPool().getOperationTimeout())
            // 使用二进制协议（更高效）
            .setProtocol(ConnectionFactoryBuilder.Protocol.BINARY)
            // 禁用 Nagle 算法
            .setUseNagleAlgorithm(false)
            // 开启优化
            .setShouldOptimize(true)
            // 序列化器
            .setTranscoder(new SerializingTranscoder() {
                {
                    // 压缩阈值 1 KB
                    setCompressionThreshold(1024);
                }
            });

        return new MemcachedClient(builder.build(), addresses);
    }

    // Getters and Setters
    public ClusterConfig getPrimary() { return primary; }
    public void setPrimary(ClusterConfig primary) { this.primary = primary; }
    public ClusterConfig getBackup() { return backup; }
    public void setBackup(ClusterConfig backup) { this.backup = backup; }
    public int getDefaultExpiration() { return defaultExpiration; }
    public void setDefaultExpiration(int defaultExpiration) { this.defaultExpiration = defaultExpiration; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int getRetryInterval() { return retryInterval; }
    public void setRetryInterval(int retryInterval) { this.retryInterval = retryInterval; }
}
