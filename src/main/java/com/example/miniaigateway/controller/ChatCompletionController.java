package com.example.miniaigateway.controller;

import com.example.miniaigateway.adapter.ModelAdapter;
import com.example.miniaigateway.adapter.impl.ModelAdapterFactory;
import com.example.miniaigateway.annotation.RateLimit;
import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.routing.RouterService;
import com.example.miniaigateway.token.TokenCounter;
import jakarta.annotation.Resource;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.stream.Collectors;
import com.example.miniaigateway.adapter.ModelAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    @Resource
    private TokenCounter tokenCounter;

    @Resource
    private RouterService routerService;

    @PostMapping(value = "/chat/completions")
    @RateLimit
    public Mono<ResponseEntity<Object>> chatCompletions(@RequestHeader("X-User-Id") String userId, @RequestBody ChatCompletionRequest request) {

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
                    .header("X-Provider-Chain", chainHeader)
                    .body(stream));
        }

        // 非流式：走 RouterService
        return routerService.chatCompletion(request, promptTokens)
                .map(providerResp -> {
                    ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON);
                    if (providerResp.fallback()) {
                        builder.header("X-Fallback-Provider", providerResp.provider());
                    }
                    return builder.body(providerResp.body());
                });
    }
}