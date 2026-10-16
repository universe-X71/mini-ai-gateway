package com.example.miniaigateway.aspect;

import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.dto.ChatCompletionResponse;
import com.example.miniaigateway.exception.RateLimitException;
import com.example.miniaigateway.quota.QuotaService;
import com.example.miniaigateway.ratelimit.TokenRateLimiter;
import com.example.miniaigateway.token.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Aspect
@Component
@Order(2)
public class RateLimitAspect {

    /**
     * 无法从入参中解析出租户时的兜底主体
     */
    private static final String ANONYMOUS_TENANT_ID = "anonymous";

    private final TokenRateLimiter rateLimiter;
    private final QuotaService quotaService;
    private final TokenCounter tokenCounter;

    @Value("${gateway.quota.daily-tokens:1000000}")
    private long dailyQuota;

    @Value("${gateway.quota.default-max-tokens:4096}")
    private int defaultMaxTokens;

    @Value("${gateway.quota.max-tokens-limit:8192}")
    private int maxTokensLimit;

    public RateLimitAspect(TokenRateLimiter rateLimiter, QuotaService quotaService, TokenCounter tokenCounter) {
        this.rateLimiter = rateLimiter;
        this.quotaService = quotaService;
        this.tokenCounter = tokenCounter;
    }

    /**
     * 切点写成全限定名（不绑定变量）是有意为之。绑定型的 {@code @annotation(rateLimit)} 要求 Spring
     * 在<b>动态匹配</b>时把注解实例作为通知方法入参绑定进来，而本切面链的 {@code joinPoint.proceed()}
     * 会被上游切面推迟到响应式操作符里执行（见 {@link AuthAspect}），那一刻
     * {@code ExposeInvocationInterceptor} 的 ThreadLocal 已经还原，绑定值取不到，会抛
     * "Required to bind 2 arguments, but only bound 1 (JoinPointMatch was NOT bound in invocation)"。
     */
    @Around("@annotation(com.example.miniaigateway.annotation.RateLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 提取参数
        Object[] args = joinPoint.getArgs();

        ChatCompletionRequest request = null;
        String tenantId = ANONYMOUS_TENANT_ID;

        for (Object arg : args) {
            if (arg instanceof ChatCompletionRequest req) {
                request = req;
            } else if (arg instanceof ServerWebExchange e) {
                Object tid = e.getAttributes().get("tenantId");
                if (tid != null && !tid.toString().isBlank()) {
                    tenantId = tid.toString();
                }
            }
        }

        if (request == null) {
            return joinPoint.proceed(); // 没有请求体，直接放行
        }

        // 2. 构建限流检查链（重点：必须返回响应式类型）
        final String finalTenantId = tenantId;
        final ChatCompletionRequest finalRequest = request;

        // 3. 计算 Token
        int promptTokens = tokenCounter.countPromptTokens(request);
        int clientMaxTokens = finalRequest.getMaxTokens() == null ? 0 : finalRequest.getMaxTokens();
        if (clientMaxTokens <= 0) {
            clientMaxTokens = defaultMaxTokens; // 兜底为 4096
        }
        if (clientMaxTokens > maxTokensLimit) {
            clientMaxTokens = maxTokensLimit;   // 强制截断为 8192
        }
        int estimatedTokens = promptTokens + clientMaxTokens;
        log.info("Estimated tokens: {}, promptTokens: {}, clientMaxTokens: {}", estimatedTokens, promptTokens, clientMaxTokens);

        // 使用 Mono.defer 延迟执行，确保只有在订阅时才触发限流检查
        return Mono.defer(() ->
                quotaService.ensureTenantExists(finalTenantId, dailyQuota)
                        .then(rateLimiter.acquireRpm(finalTenantId))
                        .flatMap(rpmAllowed -> {
                            if (!rpmAllowed) return Mono.error(new RateLimitException("RPM limit exceeded"));
                            return rateLimiter.acquireTpm(finalTenantId, estimatedTokens);
                        })
                        .flatMap(tpmAllowed -> {
                            if (!tpmAllowed) return Mono.error(new RateLimitException("TPM limit exceeded"));
                            return quotaService.tryConsume(finalTenantId, estimatedTokens);
                        })
                        .flatMap(quotaAllowed -> {
                            if (!quotaAllowed) return Mono.error(new RateLimitException("Daily quota exhausted"));
                            // 4. 限流通过，执行原方法
                            try {
                                return proceedToBusiness(joinPoint, finalTenantId, estimatedTokens);
                            } catch (Throwable e) {
                                return Mono.error(e);
                            }
                        })
        );
    }

    /**
     * {@code instanceof} 只能校验到 Mono / ResponseEntity 这一层，元素泛型无法在运行时确认，
     * 故此处需要显式抑制 unchecked 告警——签名已约定目标方法的返回类型。
     */
    @SuppressWarnings("unchecked")
    private Mono<ResponseEntity<Object>> proceedToBusiness(
            ProceedingJoinPoint joinPoint, String tenantId, int estimatedTokens) {
        try {
            Object result = joinPoint.proceed();
            if (result instanceof Mono<?> monoResult) {
                return ((Mono<ResponseEntity<Object>>) monoResult)
                        .doOnNext(response -> calibrateTokenUsage(response, tenantId, estimatedTokens));
            }
            return Mono.just((ResponseEntity<Object>) result);
        } catch (Throwable e) {
            return Mono.error(e);
        }
    }

    private void calibrateTokenUsage(ResponseEntity<Object> response, String tenantId, int estimatedTokens) {
        Object body = response.getBody();

        // 非流式：body 是 ChatCompletionResponse，直接读 usage
        if (body instanceof ChatCompletionResponse resp) {
            if (resp.getUsage() != null && resp.getUsage().getTotalTokens() != null) {
                int actualTokens = resp.getUsage().getTotalTokens();
                int diff = actualTokens - estimatedTokens;   // 负数=多扣了，正数=少扣了

                if (diff != 0) {
                    log.info("Token 校准: 预估={}, 实际={}, 差额={}", estimatedTokens, actualTokens, diff);
                    quotaService.adjustQuota(tenantId, diff).subscribe();
                    rateLimiter.adjustTpm(tenantId, diff).subscribe();
                }
            }
        }
        // 流式：body 是 Flux，暂时不处理，Day 6/7 再补
        else if (body instanceof Flux<?>) {
            // TODO: 流式场景下 usage 不在最后的 chunk 里，需要另想办法
        }
    }
}