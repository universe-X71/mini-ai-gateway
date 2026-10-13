package com.example.miniaigateway.adapter;

import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.dto.ChatCompletionResponse;
import reactor.core.publisher.Mono;

public interface ModelAdapter {

    /**
     * 适配器负责处理的模型名前缀，比如 "gpt-" 对应 OpenAI
     */
    boolean supports(String model);

    /**
     * 非流式对话
     */
    Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request);

    /**
     * 适配器名称，用于日志和 fallback 标记
     */
    String providerName();
}