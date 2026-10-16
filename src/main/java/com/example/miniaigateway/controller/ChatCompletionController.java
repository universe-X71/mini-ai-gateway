package com.example.miniaigateway.controller;

import com.example.miniaigateway.adapter.ModelAdapter;
import com.example.miniaigateway.annotation.RateLimit;
import com.example.miniaigateway.annotation.SecurityCheck;
import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.routing.RouterService;
import com.example.miniaigateway.token.TokenCounter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_PROVIDER_CHAIN = "X-Provider-Chain";
    private static final String HEADER_FALLBACK_PROVIDER = "X-Fallback-Provider";

    private final TokenCounter tokenCounter;
    private final RouterService routerService;

    public ChatCompletionController(TokenCounter tokenCounter, RouterService routerService) {
        this.tokenCounter = tokenCounter;
        this.routerService = routerService;
    }

    /**
     * @param userId 方法体未直接使用，由 {@code RateLimitAspect} 从入参中提取作为限流主体，勿删
     */
    @PostMapping(value = "/chat/completions")
    @RateLimit
    @SecurityCheck
    public Mono<ResponseEntity<Object>> chatCompletions(
            @RequestHeader(HEADER_USER_ID) String userId,
            @RequestBody ChatCompletionRequest request) {

        int promptTokens = tokenCounter.countPromptTokens(request);

        // 流式：返回 Flux<ServerSentEvent<String>>，Content-Type 为 text/event-stream
        if (Boolean.TRUE.equals(request.getStream())) {
            // 流式走 RouterService，支持首字节前 fallback
            Flux<ServerSentEvent<String>> stream = routerService
                    .chatCompletionStream(request, promptTokens);

            // 响应头里列出候选 provider 链，便于客户端/调试观察
            List<ModelAdapter> candidates = routerService.resolveCandidatesForLog(request.getModel());
            String chainHeader = candidates.stream()
                    .map(ModelAdapter::providerName)
                    .collect(Collectors.joining(","));

            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(HEADER_PROVIDER_CHAIN, chainHeader)
                    .body(stream));
        }

        // 非流式：走 RouterService
        return routerService.chatCompletion(request, promptTokens)
                .map(providerResp -> {
                    ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON);
                    if (providerResp.fallback()) {
                        builder.header(HEADER_FALLBACK_PROVIDER, providerResp.provider());
                    }
                    return builder.body(providerResp.body());
                });
    }
}
