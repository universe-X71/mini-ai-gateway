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

    // TODO(unused): 生产代码只用 getAllAdapters()，本方法仅被自身单测引用，待确认后删除
    /**
     * 根据模型名选择合适的适配器
     *
     * @deprecated 生产代码未使用；provider 筛选现由 RouterService.resolveCandidates 负责，待确认后删除
     */
    @Deprecated
    public ModelAdapter getAdapter(String model) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(model))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No adapter found for model: " + model));
    }

    // TODO(unused): 生产代码只用 getAllAdapters()，本方法仅被自身单测引用，待确认后删除
    /**
     * 按 provider 名称查找适配器
     *
     * @deprecated 生产代码未使用，待确认后删除
     */
    @Deprecated
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
