package com.example.miniaigateway.aspect;

import com.example.miniaigateway.auth.ApiKeyService;
import com.example.miniaigateway.exception.AuthException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Aspect
@Component
@Order(0)   // 比 @Order(1) 的安全切面更早执行
public class AuthAspect {

    private final ApiKeyService apiKeyService;

    public AuthAspect(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * 切点写成全限定名（不绑定变量）是有意为之，原因见 {@link RateLimitAspect#around}：
     * 绑定型切点的注解入参在「上游切面把 proceed 推迟到响应式操作符里」时取不到值。
     * 本切面恰好是链上第一个切面，躲得过，但下游切面躲不过，所以统一不绑定。
     */
    @Around("@annotation(com.example.miniaigateway.annotation.Authenticated)")
    public Object around(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();

        ServerWebExchange exchange = null;
        for (Object arg : args) {
            if (arg instanceof ServerWebExchange e) {
                exchange = e;
                break;
            }
        }

        if (exchange == null) {
            return Mono.error(new AuthException("internal_error", "ServerWebExchange not found"));
        }

        // 1. 从 Authorization 头提取 API Key
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        String apiKey = extractApiKey(authHeader);

        if (apiKey == null) {
            return Mono.error(new AuthException("missing_api_key",
                    "Missing or invalid Authorization header. Expected: Bearer sk-xxx"));
        }

        // 2. 校验
        final ServerWebExchange finalExchange = exchange;
        return apiKeyService.validateKey(apiKey)
                .switchIfEmpty(Mono.error(new AuthException("invalid_api_key",
                        "API Key is invalid, revoked, or expired")))
                .flatMap(entity -> {
                    // 3. 把租户信息存到 exchange attributes，供后续切面使用
                    finalExchange.getAttributes().put("tenantId", entity.getTenantId());
                    finalExchange.getAttributes().put("tenantName", entity.getTenantName());
                    finalExchange.getAttributes().put("apiKeyId", entity.getId());
                    finalExchange.getAttributes().put("rpmLimit", entity.getRpmLimit());
                    finalExchange.getAttributes().put("tpmLimit", entity.getTpmLimit());
                    finalExchange.getAttributes().put("dailyQuota", entity.getDailyQuota());

                    log.debug("Authenticated tenant={}, keyPrefix={}",
                            entity.getTenantId(), entity.getKeyPrefix());

                    // 4. 执行原方法
                    try {
                        return (Mono<?>) joinPoint.proceed();
                    } catch (Throwable e) {
                        return Mono.error(e);
                    }
                });
    }

    private String extractApiKey(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        // 支持 "Bearer sk-xxx" 和裸 "sk-xxx" 两种格式
        if (authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        if (authHeader.startsWith("sk-")) {
            return authHeader.trim();
        }
        return null;
    }
}