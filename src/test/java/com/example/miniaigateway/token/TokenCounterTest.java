package com.example.miniaigateway.token;

import com.example.miniaigateway.dto.ChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenCounterTest {

    private final TokenCounter tokenCounter = new TokenCounter();

    @Test
    void countTokens_emptyString_returnsZero() {
        assertEquals(0, tokenCounter.countTokens(""));
        assertEquals(0, tokenCounter.countTokens(null));
    }

    @Test
    void countTokens_simpleText_returnsPositive() {
        int count = tokenCounter.countTokens("Hello world");
        assertTrue(count > 0, "Expected positive token count");
    }

    @Test
    void countPromptTokens_nullMessages_returnsZero() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setMessages(null);
        assertEquals(0, tokenCounter.countPromptTokens(request));
    }

    @Test
    void countPromptTokens_singleMessage_includesOverhead() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
        msg.setRole("user");
        msg.setContent("hi");
        request.setMessages(List.of(msg));

        int count = tokenCounter.countPromptTokens(request);
        // 4 (message overhead) + 2 (reply overhead) + tokens("user") + tokens("hi")
        assertTrue(count >= 6, "Expected at least 6 tokens, got " + count);
    }

    @Test
    void countPromptTokens_multipleMessages_accumulates() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        ChatCompletionRequest.Message m1 = new ChatCompletionRequest.Message();
        m1.setRole("user");
        m1.setContent("Hello");
        ChatCompletionRequest.Message m2 = new ChatCompletionRequest.Message();
        m2.setRole("assistant");
        m2.setContent("Hi there");
        request.setMessages(List.of(m1, m2));

        int count = tokenCounter.countPromptTokens(request);
        assertTrue(count > 8, "Expected more than 8 tokens for two messages, got " + count);
    }
}