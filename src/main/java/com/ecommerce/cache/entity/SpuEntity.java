package com.ecommerce.cache.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SPU 商品实体
 */
@Data
@Entity
@Table(name = "t_spu")
public class SpuEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /** SPU ID */
    @Column(name = "spu_id", unique = true, nullable = false)
    private Long spuId;
    
    /** 商品名称 */
    @Column(name = "name", nullable = false, length = 200)
    private String name;
    
    /** 商品副标题 */
    @Column(name = "subtitle", length = 500)
    private String subtitle;
    
    /** 商品描述 */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    /** 分类 ID */
    @Column(name = "category_id")
    private Long categoryId;
    
    /** 品牌 ID */
    @Column(name = "brand_id")
    private Long brandId;
    
    /** 参考价格 */
    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;
    
    /** 主图 URL */
    @Column(name = "main_image", length = 500)
    private String mainImage;
    
    /** 图集 JSON */
    @Column(name = "images", columnDefinition = "TEXT")
    private String images;
    
    /** 商品属性 JSON */
    @Column(name = "attributes", columnDefinition = "TEXT")
    private String attributes;
    
    /** 状态：1-上架 0-下架 */
    @Column(name = "status")
    private Integer status;
    
    /** 销量 */
    @Column(name = "sales")
    private Integer sales;
    
    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
