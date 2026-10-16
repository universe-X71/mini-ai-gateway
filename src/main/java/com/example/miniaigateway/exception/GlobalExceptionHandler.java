package com.example.miniaigateway.exception;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 比 Spring 默认的异常处理器优先级更高 */
@Order(GlobalExceptionHandler.HIGHEST_PRECEDENCE_ORDER)
@RestControllerAdvice
public class GlobalExceptionHandler {

    static final int HIGHEST_PRECEDENCE_ORDER = -1;

    private static final String FIELD_ERROR = "error";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_TYPE = "type";

    private static final String TYPE_RATE_LIMIT_EXCEEDED = "rate_limit_exceeded";
    private static final String TYPE_INVALID_REQUEST = "invalid_request_error";
    private static final String TYPE_INTERNAL = "internal_error";

    // 限流异常 → 429
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(errorBody(TYPE_RATE_LIMIT_EXCEEDED, e.getMessage()));
    }

    // 模型不存在等参数错误 → 400
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(TYPE_INVALID_REQUEST, e.getMessage()));
    }

    // 其他未捕获异常 → 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(TYPE_INTERNAL, e.getMessage()));
    }

    /** 统一 OpenAI 风格的错误响应体：{"error": {"message": ..., "type": ...}} */
    private Map<String, Object> errorBody(String type, String message) {
        return Map.of(FIELD_ERROR, Map.of(
                FIELD_MESSAGE, message,
                FIELD_TYPE, type
        ));
    }
}
