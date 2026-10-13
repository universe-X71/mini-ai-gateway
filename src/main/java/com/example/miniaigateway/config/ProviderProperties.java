package com.example.miniaigateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 将 application.yml 中 gateway.providers.* 的配置映射为 Java 对象
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway")
public class ProviderProperties {

    // Map 的 key 是 provider 名称（如 openai, deepseek, ollama），value 是具体配置
    private Map<String, ProviderConfig> providers = new HashMap<>();

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private Integer timeoutSeconds = 60;
        private String defaultModel;
    }
}