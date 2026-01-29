package com.ecommerce.cache.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * SPU 详情 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpuDetailDTO implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** SPU ID */
    private Long spuId;
    
    /** 商品名称 */
    private String name;
    
    /** 副标题 */
    private String subtitle;
    
    /** 商品描述 */
    private String description;
    
    /** 分类信息 */
    private CategoryInfo category;
    
    /** 品牌信息 */
    private BrandInfo brand;
    
    /** 价格 */
    private BigDecimal price;
    
    /** 主图 */
    private String mainImage;
    
    /** 图集 */
    private List<String> images;
    
    /** 商品属性 */
    private List<AttributeInfo> attributes;
    
    /** 状态 */
    private Integer status;
    
    /** 销量 */
    private Integer sales;
    
    /** SKU 列表 */
    private List<SkuInfo> skuList;
    
    /** 更新时间戳（用于缓存版本控制） */
    private Long updateTimestamp;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryInfo implements Serializable {
        private Long id;
        private String name;
        private String path;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandInfo implements Serializable {
        private Long id;
        private String name;
        private String logo;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttributeInfo implements Serializable {
        private String name;
        private String value;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkuInfo implements Serializable {
        private Long skuId;
        private String spec;
        private BigDecimal price;
        private Integer stock;
    }
}
