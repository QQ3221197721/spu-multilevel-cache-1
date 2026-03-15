package com.ecommerce.cache.controller;

import com.ecommerce.cache.dto.SpuDetailDTO;
import com.ecommerce.cache.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SPU 详情控制器测试
 * 使用 addFilters=false 绕过 Security 过滤器，专注测试控制器逻辑
 * Security 规则在集成测试中验证
 */
@WebMvcTest(SpuDetailController.class)
@AutoConfigureMockMvc(addFilters = false)
class SpuDetailControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @MockBean
    private MultiLevelCacheService multiLevelCacheService;
    
    @MockBean
    private SpuService spuService;
    
    @MockBean
    private BloomFilterService bloomFilterService;
    
    @MockBean
    private HotKeyDetectorService hotKeyDetectorService;
    
    @MockBean
    private L1CacheService l1CacheService;
    
    @Test
    @DisplayName("获取 SPU 详情 - 成功")
    void testGetSpuDetail_success() throws Exception {
        SpuDetailDTO dto = SpuDetailDTO.builder()
            .spuId(10086L)
            .name("iPhone 15 Pro Max")
            .subtitle("年度旗舰")
            .price(new BigDecimal("9999.00"))
            .status(1)
            .build();
        
        String jsonValue = objectMapper.writeValueAsString(dto);
        // 控制器调用 2-arg get(key, supplier)，这里 stub 匹配
        when(multiLevelCacheService.get(anyString(), any(Supplier.class))).thenReturn(jsonValue);
        
        mockMvc.perform(get("/api/spu/detail/10086")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.spuId").value(10086))
            .andExpect(jsonPath("$.data.name").value("iPhone 15 Pro Max"));
    }
    
    @Test
    @DisplayName("获取 SPU 详情 - 不存在")
    void testGetSpuDetail_notFound() throws Exception {
        when(multiLevelCacheService.get(anyString(), any(Supplier.class))).thenReturn(null);
        
        mockMvc.perform(get("/api/spu/detail/99999")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404));
    }
    
    @Test
    @DisplayName("获取 SPU 详情 - 无效参数")
    void testGetSpuDetail_invalidParam() throws Exception {
        mockMvc.perform(get("/api/spu/detail/abc")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest());
    }
}
