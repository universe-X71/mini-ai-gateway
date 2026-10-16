package com.example.miniaigateway.adapter.impl;

import com.example.miniaigateway.adapter.ModelAdapter;
import com.example.miniaigateway.adapter.Provider;
import com.example.miniaigateway.config.ProviderProperties;
import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.dto.ChatCompletionResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 基于 WebClient 的适配器基类，收敛各家共有的
 * 「建连接 → 请求 → 错误映射 → SSE 解包」流程，子类只声明自身差异。
 */
@Slf4j
public abstract class AbstractWebClientModelAdapter implements ModelAdapter {

    /** 各家上游均为 OpenAI 兼容协议，请求路径一致 */
    protected static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    protected static final String AUTH_SCHEME = "Bearer ";

    private final WebClient.Builder webClientBuilder;
    private final ProviderProperties providerProperties;
    private final Provider provider;
    private final boolean requiresApiKey;

    private WebClient webClient;
    private ProviderProperties.ProviderConfig config;

    protected AbstractWebClientModelAdapter(WebClient.Builder webClientBuilder,
                                            ProviderProperties providerProperties,
                                            Provider provider,
                                            boolean requiresApiKey) {
        this.webClientBuilder = webClientBuilder;
        this.providerProperties = providerProperties;
        this.provider = provider;
        this.requiresApiKey = requiresApiKey;
    }

    /**
     * 子类声明本适配器支持的模型名前缀，如 "gpt-"
     */
    protected abstract List<String> modelPrefixes();

    @PostConstruct
    public void init() {
        this.config = providerProperties.getProviders().get(provider.id());
        if (config == null) {
            log.warn("{} provider config not found", provider.id());
            return;
        }

        WebClient.Builder builder = webClientBuilder.clone().baseUrl(config.getBaseUrl());
        if (requiresApiKey) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, AUTH_SCHEME + config.getApiKey());
        }
        this.webClient = builder.build();

        log.info("{} adapter initialized with baseUrl={}", provider.id(), config.getBaseUrl());
    }

    @Override
    public boolean supports(String model) {
        return model != null && modelPrefixes().stream().anyMatch(model::startsWith);
    }

    @Override
    public String providerName() {
        return provider.id();
    }

    @Override
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        request.setStream(false);
        return webClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(),
                        resp -> upstreamError(resp, "request"))
                .bodyToMono(ChatCompletionResponse.class)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()));
    }

    @Override
    public Flux<ServerSentEvent<String>> chatCompletionStream(ChatCompletionRequest request) {
        request.setStream(true);
        return webClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(),
                        resp -> upstreamError(resp, "stream request"))
                .bodyToFlux(String.class)   // 上游是 SSE，每行是一个 data: {...}
                .filter(line -> !line.isEmpty())
                .map(line -> ServerSentEvent.builder(line).build())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .doOnCancel(() -> log.info("{} stream cancelled by client", provider.id()));
    }

    /**
     * 把上游错误响应体包成异常，供 onStatus 复用
     */
    private Mono<Throwable> upstreamError(ClientResponse response, String operation) {
        return response.bodyToMono(String.class)
                .flatMap(body -> Mono.error(new RuntimeException(
                        provider.id() + " " + operation + " failed: " + body)));
    }
}
