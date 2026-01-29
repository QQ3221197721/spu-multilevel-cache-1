package com.ecommerce.cache.integration;

import com.ecommerce.cache.dto.ApiResponse;
import com.ecommerce.cache.dto.SpuDetailDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SPU 详情接口集成测试
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpuDetailIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Test
    @Order(1)
    @DisplayName("获取 SPU 详情 - 正常流程")
    void testGetSpuDetail() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/spu/detail/10001")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        
        String content = result.getResponse().getContentAsString();
        ApiResponse<SpuDetailDTO> response = objectMapper.readValue(content, 
            new TypeReference<ApiResponse<SpuDetailDTO>>() {});
        
        assertNotNull(response.getData());
    }
    
    @Test
    @Order(2)
    @DisplayName("获取不存在的 SPU")
    void testGetSpuDetail_notFound() throws Exception {
        mockMvc.perform(get("/api/spu/detail/999999999")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404));
    }
    
    @Test
    @Order(3)
    @DisplayName("缓存统计接口")
    void testCacheStats() throws Exception {
        mockMvc.perform(get("/api/cache/admin/stats")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.l1").exists());
    }
    
    @Test
    @Order(4)
    @DisplayName("健康检查接口")
    void testHealthCheck() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
    
    @Test
    @Order(5)
    @DisplayName("Prometheus 指标接口")
    void testPrometheusMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory")));
    }
    
    @Test
    @Order(6)
    @DisplayName("并发请求测试")
    void testConcurrentRequests() throws Exception {
        int threads = 10;
        int requestsPerThread = 100;
        
        java.util.concurrent.ExecutorService executor = 
            java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch latch = 
            new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger successCount = 
            new java.util.concurrent.atomic.AtomicInteger(0);
        
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        mockMvc.perform(get("/api/spu/detail/10001"))
                            .andExpect(status().isOk());
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertEquals(threads * requestsPerThread, successCount.get());
    }
}
