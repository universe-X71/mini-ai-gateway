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

    /** 采样不足时的哨兵值，调用方据此把该 provider 排到最后 */
    public static final long INSUFFICIENT_DATA = -1L;

    private static final String LATENCY_KEY_PREFIX = "latency:";
    private static final String FAILURE_KEY_PREFIX = "failure:";

    /** ZSET member 内各字段的分隔符，格式：ttft:total:timestamp */
    private static final String MEMBER_DELIMITER = ":";
    private static final int TTFT_FIELD_INDEX = 0;
    private static final int TOTAL_FIELD_INDEX = 1;

    private static final int WINDOW_SIZE = 100;          // 最多保留最近 100 次采样
    private static final long WINDOW_TTL_SECONDS = 3600; // 1 小时后过期
    private static final int MIN_SAMPLES_FOR_P95 = 10;   // 少于该采样数不出 P95
    private static final double P95_PERCENTILE = 0.95;

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
        String key = LATENCY_KEY_PREFIX + providerName;
        long now = System.currentTimeMillis();

        // member 格式：ttft:total:timestamp，保证唯一
        String member = ttftMs + MEMBER_DELIMITER + totalMs + MEMBER_DELIMITER + now;

        return redisTemplate.opsForZSet()
                .add(key, member, now)
                .then(trimToWindow(key))
                .then(redisTemplate.expire(key, Duration.ofSeconds(WINDOW_TTL_SECONDS)))
                .then()
                .doOnError(e -> log.warn("Failed to record latency for {}: {}", providerName, e.getMessage()));
    }

    /**
     * 获取某个 provider 的 P95 TTFT（毫秒）
     * 如果采样不足（少于 {@link #MIN_SAMPLES_FOR_P95} 个），返回 {@link #INSUFFICIENT_DATA}
     */
    public Mono<Long> getP95Ttft(String providerName) {
        String key = LATENCY_KEY_PREFIX + providerName;
        return redisTemplate.opsForZSet()
                .range(key, Range.closed(0L, -1L))
                .collectList()
                .map(members -> {
                    if (members.size() < MIN_SAMPLES_FOR_P95) {
                        log.info("[P95] provider={} samples={} insufficient, return {}",
                                providerName, members.size(), INSUFFICIENT_DATA);
                        return INSUFFICIENT_DATA;
                    }

                    List<Long> ttfts = members.stream()
                            .map(m -> parseField(m, TTFT_FIELD_INDEX))
                            .sorted()
                            .toList();

                    int idx = (int) Math.ceil(ttfts.size() * P95_PERCENTILE) - 1;
                    long p95 = ttfts.get(Math.max(0, idx));
                    log.info("[P95] provider={} samples={} p95={}ms",
                            providerName, members.size(), p95);
                    return p95;
                });
    }

    /**
     * 记录一次失败
     */
    public Mono<Void> recordFailure(String providerName) {
        String key = FAILURE_KEY_PREFIX + providerName;
        long now = System.currentTimeMillis();
        String member = "fail:" + now;
        return redisTemplate.opsForZSet()
                .add(key, member, now)
                .then(trimToWindow(key))
                .then(redisTemplate.expire(key, Duration.ofSeconds(WINDOW_TTL_SECONDS)))
                .then()
                .doOnError(e -> log.warn("Failed to record failure for {}: {}", providerName, e.getMessage()));
    }

    /**
     * 只保留 ZSET 中最近 {@link #WINDOW_SIZE} 个成员，超出的最旧部分裁掉
     */
    private Mono<Long> trimToWindow(String key) {
        return redisTemplate.opsForZSet()
                .count(key, Range.unbounded())
                .flatMap(size -> {
                    if (size <= WINDOW_SIZE) {
                        return Mono.just(0L);
                    }
                    long toRemove = size - WINDOW_SIZE;
                    return redisTemplate.opsForZSet()
                            .removeRange(key, Range.closed(0L, toRemove - 1));
                });
    }

    private long parseField(String member, int fieldIndex) {
        return Long.parseLong(member.split(MEMBER_DELIMITER)[fieldIndex]);
    }

    /**
     * 获取某个 provider 的平均总延迟（毫秒），用于兜底排序
     *
     * @deprecated 目前无生产引用，待确认后删除（TODO(unused)）
     */
    @Deprecated
    public Mono<Long> getAvgTotalLatency(String providerName) {
        String key = LATENCY_KEY_PREFIX + providerName;
        return redisTemplate.opsForZSet()
                .range(key, Range.closed(0L, -1L))
                .collectList()
                .map(members -> {
                    if (members.isEmpty()) return Long.MAX_VALUE;
                    return Math.round(members.stream()
                            .mapToLong(m -> parseField(m, TOTAL_FIELD_INDEX))
                            .average()
                            .orElse(Long.MAX_VALUE));
                });
    }

    /**
     * 获取某个 provider 的失败率（最近 100 次中失败的比例）
     * 失败记录通过 {@link #recordFailure(String)} 写入
     *
     * @deprecated 目前无生产引用，待确认后删除（TODO(unused)）
     */
    @Deprecated
    public Mono<Double> getFailureRate(String providerName) {
        String key = FAILURE_KEY_PREFIX + providerName;
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
}
