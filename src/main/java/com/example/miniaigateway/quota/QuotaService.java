package com.example.miniaigateway.quota;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class QuotaService {

    private final TenantQuotaRepository tenantQuotaRepository;

    public QuotaService(TenantQuotaRepository tenantQuotaRepository) {
        this.tenantQuotaRepository = tenantQuotaRepository;
    }

    /**
     * 原子扣减每日配额。SQL 见 {@link TenantQuotaRepository#tryConsume(String, int)}：
     * 如果当天已重置，先重置再扣减；如果配额不足，返回 false。
     */
    public Mono<Boolean> tryConsume(String tenantId, int tokens) {
        return tenantQuotaRepository.tryConsume(tenantId, tokens)
                .doOnNext(ok -> {
                    if (!ok) log.warn("Quota exhausted for tenant {}: {} tokens", tenantId, tokens);
                });
    }

    /**
     * 如果租户不存在，先初始化一条记录
     */
    public Mono<Void> ensureTenantExists(String tenantId, long dailyLimit) {
        return tenantQuotaRepository.ensureTenantExists(tenantId, dailyLimit);
    }

    public Mono<Void> adjustQuota(String tenantId, int diff) {
        return tenantQuotaRepository.adjustQuota(tenantId, diff);
    }
}
