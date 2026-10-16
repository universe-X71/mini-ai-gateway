package com.example.miniaigateway.routing;

/**
 * 包装业务响应，额外携带实际使用的 provider 和是否发生了 fallback
 *
 * @param body     业务响应体（如 ChatCompletionResponse）
 * @param provider 实际使用的 provider 名称（如 openai、deepseek）
 * @param fallback 是否发生了 fallback（true 表示主 provider 失败，用了备用）
 */
public record ProviderResponse<T>(T body, String provider, boolean fallback) {
}