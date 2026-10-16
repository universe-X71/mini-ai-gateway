package com.example.miniaigateway.token;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.example.miniaigateway.dto.ChatCompletionRequest;
import org.springframework.stereotype.Component;

@Component
public class TokenCounter {

    /** 每条消息的固定开销（role 与分隔符），OpenAI 官方建议值 */
    private static final int TOKENS_PER_MESSAGE = 4;

    /** 回复时的固定开销 */
    private static final int REPLY_PRIMING_TOKENS = 2;

    // cl100k_base 是 GPT-4、GPT-3.5-turbo 使用的编码
    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

    /**
     * 计算单段文本的 token 数
     */
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * 预估整个请求的 prompt token 数
     * OpenAI 官方建议：每条消息额外加 4 个 token（role 和分隔符）
     */
    public int countPromptTokens(ChatCompletionRequest request) {
        if (request.getMessages() == null) {
            return 0;
        }
        int total = 0;
        for (ChatCompletionRequest.Message msg : request.getMessages()) {
            total += TOKENS_PER_MESSAGE;
            total += countTokens(msg.getRole());
            total += countTokens(msg.getContent());
        }
        total += REPLY_PRIMING_TOKENS;
        return total;
    }
}