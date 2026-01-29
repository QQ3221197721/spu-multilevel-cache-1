package com.ecommerce.cache.exception;

import com.ecommerce.cache.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    /**
     * 处理 SPU 不存在异常
     */
    @ExceptionHandler(SpuNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleSpuNotFound(SpuNotFoundException ex) {
        log.warn("SPU not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.notFound(ex.getMessage()));
    }
    
    /**
     * 处理缓存异常
     */
    @ExceptionHandler(CacheException.class)
    public ResponseEntity<ApiResponse<Void>> handleCacheException(CacheException ex) {
        log.error("Cache error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.serverError("缓存服务异常，请稍后重试"));
    }
    
    /**
     * 处理通用运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException ex) {
        log.error("Runtime error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.serverError("系统繁忙，请稍后重试"));
    }
    
    /**
     * 处理所有未捕获异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.serverError("系统异常，请稍后重试"));
    }
    
    /**
     * SPU 不存在异常
     */
    public static class SpuNotFoundException extends RuntimeException {
        public SpuNotFoundException(Long spuId) {
            super("SPU 不存在: " + spuId);
        }
    }
    
    /**
     * 缓存异常
     */
    public static class CacheException extends RuntimeException {
        public CacheException(String message) {
            super(message);
        }
        
        public CacheException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
