package com.example.miniaigateway.exception;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Order(-1)
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 限流异常 → 429
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", Map.of(
                        "message", e.getMessage(),
                        "type", "rate_limit_exceeded"
                )));
    }

    // 模型不存在等参数错误 → 400
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", Map.of(
                        "message", e.getMessage(),
                        "type", "invalid_request_error"
                )));
    }

    // 其他未捕获异常 → 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", Map.of(
                        "message", e.getMessage(),
                        "type", "internal_error"
                )));
    }
}