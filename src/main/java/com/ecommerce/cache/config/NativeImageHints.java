package com.ecommerce.cache.config;

import com.ecommerce.cache.entity.SpuEntity;
import com.ecommerce.cache.dto.SpuDetailDTO;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * GraalVM Native Image 运行时提示配置
 * <p>
 * 为 GraalVM 原生编译提供反射、资源、序列化等提示信息，
 * 确保 Native Image 在 AOT 编译模式下能正确发现动态特性。
 * <p>
 * Spring Boot 3.x 使用 {@link RuntimeHintsRegistrar} 机制替代传统的
 * reflect-config.json 手动配置（两者可共存，本类作为补充）。
 */
@Configuration
@ImportRuntimeHints(NativeImageHints.SpuCacheRuntimeHints.class)
public class NativeImageHints {

    /**
     * SPU 多级缓存服务运行时提示注册器
     */
    static class SpuCacheRuntimeHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // ---- 反射提示：JPA 实体 ----
            hints.reflection()
                    .registerType(SpuEntity.class,
                            MemberCategory.DECLARED_FIELDS,
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                            MemberCategory.INVOKE_PUBLIC_METHODS)
                    .registerType(SpuDetailDTO.class,
                            MemberCategory.DECLARED_FIELDS,
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                            MemberCategory.INVOKE_PUBLIC_METHODS);

            // ---- 资源提示：配置文件 + Lua 脚本 ----
            hints.resources()
                    .registerPattern("application*.yml")
                    .registerPattern("bootstrap.yml")
                    .registerPattern("logback-spring.xml")
                    .registerPattern("lua/*")
                    .registerPattern("db/migration/*")
                    .registerPattern("META-INF/native-image/**");

            // ---- 序列化提示：Redisson 分布式对象 ----
            hints.serialization()
                    .registerType(String.class)
                    .registerType(Long.class)
                    .registerType(Integer.class);

            // ---- JNI 提示（如果使用了 JNI 加速） ----
            // hints.jni().registerType(...);

            // ---- 代理提示：Spring AOP / MyBatis 接口 ----
            // Spring AOT 自动处理大部分代理，此处仅补充自定义接口
            // hints.proxies().registerJdkProxy(...);
        }
    }
}
