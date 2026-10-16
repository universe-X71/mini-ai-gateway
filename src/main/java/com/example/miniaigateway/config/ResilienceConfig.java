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

    // ---- 熔断器 ----
    /** 统计窗口：最近 10 次调用 */
    private static final int SLIDING_WINDOW_SIZE = 10;
    /** 至少 5 次调用才开始统计 */
    private static final int MINIMUM_NUMBER_OF_CALLS = 5;
    /** 失败率超过 50% 熔断 */
    private static final float FAILURE_RATE_THRESHOLD = 50;
    /** 熔断后保持 30 秒 */
    private static final Duration WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(30);
    /** 半开状态允许 3 次试探 */
    private static final int PERMITTED_CALLS_IN_HALF_OPEN_STATE = 3;

    // ---- 重试 ----
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_INITIAL_INTERVAL_MILLIS = 500;
    private static final int RETRY_INTERVAL_MULTIPLIER = 2;

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(SLIDING_WINDOW_SIZE)
                .minimumNumberOfCalls(MINIMUM_NUMBER_OF_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(WAIT_DURATION_IN_OPEN_STATE)
                .permittedNumberOfCallsInHalfOpenState(PERMITTED_CALLS_IN_HALF_OPEN_STATE)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        RETRY_INITIAL_INTERVAL_MILLIS, RETRY_INTERVAL_MULTIPLIER))
                .retryExceptions(TimeoutException.class, IOException.class)
                .build();
        return RetryRegistry.of(config);
    }
}
