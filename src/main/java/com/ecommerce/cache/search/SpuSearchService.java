package com.ecommerce.cache.search;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;

/**
 * SPU 全文检索服务（Elasticsearch）
 * <p>
 * 功能：
 * 1. 关键词搜索（支持中文分词）
 * 2. 分类/品牌过滤
 * 3. 价格范围查询
 * 4. 搜索结果排序（相关度/销量/价格）
 * 5. 搜索性能指标
 */
@Service
public class SpuSearchService {

    private static final Logger log = LoggerFactory.getLogger(SpuSearchService.class);

    private final SpuSearchRepository searchRepository;
    private final MeterRegistry meterRegistry;

    private Timer searchTimer;

    public SpuSearchService(SpuSearchRepository searchRepository,
                             MeterRegistry meterRegistry) {
        this.searchRepository = searchRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        searchTimer = Timer.builder("es.search.latency")
                .description("Elasticsearch search latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /**
     * 关键词搜索（多字段匹配）
     */
    public Page<SpuSearchDocument> search(String keyword, int page, int size) {
        return searchTimer.record(() -> {
            Pageable pageable = PageRequest.of(page, size);
            Page<SpuSearchDocument> result = searchRepository.searchByKeyword(keyword, pageable);
            log.info("ES 搜索: keyword={}, total={}, page={}", keyword, result.getTotalElements(), page);
            return result;
        });
    }

    /**
     * 按分类搜索
     */
    public Page<SpuSearchDocument> searchByCategory(Long categoryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "salesCount"));
        return searchRepository.findByCategoryId(categoryId, pageable);
    }

    /**
     * 按品牌搜索
     */
    public Page<SpuSearchDocument> searchByBrand(String brandName, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "salesCount"));
        return searchRepository.findByBrandName(brandName, pageable);
    }

    /**
     * 索引/更新 SPU 文档
     */
    public void indexDocument(SpuSearchDocument document) {
        try {
            searchRepository.save(document);
            log.debug("ES 文档已索引: spuId={}", document.getSpuId());
        } catch (Exception e) {
            log.error("ES 文档索引失败: spuId={}", document.getSpuId(), e);
        }
    }

    /**
     * 删除 SPU 文档
     */
    public void deleteDocument(Long spuId) {
        try {
            searchRepository.deleteById(spuId);
            log.debug("ES 文档已删除: spuId={}", spuId);
        } catch (Exception e) {
            log.error("ES 文档删除失败: spuId={}", spuId, e);
        }
    }

    /**
     * 获取索引文档总数
     */
    public long countDocuments() {
        return searchRepository.count();
    }
}

