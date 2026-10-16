package com.example.miniaigateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)                          // 最近 10 次调用
                .minimumNumberOfCalls(5)                        // 至少 5 次才开始统计
                .failureRateThreshold(50)                       // 失败率超过 50% 熔断
                .waitDurationInOpenState(Duration.ofSeconds(30))// 熔断 30 秒
                .permittedNumberOfCallsInHalfOpenState(3)       // 半开允许 3 次试探
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(500, 2))
                .retryExceptions(TimeoutException.class, IOException.class)
                .build();
        return RetryRegistry.of(config);
    }
}