package com.example.miniaigateway.adapter.impl;

import com.example.miniaigateway.adapter.ModelAdapter;
import com.example.miniaigateway.config.ProviderProperties;
import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.dto.ChatCompletionResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Component
public class OpenAiAdapter implements ModelAdapter {

    private final WebClient.Builder webClientBuilder;
    private final ProviderProperties providerProperties;
    private WebClient webClient;
    private ProviderProperties.ProviderConfig config;

    public OpenAiAdapter(WebClient.Builder webClientBuilder, ProviderProperties providerProperties) {
        this.webClientBuilder = webClientBuilder;
        this.providerProperties = providerProperties;
    }

    @PostConstruct
    public void init() {
        this.config = providerProperties.getProviders().get("openai");
        if (config == null) {
            log.warn("OpenAI provider config not found");
            return;
        }
        this.webClient = webClientBuilder
                .clone()
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .build();
        log.info("OpenAiAdapter initialized with baseUrl={}", config.getBaseUrl());
    }

    @Override
    public boolean supports(String model) {
        return model != null && (model.startsWith("gpt-") || model.startsWith("o1-"));
    }

    @Override
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        request.setStream(false);
        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), resp ->
                        resp.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException("OpenAI error: " + body))))
                .bodyToMono(ChatCompletionResponse.class)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()));
    }

    @Override
    public Flux<ServerSentEvent<String>> chatCompletionStream(ChatCompletionRequest request) {
        request.setStream(true);
        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), resp ->
                        resp.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException("OpenAI stream error: " + body))))
                .bodyToFlux(String.class)  // 上游是 SSE，每行是一个 data: {...}
                .filter(line -> !line.isEmpty())
                .map(line -> {
                    // Spring WebFlux 会自动去掉 "data: " 前缀，这里直接拿到 JSON 或 [DONE]
                    return ServerSentEvent.builder(line).build();
                })
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .doOnCancel(() -> log.info("OpenAI stream cancelled by client"));
    }

    @Override
    public String providerName() {
        return "openai";
    }
}