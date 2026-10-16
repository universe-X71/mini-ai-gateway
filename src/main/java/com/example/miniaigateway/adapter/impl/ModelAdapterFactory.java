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

    /**
     * 按 provider 名称查找适配器
     */
    public ModelAdapter getByProviderName(String providerName) {
        return adapters.stream()
                .filter(adapter -> adapter.providerName().equalsIgnoreCase(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Provider not found: " + providerName));
    }

    /**
     * 返回所有已注册的适配器（Day 6 新增，RouterService 使用）
     */
    public List<ModelAdapter> getAllAdapters() {
        return adapters;
    }
}