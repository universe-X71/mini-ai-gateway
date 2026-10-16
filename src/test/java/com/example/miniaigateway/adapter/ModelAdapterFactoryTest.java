package com.example.miniaigateway.adapter;

import com.example.miniaigateway.adapter.impl.ModelAdapterFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// getAdapter / getByProviderName 已被标记为 @Deprecated（生产代码无引用），
// 但保留测试以确保标记期间行为不回退
@SuppressWarnings("deprecation")
class ModelAdapterFactoryTest {

    private ModelAdapter mockAdapter(String provider, String... supportedModels) {
        ModelAdapter adapter = mock(ModelAdapter.class);
        when(adapter.providerName()).thenReturn(provider);
        when(adapter.supports(anyString())).thenAnswer(invocation -> {
            String model = invocation.getArgument(0);
            for (String s : supportedModels) {
                if (model.startsWith(s)) return true;
            }
            return false;
        });
        return adapter;
    }

    @Test
    void getAdapter_matchesByPrefix() {
        ModelAdapter openai = mockAdapter("openai", "gpt-");
        ModelAdapter deepseek = mockAdapter("deepseek", "deepseek-");

        ModelAdapterFactory factory = new ModelAdapterFactory(List.of(openai, deepseek));

        assertSame(openai, factory.getAdapter("gpt-4o-mini"));
        assertSame(deepseek, factory.getAdapter("deepseek-chat"));
    }

    @Test
    void getAdapter_unknownModel_throws() {
        ModelAdapter openai = mockAdapter("openai", "gpt-");
        ModelAdapterFactory factory = new ModelAdapterFactory(List.of(openai));

        assertThrows(IllegalArgumentException.class,
                () -> factory.getAdapter("unknown-model"));
    }

    @Test
    void getByProviderName_caseInsensitive() {
        ModelAdapter openai = mockAdapter("openai", "gpt-");
        ModelAdapterFactory factory = new ModelAdapterFactory(List.of(openai));

        assertSame(openai, factory.getByProviderName("OpenAI"));
        assertSame(openai, factory.getByProviderName("openai"));
    }

    @Test
    void getAllAdapters_returnsAll() {
        ModelAdapter a = mockAdapter("a");
        ModelAdapter b = mockAdapter("b");
        ModelAdapterFactory factory = new ModelAdapterFactory(List.of(a, b));
        assertEquals(2, factory.getAllAdapters().size());
    }
}