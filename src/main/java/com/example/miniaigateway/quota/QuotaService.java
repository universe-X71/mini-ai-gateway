package com.example.miniaigateway.quota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class QuotaService {

    private final UserQuotaRepository userQuotaRepository;

    public QuotaService(UserQuotaRepository userQuotaRepository) {
        this.userQuotaRepository = userQuotaRepository;
    }

    /**
     * 原子扣减每日配额。SQL 见 {@link UserQuotaRepository#tryConsume(String, int)}：
     * 如果当天已重置，先重置再扣减；如果配额不足，返回 false。
     */
    public Mono<Boolean> tryConsume(String userId, int tokens) {
        return userQuotaRepository.tryConsume(userId, tokens)
                .doOnNext(ok -> {
                    if (!ok) log.warn("Quota exhausted for user {}: {} tokens", userId, tokens);
                });
    }

    /**
     * 如果用户不存在，先初始化一条记录
     */
    public Mono<Void> ensureUserExists(String userId, long dailyLimit) {
        return userQuotaRepository.ensureUserExists(userId, dailyLimit);
    }

    public Mono<Void> adjustQuota(String userId, int diff) {
        return userQuotaRepository.adjustQuota(userId, diff);
    }
}
