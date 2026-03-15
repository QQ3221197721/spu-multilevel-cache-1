package com.ecommerce.cache.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.0 文档配置
 *
 * 提供：
 * 1. API 基础信息（标题、版本、描述、联系人、许可证）
 * 2. JWT Bearer Token 认证方案
 * 3. 接口分组 Tag 定义
 * 4. 多环境 Server 列表
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

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

                    ## 认证方式
                    所有受保护接口需在请求头携带 `Authorization: Bearer <token>`。
                    可通过 Swagger UI 右上角 "Authorize" 按钮输入 JWT Token。
                    """)
                .contact(new Contact()
                    .name("Cache Team")
                    .email("cache-team@ecommerce.com"))
                .license(new License()
                    .name("Apache 2.0")
                    .url("https://www.apache.org/licenses/LICENSE-2.0")))
            // JWT Bearer Token 认证
            .components(new Components()
                .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                    .name(SECURITY_SCHEME_NAME)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT 认证令牌，格式: Bearer {token}")))
            .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
            // 接口分组
            .tags(List.of(
                new Tag().name("SPU详情").description("SPU 商品详情查询、缓存刷新、预热等核心接口"),
                new Tag().name("缓存管理").description("缓存运维管理：统计、清除、布隆过滤器、熔断器、诊断"),
                new Tag().name("降级开关").description("服务降级控制：级别设置、细粒度开关、审计日志"),
                new Tag().name("审计日志").description("安全审计日志查询：综合检索、摘要统计、高风险操作")
            ))
            // 多环境服务器
            .servers(List.of(
                new Server().url("http://localhost:8080").description("本地开发环境"),
                new Server().url("https://api-staging.ecommerce.com").description("预发布环境"),
                new Server().url("https://api.ecommerce.com").description("生产环境")
            ));
    }
}
