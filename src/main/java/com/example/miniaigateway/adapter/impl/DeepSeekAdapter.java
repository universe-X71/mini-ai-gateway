package com.example.miniaigateway.adapter.impl;

import com.example.miniaigateway.adapter.Provider;
import com.example.miniaigateway.config.ProviderProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Component
public class DeepSeekAdapter extends AbstractWebClientModelAdapter {

    private static final List<String> MODEL_PREFIXES = List.of("deepseek-");

    public DeepSeekAdapter(WebClient.Builder webClientBuilder, ProviderProperties providerProperties) {
        super(webClientBuilder, providerProperties, Provider.DEEPSEEK, true);
    }

    @Override
    protected List<String> modelPrefixes() {
        return MODEL_PREFIXES;
    }
}
