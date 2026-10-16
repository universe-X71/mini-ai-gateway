package com.example.miniaigateway.adapter;

/**
 * 网关支持的 AI 服务提供商。
 * provider id 会作为配置键（gateway.providers.*）、Redis key 前缀、
 * Resilience4j 熔断器/重试器名称使用，必须保持稳定。
 */
public enum Provider {

    OPENAI("openai"),
    DEEPSEEK("deepseek"),
    OLLAMA("ollama");

    private final String id;

    Provider(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
