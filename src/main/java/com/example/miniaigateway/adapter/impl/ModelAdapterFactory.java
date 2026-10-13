package com.example.miniaigateway.adapter.impl;

import com.example.miniaigateway.adapter.ModelAdapter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ModelAdapterFactory {

    private final List<ModelAdapter> adapters;

    public ModelAdapterFactory(List<ModelAdapter> adapters) {
        this.adapters = adapters;
    }

    /**
     * 根据模型名选择合适的适配器
     */
    public ModelAdapter getAdapter(String model) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(model))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No adapter found for model: " + model));
    }
}