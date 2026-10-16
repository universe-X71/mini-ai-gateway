package com.example.miniaigateway.quota;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface TenantQuotaRepository extends ReactiveCrudRepository<TenantQuotaEntity, String> {

    /**
     * 原子扣减每日配额：判断是否跨天重置、扣减、配额校验全部在 DB 侧的同一条 UPDATE 内完成，
     * 避免「读-改-写」带来的并发超扣。
     *
     * @return true 表示扣减成功（配额充足），false 表示配额已耗尽
     */
    @Modifying
    @Query("""
            UPDATE tenant_quota
            SET used_today = IF(last_reset = CURDATE(), used_today + :tokens, :tokens),
                last_reset = CURDATE()
            WHERE tenant_id = :tenantId
              AND IF(last_reset = CURDATE(), used_today + :tokens, :tokens) <= daily_limit
            """)
    Mono<Boolean> tryConsume(@Param("tenantId") String tenantId, @Param("tokens") int tokens);

    /**
     * 如果租户不存在，先初始化一条记录
     */
    @Modifying
    @Query("""
            INSERT INTO tenant_quota (tenant_id, daily_limit, used_today, last_reset)
            VALUES (:tenantId, :dailyLimit, 0, CURDATE())
            ON DUPLICATE KEY UPDATE tenant_id = tenant_id
            """)
    Mono<Void> ensureTenantExists(@Param("tenantId") String tenantId, @Param("dailyLimit") long dailyLimit);

    /**
     * 校准配额：diff 为负数表示退还（used_today 减少），正数表示补扣（used_today 增加）
     */
    @Modifying
    @Query("""
            UPDATE tenant_quota
            SET used_today = GREATEST(0, used_today + :diff)
            WHERE tenant_id = :tenantId
            """)
    Mono<Void> adjustQuota(@Param("tenantId") String tenantId, @Param("diff") int diff);
}
