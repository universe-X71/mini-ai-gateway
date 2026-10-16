package com.example.miniaigateway.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelPricingTest {

    @Test
    void estimateCost_calculatesCorrectly() {
        ModelPricing pricing = new ModelPricing();
        pricing.setPromptPrice(0.00000015);    // 0.15 USD / 1M tokens
        pricing.setCompletionPrice(0.0000006); // 0.6 USD / 1M tokens

        // 1000 prompt + 500 completion
        double cost = pricing.estimateCost(1000, 500);
        double expected = 1000 * 0.00000015 + 500 * 0.0000006;
        assertEquals(expected, cost, 1e-12);
    }

    @Test
    void estimateCost_zeroTokens_returnsZero() {
        ModelPricing pricing = new ModelPricing();
        pricing.setPromptPrice(0.00000015);
        pricing.setCompletionPrice(0.0000006);
        assertEquals(0.0, pricing.estimateCost(0, 0), 1e-12);
    }
}