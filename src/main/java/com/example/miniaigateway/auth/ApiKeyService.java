package com.example.miniaigateway.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "sk-";
    private static final int RANDOM_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final ApiKeyRepository repository;

    public ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * 创建 API Key
     * @return 明文 key（只返回一次，后续无法再查询）
     */
    public Mono<Map<String, Object>> createKey(String tenantId, String tenantName,
                                               Integer rpmLimit, Integer tpmLimit,
                                               Long dailyQuota) {
        // 1. 生成明文 key
        String plainKey = generateKey();
        String hash = sha256(plainKey);
        String prefix = plainKey.substring(0, 10);   // "sk-" + 7 位

        // 2. 构造实体
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyHash(hash);
        entity.setKeyPrefix(prefix);
        entity.setTenantId(tenantId);
        entity.setTenantName(tenantName);
        entity.setStatus("ACTIVE");
        entity.setRpmLimit(rpmLimit == null ? 20 : rpmLimit);
        entity.setTpmLimit(tpmLimit == null ? 100000 : tpmLimit);
        entity.setDailyQuota(dailyQuota == null ? 1000000L : dailyQuota);
        entity.setCreatedAt(LocalDateTime.now());

        // 3. 保存
        return repository.save(entity)
                .map(saved -> Map.of(
                        "id", saved.getId(),
                        "key", plainKey,                 // 只返回一次
                        "keyPrefix", saved.getKeyPrefix(),
                        "tenantId", saved.getTenantId(),
                        "rpmLimit", saved.getRpmLimit(),
                        "tpmLimit", saved.getTpmLimit(),
                        "dailyQuota", saved.getDailyQuota(),
                        "createdAt", saved.getCreatedAt().toString()
                ));
    }

    /**
     * 校验 API Key
     * @return 有效则返回实体，无效返回 Mono.empty()
     */
    public Mono<ApiKeyEntity> validateKey(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return Mono.empty();
        }
        String hash = sha256(plainKey);
        return repository.findByKeyHash(hash)
                .filter(this::isKeyValid)
                .doOnNext(entity -> {
                    // 异步更新最后使用时间，不阻塞主链路
                    updateLastUsed(entity.getId());
                });
    }

    private boolean isKeyValid(ApiKeyEntity entity) {
        if (!"ACTIVE".equals(entity.getStatus())) {
            log.debug("Key {} is not active", entity.getKeyPrefix());
            return false;
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.debug("Key {} has expired", entity.getKeyPrefix());
            return false;
        }
        return true;
    }

    /**
     * 撤销 Key
     */
    public Mono<Boolean> revokeKey(Long id) {
        return repository.findById(id)
                .flatMap(entity -> {
                    entity.setStatus("REVOKED");
                    entity.setRevokedAt(LocalDateTime.now());
                    return repository.save(entity);
                })
                .map(saved -> true)
                .defaultIfEmpty(false);
    }

    /**
     * 查看某个租户的所有 Key
     */
    public Flux<ApiKeyEntity> listKeys(String tenantId) {
        return repository.findByTenantId(tenantId);
    }

    private void updateLastUsed(Long id) {
        repository.findById(id)
                .flatMap(e -> {
                    e.setLastUsedAt(LocalDateTime.now());
                    return repository.save(e);
                })
                .subscribe(
                        null,
                        e -> log.debug("Failed to update lastUsedAt: {}", e.getMessage())
                );
    }

    // -------- 工具方法 --------

    private String generateKey() {
        StringBuilder sb = new StringBuilder(KEY_PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}