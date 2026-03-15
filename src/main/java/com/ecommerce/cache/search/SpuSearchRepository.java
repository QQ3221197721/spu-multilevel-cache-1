package com.ecommerce.cache.search;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SPU 搜索仓库（Elasticsearch）
 */
@Repository
public interface SpuSearchRepository extends ElasticsearchRepository<SpuSearchDocument, Long> {

    /**
     * 按名称模糊搜索
     */
    Page<SpuSearchDocument> findBySpuNameContaining(String keyword, Pageable pageable);

    /**
     * 按分类搜索
     */
    Page<SpuSearchDocument> findByCategoryId(Long categoryId, Pageable pageable);

    /**
     * 按品牌搜索
     */
    Page<SpuSearchDocument> findByBrandName(String brandName, Pageable pageable);

    /**
     * 按状态搜索
     */
    List<SpuSearchDocument> findByStatus(Integer status);

    /**
     * 自定义多字段搜索
     */
    @Query("""
            {
              "bool": {
                "should": [
                  { "match": { "spuName": { "query": "?0", "boost": 3 } } },
                  { "match": { "description": { "query": "?0", "boost": 1 } } },
                  { "match": { "tags": { "query": "?0", "boost": 2 } } },
                  { "term": { "brandName": { "value": "?0", "boost": 2 } } }
                ],
                "minimum_should_match": 1
              }
            }
            """)
    Page<SpuSearchDocument> searchByKeyword(String keyword, Pageable pageable);
}
