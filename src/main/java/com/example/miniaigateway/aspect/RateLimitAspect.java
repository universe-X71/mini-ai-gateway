package com.example.miniaigateway.aspect;

import com.example.miniaigateway.annotation.RateLimit;
import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.dto.ChatCompletionResponse;
import com.example.miniaigateway.exception.RateLimitException;
import com.example.miniaigateway.ratelimit.TokenRateLimiter;
import com.example.miniaigateway.service.QuotaService;
import com.example.miniaigateway.token.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
public class RateLimitAspect {

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

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // 1. 提取参数
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();

        ChatCompletionRequest request = null;
        String userId = "anonymous";

        for (Object arg : args) {
            log.info("Aspect arg: type={}, value={}",
                    arg == null ? "null" : arg.getClass().getSimpleName(), arg);
            if (arg instanceof ChatCompletionRequest) {
                request = (ChatCompletionRequest) arg;
            } else if (arg instanceof String s) {
                userId = s;
            }
            // 这里假设 userId 是从方法参数传入，或者通过其他方式获取
            // 实际项目中通常通过 ServerWebExchange 或 ThreadLocal 获取
        }

        if (request == null) {
            return joinPoint.proceed(); // 没有请求体，直接放行
        }

        // 2. 构建限流检查链（重点：必须返回响应式类型）
        final String finalUserId = userId;
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
                quotaService.ensureUserExists(finalUserId, dailyQuota)
                        .then(rateLimiter.acquireRpm(finalUserId))
                        .flatMap(rpmAllowed -> {
                            if (!rpmAllowed) return Mono.error(new RateLimitException("RPM limit exceeded"));
                            return rateLimiter.acquireTpm(finalUserId, estimatedTokens);
                        })
                        .flatMap(tpmAllowed -> {
                            if (!tpmAllowed) return Mono.error(new RateLimitException("TPM limit exceeded"));
                            return quotaService.tryConsume(finalUserId, estimatedTokens);
                        })
                        .flatMap(quotaAllowed -> {
                            if (!quotaAllowed) return Mono.error(new RateLimitException("Daily quota exhausted"));
                            // 4. 限流通过，执行原方法
                            try {
                                return proceedToBusiness(joinPoint, finalUserId, estimatedTokens);
                            } catch (Throwable e) {
                                return Mono.error(e);
                            }
                        })
        );
    }

    private Mono<ResponseEntity<Object>> proceedToBusiness(
            ProceedingJoinPoint joinPoint, String userId, int estimatedTokens) {
        try {
            Object result = joinPoint.proceed();
            if (result instanceof Mono<?> monoResult) {
                return ((Mono<ResponseEntity<Object>>) monoResult)
                        .doOnNext(response -> calibrateTokenUsage(response, userId, estimatedTokens));
            }
            return Mono.just((ResponseEntity<Object>) result);
        } catch (Throwable e) {
            return Mono.error(e);
        }
    }

    private void calibrateTokenUsage(ResponseEntity<Object> response, String userId, int estimatedTokens) {
        Object body = response.getBody();

        // 非流式：body 是 ChatCompletionResponse，直接读 usage
        if (body instanceof ChatCompletionResponse resp) {
            if (resp.getUsage() != null && resp.getUsage().getTotalTokens() != null) {
                int actualTokens = resp.getUsage().getTotalTokens();
                int diff = actualTokens - estimatedTokens;   // 负数=多扣了，正数=少扣了

                if (diff != 0) {
                    log.info("Token 校准: 预估={}, 实际={}, 差额={}", estimatedTokens, actualTokens, diff);
                    quotaService.adjustQuota(userId, diff).subscribe();
                    rateLimiter.adjustTpm(userId, diff).subscribe();
                }
            }
        }
        // 流式：body 是 Flux，暂时不处理，Day 6/7 再补
        else if (body instanceof Flux<?>) {
            // TODO: 流式场景下 usage 不在最后的 chunk 里，需要另想办法
        }
    }
}