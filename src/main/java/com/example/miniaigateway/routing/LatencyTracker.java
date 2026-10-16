package com.example.miniaigateway.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class LatencyTracker {

    private static final int WINDOW_SIZE = 100;      // 最多保留最近 100 次采样
    private static final long WINDOW_TTL_SECONDS = 3600; // 1 小时后过期

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public LatencyTracker(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 记录一次请求的延迟采样
     *
     * @param providerName provider 名称（openai/deepseek/ollama）
     * @param ttftMs       首 Token 延迟（毫秒）
     * @param totalMs      总延迟（毫秒）
     */
    public Mono<Void> record(String providerName, long ttftMs, long totalMs) {
        String key = "latency:" + providerName;
        long now = System.currentTimeMillis();

        // member 格式：ttft:total:timestamp，保证唯一
        String member = ttftMs + ":" + totalMs + ":" + now;

        return redisTemplate.opsForZSet()
                .add(key, member, now)
                .then(redisTemplate.opsForZSet().count(key, Range.unbounded()))
                .flatMap(size -> {
                    if (size <= WINDOW_SIZE) return Mono.just(0L);
                    long toRemove = size - WINDOW_SIZE;
                    return redisTemplate.opsForZSet()
                            .removeRange(key, Range.closed(0L, toRemove - 1));
                })
                .then(redisTemplate.expire(key, Duration.ofSeconds(WINDOW_TTL_SECONDS)))
                .then()
                .doOnError(e -> log.warn("Failed to record latency for {}: {}", providerName, e.getMessage()));
    }

    /**
     * 获取某个 provider 的 P95 TTFT（毫秒）
     * 如果采样不足（少于 10 个），返回 -1 表示数据不足
     */
    public Mono<Long> getP95Ttft(String providerName) {
        String key = "latency:" + providerName;
        return redisTemplate.opsForZSet()
                .range(key, Range.closed(0L, -1L))
                .collectList()
                .map(members -> {
                    if (members.size() < 10) {
                        log.info("[P95] provider={} samples={} insufficient, return -1",
                                providerName, members.size());
                        return -1L;
                    }

                    List<Long> ttfts = members.stream()
                            .map(m -> Long.parseLong(m.split(":")[0]))
                            .sorted()
                            .toList();

                    int idx = (int) Math.ceil(ttfts.size() * 0.95) - 1;
                    long p95 = ttfts.get(Math.max(0, idx));
                    log.info("[P95] provider={} samples={} p95={}ms",
                            providerName, members.size(), p95);
                    return p95;
                });
    }

    /**
     * 获取某个 provider 的平均总延迟（毫秒），用于兜底排序
     */
    public Mono<Long> getAvgTotalLatency(String providerName) {
        String key = "latency:" + providerName;
        return redisTemplate.opsForZSet()
                .range(key, Range.closed(0L, -1L))
                .collectList()
                .map(members -> {
                    if (members.isEmpty()) return Long.MAX_VALUE;
                    return Math.round(members.stream()
                            .mapToLong(m -> Long.parseLong(m.split(":")[1]))
                            .average()
                            .orElse(Long.MAX_VALUE));
                });
    }

    /**
     * 获取某个 provider 的失败率（最近 100 次中失败的比例）
     * 失败记录通过 recordFailure 写入
     */
    public Mono<Double> getFailureRate(String providerName) {
        String key = "failure:" + providerName;
        return redisTemplate.opsForZSet()
                .count(key, Range.unbounded())
                .map(Long::doubleValue)
                .defaultIfEmpty(0.0)
                .map(failures -> {
                    if (failures == 0) return 0.0;
                    // 失败率 = 失败次数 / (失败次数 + 延迟采样数)
                    return failures / (failures + WINDOW_SIZE);
                });
    }

    /**
     * 记录一次失败
     */
    public Mono<Void> recordFailure(String providerName) {
        String key = "failure:" + providerName;
        long now = System.currentTimeMillis();
        String member = "fail:" + now;
        return redisTemplate.opsForZSet()
                .add(key, member, now)
                .then(redisTemplate.opsForZSet().count(key, Range.unbounded()))
                .flatMap(size -> {
                    if (size <= WINDOW_SIZE) {
                        return Mono.just(0L);
                    }
                    // 保留最后 WINDOW_SIZE 个，移除前面 size - WINDOW_SIZE 个
                    long toRemove = size - WINDOW_SIZE;
                    return redisTemplate.opsForZSet()
                            .removeRange(key, Range.closed(0L, toRemove - 1));
                })
                .then(redisTemplate.expire(key, Duration.ofSeconds(WINDOW_TTL_SECONDS)))
                .then()
                .doOnError(e -> log.warn("Failed to record failure for {}: {}", providerName, e.getMessage()));
    }
}