package com.ecommerce.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Spring Boot 应用启动测试
 */
@SpringBootTest
@ActiveProfiles("test")
class SpuCacheApplicationTest {
    
    @Test
    @DisplayName("应用上下文加载测试")
    void contextLoads() {
        assertDoesNotThrow(() -> {
            // 如果上下文加载成功，测试通过
        });
    }
}
