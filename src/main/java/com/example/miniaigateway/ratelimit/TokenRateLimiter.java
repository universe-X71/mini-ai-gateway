package com.example.miniaigateway.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class TokenRateLimiter {

    private static final String KEY_PREFIX_RPM = "rl:rpm:";
    private static final String KEY_PREFIX_TPM = "rl:tpm:";

    /** Lua 脚本约定：1 = 放行，0 = 拒绝 */
    private static final long SCRIPT_ALLOWED = 1L;

    private static final String SCRIPT_RPM = "scripts/rpm_limit.lua";
    private static final String SCRIPT_TPM = "scripts/tpm_limit.lua";

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    private final RedisScript<Long> rpmScript;
    private final RedisScript<Long> tpmScript;

    @Value("${gateway.rate-limit.rpm}")
    private int rpmLimit;

    @Value("${gateway.rate-limit.tpm}")
    private int tpmLimit;

    @Value("${gateway.rate-limit.window-seconds}")
    private int windowSeconds;

    public TokenRateLimiter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rpmScript = loadScript(SCRIPT_RPM);
        this.tpmScript = loadScript(SCRIPT_TPM);
    }

    private static RedisScript<Long> loadScript(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * RPM 限流：每分钟请求数
     */
    public Mono<Boolean> acquireRpm(String userId) {
        String key = KEY_PREFIX_RPM + userId;
        long now = System.currentTimeMillis();
        long window = windowSeconds * 1000L;

        return redisTemplate.execute(rpmScript,
                        List.of(key),
                        List.of(String.valueOf(now), String.valueOf(window), String.valueOf(rpmLimit)))
                .next()
                .map(result -> result == SCRIPT_ALLOWED)
                .doOnNext(allowed -> {
                    if (!allowed) log.warn("RPM limit exceeded for user {}", userId);
                });
    }

    /**
     * TPM 限流：每分钟 token 数
     */
    public Mono<Boolean> acquireTpm(String userId, int estimatedTokens) {
        String key = KEY_PREFIX_TPM + userId;
        long now = System.currentTimeMillis();
        long window = windowSeconds * 1000L;

        return redisTemplate.execute(tpmScript,
                        List.of(key),
                        List.of(String.valueOf(now), String.valueOf(window),
                                String.valueOf(tpmLimit), String.valueOf(estimatedTokens)))
                .next()
                .map(result -> result == SCRIPT_ALLOWED)
                .doOnNext(allowed -> {
                    if (!allowed) log.warn("TPM limit exceeded for user {}: {} tokens", userId, estimatedTokens);
                });
    }

    public Mono<Void> adjustTpm(String userId, int diff) {
        if (diff == 0) {
            return Mono.empty();
        }
        String key = KEY_PREFIX_TPM + userId;
        long now = System.currentTimeMillis();
        long window = windowSeconds * 1000L;

        // 用负数的 token 数作为一条新记录写入 ZSET，累加时会自动抵扣
        String member = now + "-adjust:" + diff;
        return redisTemplate.opsForZSet()
                .add(key, member, now)
                .then(redisTemplate.expire(key, Duration.ofMillis(window)))
                .then();
    }
}
