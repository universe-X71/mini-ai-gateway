package com.example.miniaigateway.service;

import io.r2dbc.spi.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.CorePublisher;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

@Slf4j
@Service
public class QuotaService {

    private final DatabaseClient databaseClient;

    public QuotaService(ConnectionFactory connectionFactory) {
        this.databaseClient = DatabaseClient.create(connectionFactory);
    }

    /**
     * 原子扣减每日配额。
     * 如果当天已重置，先重置再扣减；如果配额不足，返回 false。
     *
     * SQL 核心逻辑：
     *   UPDATE user_quota
     *   SET used_today = IF(last_reset = CURDATE(), used_today + ?, ?),
     *       last_reset = CURDATE()
     *   WHERE user_id = ?
     *     AND IF(last_reset = CURDATE(), used_today + ?, ?) <= daily_limit
     */
    public Mono<Boolean> tryConsume(String userId, int tokens) {
        String sql = """
            UPDATE user_quota
            SET used_today = IF(last_reset = CURDATE(), used_today + :tokens, :tokens),
                last_reset = CURDATE()
            WHERE user_id = :userId
              AND IF(last_reset = CURDATE(), used_today + :tokens, :tokens) <= daily_limit
            """;

        return databaseClient.sql(sql)
                .bind("tokens", tokens)
                .bind("userId", userId)
                .fetch()
                .rowsUpdated()
                .map(rows -> rows > 0)
                .doOnNext(ok -> {
                    if (!ok) log.warn("Quota exhausted for user {}: {} tokens", userId, tokens);
                });
    }

    /**
     * 如果用户不存在，先初始化一条记录
     */
    public Mono<Void> ensureUserExists(String userId, long dailyLimit) {
        String sql = """
            INSERT INTO user_quota (user_id, daily_limit, used_today, last_reset)
            VALUES (:userId, :dailyLimit, 0, CURDATE())
            ON DUPLICATE KEY UPDATE user_id = user_id
            """;
        return databaseClient.sql(sql)
                .bind("userId", userId)
                .bind("dailyLimit", dailyLimit)
                .then();
    }

    public Mono<Void> adjustQuota(String userId, Integer diff) {
        // diff 为负数：退还（used_today 减少）；正数：补扣（used_today 增加）
        String sql = """
        UPDATE user_quota
        SET used_today = GREATEST(0, used_today + :diff)
        WHERE user_id = :userId
        """;
        return databaseClient.sql(sql)
                .bind("diff", diff)
                .bind("userId", userId)
                .then();
    }
}