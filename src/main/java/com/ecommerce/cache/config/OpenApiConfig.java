package com.ecommerce.cache.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.0 文档配置
 */
@Configuration
public class OpenApiConfig {
    
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("SPU 多级缓存服务 API")
                .version("1.0.0")
                .description("""
                    电商 SPU 详情页多级缓存服务 API 文档
                    
                    ## 核心特性
                    - 支持 10W QPS，亿级日活
                    - TP99 < 50ms
                    - 四级缓存：CDN → L1 Caffeine → L2 Redis → L3 Memcached
                    
                    ## 缓存异常治理
                    - 布隆过滤器防穿透
                    - DCL + 分布式锁防击穿
                    - 随机 TTL + 预热防雪崩
                    - 滑动窗口热点检测 + 自动分片
                    """)
                .contact(new Contact()
                    .name("Cache Team")
                    .email("cache-team@ecommerce.com"))
                .license(new License()
                    .name("Apache 2.0")
                    .url("https://www.apache.org/licenses/LICENSE-2.0")))
            .servers(List.of(
                new Server().url("http://localhost:8080").description("本地开发环境"),
                new Server().url("https://api.ecommerce.com").description("生产环境")
            ));
    }
}
