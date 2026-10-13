package com.example.miniaigateway.controller;

import com.example.miniaigateway.adapter.ModelAdapter;
import com.example.miniaigateway.adapter.impl.ModelAdapterFactory;
import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.dto.ChatCompletionResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final ModelAdapterFactory adapterFactory;

    public ChatCompletionController(ModelAdapterFactory adapterFactory) {
        this.adapterFactory = adapterFactory;
    }

    @PostMapping("/chat/completions")
    public Mono<ChatCompletionResponse> chatCompletions(
            @RequestBody ChatCompletionRequest request) {
        ModelAdapter adapter = adapterFactory.getAdapter(request.getModel());
        return adapter.chatCompletion(request);
    }
}