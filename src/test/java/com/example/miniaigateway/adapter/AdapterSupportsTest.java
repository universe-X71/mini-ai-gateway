package com.example.miniaigateway.adapter;

import com.example.miniaigateway.adapter.impl.DeepSeekAdapter;
import com.example.miniaigateway.adapter.impl.OllamaAdapter;
import com.example.miniaigateway.adapter.impl.OpenAiAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三个适配器的模型前缀匹配逻辑收敛到 {@code AbstractWebClientModelAdapter.supports} 后，
 * 这里是离线可自动化验证的那部分。构造器不触碰配置，{@code @PostConstruct} 不会被调用，
 * 因此传入 null 依赖即可。
 */
class AdapterSupportsTest {

    private final OpenAiAdapter openAi = new OpenAiAdapter(null, null);
    private final DeepSeekAdapter deepSeek = new DeepSeekAdapter(null, null);
    private final OllamaAdapter ollama = new OllamaAdapter(null, null);

    @Test
    void openAi_supportsGptAndO1Prefixes() {
        assertTrue(openAi.supports("gpt-4o-mini"));
        assertTrue(openAi.supports("o1-preview"));
        assertFalse(openAi.supports("deepseek-chat"));
        assertFalse(openAi.supports("llama3"));
    }

    @Test
    void deepSeek_supportsDeepSeekPrefix() {
        assertTrue(deepSeek.supports("deepseek-chat"));
        assertTrue(deepSeek.supports("deepseek-reasoner"));
        assertFalse(deepSeek.supports("gpt-4o-mini"));
    }

    @Test
    void ollama_supportsLocalModelPrefixes() {
        assertTrue(ollama.supports("qwen3.5:7b"));
        assertTrue(ollama.supports("llama3.1"));
        assertTrue(ollama.supports("mistral-nemo"));
        assertFalse(ollama.supports("gpt-4o-mini"));
    }

    @Test
    void supports_nullModel_returnsFalse() {
        assertFalse(openAi.supports(null));
        assertFalse(deepSeek.supports(null));
        assertFalse(ollama.supports(null));
    }

    @Test
    void providerName_matchesConfiguredProviderId() {
        assertEquals(Provider.OPENAI.id(), openAi.providerName());
        assertEquals(Provider.DEEPSEEK.id(), deepSeek.providerName());
        assertEquals(Provider.OLLAMA.id(), ollama.providerName());
    }
}
