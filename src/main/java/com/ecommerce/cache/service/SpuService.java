package com.ecommerce.cache.service;

import com.ecommerce.cache.dto.SpuDetailDTO;
import com.ecommerce.cache.entity.SpuEntity;
import com.ecommerce.cache.repository.SpuRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * SPU 业务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpuService {
    
    private final SpuRepository spuRepository;
    private final ObjectMapper objectMapper;
    
    /**
     * 从数据库加载 SPU 详情
     * @param spuId SPU ID
     * @return SPU 详情 DTO
     */
    @Transactional(readOnly = true)
    public SpuDetailDTO loadFromDatabase(Long spuId) {
        log.debug("Loading SPU from database: {}", spuId);
        
        Optional<SpuEntity> optional = spuRepository.findBySpuId(spuId);
        if (optional.isEmpty()) {
            return null;
        }
        
        SpuEntity entity = optional.get();
        return convertToDTO(entity);
    }
    
    /**
     * 检查 SPU 是否存在
     */
    public boolean existsById(Long spuId) {
        return spuRepository.existsBySpuId(spuId);
    }
    
    /**
     * 获取所有有效 SPU ID（用于布隆过滤器初始化）
     */
    @Transactional(readOnly = true)
    public List<Long> getAllActiveSpuIds() {
        return spuRepository.findAllActiveSpuIds();
    }
    
    /**
     * 获取热门 SPU ID（用于预热）
     */
    @Transactional(readOnly = true)
    public List<Long> getHotSpuIds(int limit) {
        return spuRepository.findTopSpuIdsBySales(limit);
    }
    
    /**
     * Entity 转 DTO
     */
    private SpuDetailDTO convertToDTO(SpuEntity entity) {
        try {
            // 解析图集 JSON
            List<String> images = entity.getImages() != null
                ? objectMapper.readValue(entity.getImages(), new TypeReference<List<String>>() {})
                : Collections.emptyList();
            
            // 解析属性 JSON
            List<SpuDetailDTO.AttributeInfo> attributes = entity.getAttributes() != null
                ? objectMapper.readValue(entity.getAttributes(), new TypeReference<List<SpuDetailDTO.AttributeInfo>>() {})
                : Collections.emptyList();
            
            return SpuDetailDTO.builder()
                .spuId(entity.getSpuId())
                .name(entity.getName())
                .subtitle(entity.getSubtitle())
                .description(entity.getDescription())
                .category(SpuDetailDTO.CategoryInfo.builder()
                    .id(entity.getCategoryId())
                    .build())
                .brand(SpuDetailDTO.BrandInfo.builder()
                    .id(entity.getBrandId())
                    .build())
                .price(entity.getPrice())
                .mainImage(entity.getMainImage())
                .images(images)
                .attributes(attributes)
                .status(entity.getStatus())
                .sales(entity.getSales())
                .updateTimestamp(entity.getUpdatedAt() != null 
                    ? entity.getUpdatedAt().toEpochSecond(java.time.ZoneOffset.UTC) 
                    : System.currentTimeMillis() / 1000)
                .build();
                
        } catch (JsonProcessingException e) {
            log.error("Failed to parse SPU JSON fields for spuId: {}", entity.getSpuId(), e);
            throw new RuntimeException("SPU 数据解析失败", e);
        }
    }
}
