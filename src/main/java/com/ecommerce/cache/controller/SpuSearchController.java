package com.ecommerce.cache.controller;

import com.ecommerce.cache.search.SpuSearchDocument;
import com.ecommerce.cache.search.SpuSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SPU 全文检索 API（Elasticsearch）
 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "SPU搜索", description = "SPU 商品全文检索（Elasticsearch）")
public class SpuSearchController {

    private final SpuSearchService searchService;

    public SpuSearchController(SpuSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @Operation(summary = "关键词搜索", description = "多字段全文检索：名称(权重3)、标签(权重2)、描述(权重1)")
    public ResponseEntity<Page<SpuSearchDocument>> search(
            @Parameter(description = "搜索关键词") @RequestParam String keyword,
            @Parameter(description = "页码(从0开始)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(searchService.search(keyword, page, size));
    }

    @GetMapping("/category/{categoryId}")
    @Operation(summary = "按分类搜索", description = "返回指定分类下的 SPU，按销量降序排列")
    public ResponseEntity<Page<SpuSearchDocument>> searchByCategory(
            @Parameter(description = "分类ID") @PathVariable Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(searchService.searchByCategory(categoryId, page, size));
    }

    @GetMapping("/brand/{brandName}")
    @Operation(summary = "按品牌搜索", description = "返回指定品牌的 SPU，按销量降序排列")
    public ResponseEntity<Page<SpuSearchDocument>> searchByBrand(
            @Parameter(description = "品牌名称") @PathVariable String brandName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(searchService.searchByBrand(brandName, page, size));
    }

    @PostMapping("/index")
    @Operation(summary = "索引SPU文档", description = "手动索引/更新单个 SPU 到 Elasticsearch")
    public ResponseEntity<Map<String, Object>> indexDocument(@RequestBody SpuSearchDocument document) {
        searchService.indexDocument(document);
        return ResponseEntity.ok(Map.of("status", "indexed", "spuId", document.getSpuId()));
    }

    @DeleteMapping("/index/{spuId}")
    @Operation(summary = "删除SPU文档", description = "从 Elasticsearch 索引中删除指定 SPU")
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @Parameter(description = "SPU ID") @PathVariable Long spuId) {
        searchService.deleteDocument(spuId);
        return ResponseEntity.ok(Map.of("status", "deleted", "spuId", spuId));
    }

    @GetMapping("/stats")
    @Operation(summary = "搜索统计", description = "获取 ES 索引文档数量等统计信息")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "totalDocuments", searchService.countDocuments()
        ));
    }
}
