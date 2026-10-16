package com.example.miniaigateway.routing;

import lombok.Data;

/**
 * 单个模型的实时定价，单位：USD per token
 */
@Data
public class ModelPricing {
    private String modelId;
    private Double promptPrice;       // 输入单价
    private Double completionPrice;   // 输出单价

    /**
     * 估算本次请求的成本（USD）
     */
    public double estimateCost(int promptTokens, int completionTokens) {
        return promptTokens * promptPrice + completionTokens * completionPrice;
    }
}