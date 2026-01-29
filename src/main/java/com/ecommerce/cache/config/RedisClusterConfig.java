package com.ecommerce.cache.config;

import io.lettuce.core.ReadFrom;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;

/**
 * Redis Cluster 配置
 * 10 主 10 从，开启 io-threads 4
 * 目标：80W OPS，命中率 58%，延迟 2ms
 */
@Configuration
public class RedisClusterConfig {
    
    private static final Logger log = LoggerFactory.getLogger(RedisClusterConfig.class);

    @Value("${spring.data.redis.cluster.nodes}")
    private List<String> clusterNodes;

    @Value("${spring.data.redis.password:}")
    private String password;

    /**
     * Lettuce 客户端资源配置
     * 配置 IO 线程数和计算线程数
     */
    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return DefaultClientResources.builder()
            // IO 线程数（对应 redis.conf 的 io-threads）
            .ioThreadPoolSize(4)
            // 计算线程数
            .computationThreadPoolSize(4)
            .build();
    }

    /**
     * 连接池配置
     */
    @Bean
    public GenericObjectPoolConfig<?> redisPoolConfig() {
        GenericObjectPoolConfig<?> config = new GenericObjectPoolConfig<>();
        // 最大连接数
        config.setMaxTotal(200);
        // 最大空闲连接
        config.setMaxIdle(50);
        // 最小空闲连接
        config.setMinIdle(20);
        // 获取连接最大等待时间
        config.setMaxWait(Duration.ofMillis(1000));
        // 空闲连接检测
        config.setTestWhileIdle(true);
        config.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        // 借用连接时检测
        config.setTestOnBorrow(true);
        return config;
    }

    /**
     * 集群拓扑刷新配置
     */
    @Bean
    public ClusterTopologyRefreshOptions clusterTopologyRefreshOptions() {
        return ClusterTopologyRefreshOptions.builder()
            // 开启周期性刷新
            .enablePeriodicRefresh(Duration.ofSeconds(30))
            // 开启自适应刷新（连接断开、重定向等触发）
            .enableAllAdaptiveRefreshTriggers()
            // 自适应刷新超时
            .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * Lettuce 客户端配置
     */
    @Bean
    public LettucePoolingClientConfiguration lettuceClientConfiguration(
            ClientResources clientResources,
            GenericObjectPoolConfig<?> poolConfig,
            ClusterTopologyRefreshOptions topologyRefreshOptions) {
        
        // 集群客户端选项
        ClusterClientOptions clientOptions = ClusterClientOptions.builder()
            .topologyRefreshOptions(topologyRefreshOptions)
            // 自动重连
            .autoReconnect(true)
            // 命令超时时取消命令
            .cancelCommandsOnReconnectFailure(false)
            // 请求队列大小
            .requestQueueSize(1000)
            .build();

        return LettucePoolingClientConfiguration.builder()
            .clientResources(clientResources)
            .clientOptions(clientOptions)
            .poolConfig(poolConfig)
            // 命令超时
            .commandTimeout(Duration.ofMillis(1000))
            // 读取优先从节点（读写分离）
            .readFrom(ReadFrom.REPLICA_PREFERRED)
            .build();
    }

    /**
     * Redis 集群连接工厂
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            LettucePoolingClientConfiguration lettuceClientConfiguration) {
        
        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(clusterNodes);
        clusterConfig.setMaxRedirects(3);
        
        if (password != null && !password.isEmpty()) {
            clusterConfig.setPassword(password);
        }
        
        log.info("Redis Cluster configured with nodes: {}", clusterNodes);
        
        return new LettuceConnectionFactory(clusterConfig, lettuceClientConfiguration);
    }

    /**
     * RedisTemplate 配置（用于对象序列化）
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Key 使用 String 序列化
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Value 使用 JSON 序列化
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        
        template.afterPropertiesSet();
        return template;
    }

    /**
     * StringRedisTemplate（用于纯字符串操作）
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
