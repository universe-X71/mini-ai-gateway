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
public class OllamaAdapter implements ModelAdapter {

    private final WebClient.Builder webClientBuilder;
    private final ProviderProperties providerProperties;
    private WebClient webClient;
    private ProviderProperties.ProviderConfig config;

    public OllamaAdapter(WebClient.Builder webClientBuilder, ProviderProperties providerProperties) {
        this.webClientBuilder = webClientBuilder;
        this.providerProperties = providerProperties;
    }

    @PostConstruct
    public void init() {
        this.config = providerProperties.getProviders().get("ollama");
        this.webClient = webClientBuilder
                .clone()
                .baseUrl(config.getBaseUrl())  // http://localhost:11434/v1
                .build();
        // Ollama 不需要 Authorization 头
    }

    @Override
    public boolean supports(String model) {
        return model != null && (model.startsWith("qwen3.5") || model.startsWith("llama") || model.startsWith("mistral"));
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
                                Mono.error(new RuntimeException("ollama error: " + body))))
                .bodyToMono(ChatCompletionResponse.class)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()));
    }

    @Override
    public Flux<ServerSentEvent<String>> chatCompletionStream(ChatCompletionRequest request) {
        request.setStream(true);
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), resp ->
                        resp.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException("ollama stream error: " + body))))
                .bodyToFlux(String.class)  // 上游是 SSE，每行是一个 data: {...}
                .filter(line -> !line.isEmpty())
                .map(line -> {
                    // Spring WebFlux 会自动去掉 "data: " 前缀，这里直接拿到 JSON 或 [DONE]
                    return ServerSentEvent.builder(line).build();
                })
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .doOnCancel(() -> log.info("ollama stream cancelled by client"));
    }

    @Override
    public String providerName() {
        return "ollama";
    }
}