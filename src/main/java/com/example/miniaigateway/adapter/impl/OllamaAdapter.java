package com.example.miniaigateway.adapter.impl;

import com.example.miniaigateway.adapter.Provider;
import com.example.miniaigateway.config.ProviderProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Component
public class OllamaAdapter extends AbstractWebClientModelAdapter {

    private static final List<String> MODEL_PREFIXES = List.of("qwen3.5", "llama", "mistral");

    public OllamaAdapter(WebClient.Builder webClientBuilder, ProviderProperties providerProperties) {
        // Ollama 本地部署，不需要 Authorization 头
        super(webClientBuilder, providerProperties, Provider.OLLAMA, false);
    }

    @Override
    protected List<String> modelPrefixes() {
        return MODEL_PREFIXES;
    }
}
