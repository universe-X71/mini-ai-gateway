package com.example.miniaigateway.aspect;

import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.security.PiiDetector;
import com.example.miniaigateway.security.PromptInjectionModelDetector;
import com.example.miniaigateway.security.SecurityProperties;
import com.example.miniaigateway.security.SecurityViolationException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Aspect
@Component
@Order(1)   // 在 RateLimitAspect 之前执行
public class SecurityAspect {

    private final PromptInjectionModelDetector injectionDetector;
    private final PiiDetector piiDetector;
    private final SecurityProperties securityProperties;

    public SecurityAspect(PromptInjectionModelDetector injectionDetector,
                          PiiDetector piiDetector,
                          SecurityProperties securityProperties) {
        this.injectionDetector = injectionDetector;
        this.piiDetector = piiDetector;
        this.securityProperties = securityProperties;
    }

    /**
     * 切点写成全限定名（不绑定变量）是有意为之，原因见 {@link RateLimitAspect#around}：
     * 绑定型切点的注解入参在「上游切面把 proceed 推迟到响应式操作符里」时取不到值。
     */
    @Around("@annotation(com.example.miniaigateway.annotation.SecurityCheck)")
    public Object around(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();

        ChatCompletionRequest request = null;
        for (Object arg : args) {
            if (arg instanceof ChatCompletionRequest req) {
                request = req;
                break;
            }
        }

        if (request == null || request.getMessages() == null) {
            return proceedQuietly(joinPoint);
        }

        try {
            // 1. Prompt 注入检测
            if (securityProperties.getPromptInjection().isEnabled()) {
                for (ChatCompletionRequest.Message msg : request.getMessages()) {
                    boolean hit = injectionDetector.isInjection(msg.getContent());
                    if (hit) {
                        String action = securityProperties.getPromptInjection().getAction();
                        if ("REJECT".equalsIgnoreCase(action)) {
                            return Mono.error(new SecurityViolationException(
                                    "prompt_injection_blocked", "Model detected prompt injection attempt"));
                        } else {
                            log.warn("[LOG_ONLY] Prompt injection detected but not blocked: {}", hit);
                        }
                    }
                }
            }

            // 2. PII 检测与脱敏
            if (securityProperties.getPii().isEnabled()) {
                String action = securityProperties.getPii().getAction();
                for (ChatCompletionRequest.Message msg : request.getMessages()) {
                    String content = msg.getContent();
                    if (content == null || !piiDetector.containsPii(content)) {
                        continue;
                    }

                    if ("REJECT".equalsIgnoreCase(action)) {
                        return Mono.error(new SecurityViolationException(
                                "pii_blocked", "Request contains PII and is rejected"));
                    } else if ("REDACT".equalsIgnoreCase(action)) {
                        // 就地修改消息内容，脱敏后的请求继续
                        msg.setContent(piiDetector.mask(content));
                        log.info("PII redacted in message (role={})", msg.getRole());
                    }
                }
            }

            // 3. 安全检测通过，执行业务
            return proceedQuietly(joinPoint);

        } catch (SecurityViolationException e) {
            return Mono.error(e);
        }
    }

    private Object proceedQuietly(ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            return Mono.error(e);
        }
    }
}