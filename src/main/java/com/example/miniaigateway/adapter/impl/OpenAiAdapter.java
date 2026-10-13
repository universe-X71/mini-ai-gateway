package com.example.miniaigateway.adapter.impl;

import com.example.miniaigateway.adapter.ModelAdapter;
import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.dto.ChatCompletionResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Component
public class OpenAiAdapter implements ModelAdapter {

    @Override
    public boolean supports(String model) {
        return model != null && (model.startsWith("gpt-") || model.startsWith("o1-"));
    }

    @Override
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        // Day 3 先用 Mock 返回，Day 4 接入真实 OpenAI API
        return Mono.fromCallable(() -> buildMockResponse(request));
    }

    @Override
    public String providerName() {
        return "openai";
    }

    private ChatCompletionResponse buildMockResponse(ChatCompletionRequest request) {
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setId("chatcmpl-" + UUID.randomUUID().toString().substring(0, 8));
        response.setCreated(System.currentTimeMillis() / 1000);
        response.setModel(request.getModel());

        ChatCompletionResponse.Message message = new ChatCompletionResponse.Message();
        message.setRole("assistant");
        message.setContent("[OpenAI Mock] 收到你的消息: " +
                request.getMessages().get(request.getMessages().size() - 1).getContent());

        ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason("stop");

        response.setChoices(List.of(choice));

        ChatCompletionResponse.Usage usage = new ChatCompletionResponse.Usage();
        usage.setPromptTokens(10);
        usage.setCompletionTokens(20);
        usage.setTotalTokens(30);
        response.setUsage(usage);

        return response;
    }
}