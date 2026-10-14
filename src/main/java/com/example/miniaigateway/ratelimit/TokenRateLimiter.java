package com.example.miniaigateway.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import reactor.core.CorePublisher;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
public class TokenRateLimiter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    private final RedisScript<Long> rpmScript;
    private final RedisScript<Long> tpmScript;

    @Value("${gateway.rate-limit.rpm:20}")
    private int rpmLimit;

    @Value("${gateway.rate-limit.tpm:100000}")
    private int tpmLimit;

    @Value("${gateway.rate-limit.window-seconds:60}")
    private int windowSeconds;

    public TokenRateLimiter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;

        this.rpmScript = new DefaultRedisScript<>();
        ((DefaultRedisScript<Long>) rpmScript).setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/rpm_limit.lua")));
        ((DefaultRedisScript<Long>) rpmScript).setResultType(Long.class);

        this.tpmScript = new DefaultRedisScript<>();
        ((DefaultRedisScript<Long>) tpmScript).setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/tpm_limit.lua")));
        ((DefaultRedisScript<Long>) tpmScript).setResultType(Long.class);
    }

    /**
     * RPM 限流：每分钟请求数
     */
    public Mono<Boolean> acquireRpm(String userId) {
        String key = "rl:rpm:" + userId;
        long now = System.currentTimeMillis();
        long window = windowSeconds * 1000L;

        return redisTemplate.execute(rpmScript,
                        List.of(key),
                        List.of(String.valueOf(now), String.valueOf(window), String.valueOf(rpmLimit)))
                .next()
                .map(result -> result == 1L)
                .doOnNext(allowed -> {
                    if (!allowed) log.warn("RPM limit exceeded for user {}", userId);
                });
    }

    /**
     * TPM 限流：每分钟 token 数
     */
    public Mono<Boolean> acquireTpm(String userId, int estimatedTokens) {
        String key = "rl:tpm:" + userId;
        long now = System.currentTimeMillis();
        long window = windowSeconds * 1000L;

        return redisTemplate.execute(tpmScript,
                        List.of(key),
                        List.of(String.valueOf(now), String.valueOf(window),
                                String.valueOf(tpmLimit), String.valueOf(estimatedTokens)))
                .next()
                .map(result -> result == 1L)
                .doOnNext(allowed -> {
                    if (!allowed) log.warn("TPM limit exceeded for user {}: {} tokens", userId, estimatedTokens);
                });
    }

    public Mono<Void> adjustTpm(String userId, int diff) {
        if (diff == 0) {
            return Mono.empty();
        }
        String key = "rl:tpm:" + userId;
        long now = System.currentTimeMillis();
        long window = windowSeconds * 1000L;

        // 用负数的 token 数作为一条新记录写入 ZSET，累加时会自动抵扣
        String member = now + "-adjust:" + diff;
        return redisTemplate.opsForZSet()
                .add(key, member, now)
                .then(redisTemplate.expire(key, java.time.Duration.ofMillis(window)))
                .then();
    }
}