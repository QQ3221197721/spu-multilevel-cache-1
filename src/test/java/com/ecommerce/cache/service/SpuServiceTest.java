package com.ecommerce.cache.service;

import com.ecommerce.cache.dto.SpuDetailDTO;
import com.ecommerce.cache.entity.SpuEntity;
import com.ecommerce.cache.repository.SpuRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SPU 业务服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class SpuServiceTest {

    @Mock
    private SpuRepository spuRepository;

    private SpuService spuService;

    private static final Long TEST_SPU_ID = 10086L;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        spuService = new SpuService(spuRepository, objectMapper);
    }

    // ==================== loadFromDatabase ====================

    @Test
    @DisplayName("loadFromDatabase - SPU 存在，正常返回 DTO")
    void testLoadFromDatabase_success() {
        SpuEntity entity = buildTestEntity(TEST_SPU_ID);
        when(spuRepository.findBySpuId(TEST_SPU_ID)).thenReturn(Optional.of(entity));

        SpuDetailDTO dto = spuService.loadFromDatabase(TEST_SPU_ID);

        assertNotNull(dto);
        assertEquals(TEST_SPU_ID, dto.getSpuId());
        assertEquals("iPhone 15 Pro Max", dto.getName());
        assertEquals("年度旗舰", dto.getSubtitle());
        assertEquals(new BigDecimal("9999.00"), dto.getPrice());
        assertEquals(1, dto.getStatus());
    }

    @Test
    @DisplayName("loadFromDatabase - SPU 不存在，返回 null")
    void testLoadFromDatabase_notFound() {
        when(spuRepository.findBySpuId(99999L)).thenReturn(Optional.empty());

        SpuDetailDTO dto = spuService.loadFromDatabase(99999L);

        assertNull(dto);
    }

    @Test
    @DisplayName("loadFromDatabase - 带图集 JSON 正确解析")
    void testLoadFromDatabase_withImages() {
        SpuEntity entity = buildTestEntity(TEST_SPU_ID);
        entity.setImages("[\"img1.jpg\",\"img2.jpg\",\"img3.jpg\"]");
        when(spuRepository.findBySpuId(TEST_SPU_ID)).thenReturn(Optional.of(entity));

        SpuDetailDTO dto = spuService.loadFromDatabase(TEST_SPU_ID);

        assertNotNull(dto);
        assertNotNull(dto.getImages());
        assertEquals(3, dto.getImages().size());
        assertEquals("img1.jpg", dto.getImages().get(0));
    }

    @Test
    @DisplayName("loadFromDatabase - 无图集 JSON，返回空列表")
    void testLoadFromDatabase_noImages() {
        SpuEntity entity = buildTestEntity(TEST_SPU_ID);
        entity.setImages(null);
        when(spuRepository.findBySpuId(TEST_SPU_ID)).thenReturn(Optional.of(entity));

        SpuDetailDTO dto = spuService.loadFromDatabase(TEST_SPU_ID);

        assertNotNull(dto);
        assertTrue(dto.getImages().isEmpty());
    }

    @Test
    @DisplayName("loadFromDatabase - 带属性 JSON 正确解析")
    void testLoadFromDatabase_withAttributes() {
        SpuEntity entity = buildTestEntity(TEST_SPU_ID);
        entity.setAttributes("[{\"name\":\"颜色\",\"value\":\"深空黑\"},{\"name\":\"存储\",\"value\":\"256GB\"}]");
        when(spuRepository.findBySpuId(TEST_SPU_ID)).thenReturn(Optional.of(entity));

        SpuDetailDTO dto = spuService.loadFromDatabase(TEST_SPU_ID);

        assertNotNull(dto);
        assertNotNull(dto.getAttributes());
        assertEquals(2, dto.getAttributes().size());
    }

    @Test
    @DisplayName("loadFromDatabase - 非法 JSON 抛出 RuntimeException")
    void testLoadFromDatabase_invalidJson() {
        SpuEntity entity = buildTestEntity(TEST_SPU_ID);
        entity.setImages("invalid-json{{{");
        when(spuRepository.findBySpuId(TEST_SPU_ID)).thenReturn(Optional.of(entity));

        assertThrows(RuntimeException.class, () -> spuService.loadFromDatabase(TEST_SPU_ID));
    }

    // ==================== existsById ====================

    @Test
    @DisplayName("existsById - SPU 存在")
    void testExistsById_exists() {
        when(spuRepository.existsBySpuId(TEST_SPU_ID)).thenReturn(true);

        assertTrue(spuService.existsById(TEST_SPU_ID));
    }

    @Test
    @DisplayName("existsById - SPU 不存在")
    void testExistsById_notExists() {
        when(spuRepository.existsBySpuId(99999L)).thenReturn(false);

        assertFalse(spuService.existsById(99999L));
    }

    // ==================== getAllActiveSpuIds ====================

    @Test
    @DisplayName("getAllActiveSpuIds - 返回有效 SPU ID 列表")
    void testGetAllActiveSpuIds() {
        List<Long> ids = Arrays.asList(10001L, 10002L, 10003L);
        when(spuRepository.findAllActiveSpuIds()).thenReturn(ids);

        List<Long> result = spuService.getAllActiveSpuIds();

        assertEquals(3, result.size());
        assertEquals(10001L, result.get(0));
    }

    // ==================== getHotSpuIds ====================

    @Test
    @DisplayName("getHotSpuIds - 返回热门 SPU ID 列表")
    void testGetHotSpuIds() {
        List<Long> ids = Arrays.asList(10001L, 10002L);
        when(spuRepository.findTopSpuIdsBySales(2)).thenReturn(ids);

        List<Long> result = spuService.getHotSpuIds(2);

        assertEquals(2, result.size());
    }

    // ==================== 工具方法 ====================

    private SpuEntity buildTestEntity(Long spuId) {
        SpuEntity entity = new SpuEntity();
        entity.setId(1L);
        entity.setSpuId(spuId);
        entity.setName("iPhone 15 Pro Max");
        entity.setSubtitle("年度旗舰");
        entity.setDescription("A17 Pro 芯片");
        entity.setCategoryId(100L);
        entity.setBrandId(200L);
        entity.setPrice(new BigDecimal("9999.00"));
        entity.setMainImage("main.jpg");
        entity.setImages("[]");
        entity.setAttributes("[]");
        entity.setStatus(1);
        entity.setSales(50000);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
