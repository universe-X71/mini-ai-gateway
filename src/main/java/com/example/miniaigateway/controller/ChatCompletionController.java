package com.example.miniaigateway.controller;

import com.example.miniaigateway.adapter.ModelAdapter;
import com.example.miniaigateway.adapter.impl.ModelAdapterFactory;
import com.example.miniaigateway.dto.ChatCompletionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final ModelAdapterFactory adapterFactory;

    public ChatCompletionController(ModelAdapterFactory adapterFactory) {
        this.adapterFactory = adapterFactory;
    }

    @PostMapping(value = "/chat/completions")
    public Mono<ResponseEntity<Object>> chatCompletions(
            @RequestBody ChatCompletionRequest request) {

        ModelAdapter adapter = adapterFactory.getAdapter(request.getModel());

        // 流式：返回 Flux<ServerSentEvent<String>>，Content-Type 为 text/event-stream
        if (Boolean.TRUE.equals(request.getStream())) {
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(
                            adapter.chatCompletionStream(request)
                                    .timeout(Duration.ofSeconds(60))
                                    .onErrorResume(e -> {
                                        log.error("Stream error: {}", e.getMessage());
                                        String errorJson = "{\"error\":\"" + e.getMessage() + "\"}";
                                        return Flux.just(
                                                ServerSentEvent.builder(errorJson).build(),
                                                ServerSentEvent.builder("[DONE]").build()
                                        );
                                    })
                    ));
        }

        // 非流式：返回 Mono<ChatCompletionResponse>，Content-Type 为 application/json
        return adapter.chatCompletion(request)
                .map(resp -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body((Object) resp));
    }
}