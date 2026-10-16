package com.example.miniaigateway.auth;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ApiKeyRepository extends ReactiveCrudRepository<ApiKeyEntity, Long> {

    Mono<ApiKeyEntity> findByKeyHash(String keyHash);

    Flux<ApiKeyEntity> findByTenantId(String tenantId);
}