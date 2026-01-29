package com.ecommerce.cache.repository;

import com.ecommerce.cache.entity.SpuEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SPU Repository
 */
@Repository
public interface SpuRepository extends JpaRepository<SpuEntity, Long> {
    
    /**
     * 根据 SPU ID 查询
     */
    Optional<SpuEntity> findBySpuId(Long spuId);
    
    /**
     * 检查 SPU ID 是否存在
     */
    boolean existsBySpuId(Long spuId);
    
    /**
     * 批量查询 SPU
     */
    @Query("SELECT s FROM SpuEntity s WHERE s.spuId IN :spuIds")
    List<SpuEntity> findBySpuIdIn(@Param("spuIds") List<Long> spuIds);
    
    /**
     * 查询所有上架商品的 SPU ID（用于布隆过滤器初始化）
     */
    @Query("SELECT s.spuId FROM SpuEntity s WHERE s.status = 1")
    List<Long> findAllActiveSpuIds();
    
    /**
     * 查询热门商品（销量前 N）
     */
    @Query("SELECT s.spuId FROM SpuEntity s WHERE s.status = 1 ORDER BY s.sales DESC LIMIT :limit")
    List<Long> findTopSpuIdsBySales(@Param("limit") int limit);
    
    /**
     * 按分类查询
     */
    List<SpuEntity> findByCategoryIdAndStatus(Long categoryId, Integer status);
    
    /**
     * 按品牌查询
     */
    List<SpuEntity> findByBrandIdAndStatus(Long brandId, Integer status);
}
