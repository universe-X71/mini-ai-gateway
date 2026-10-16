package com.example.miniaigateway.routing;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ModelPricingRepository extends ReactiveCrudRepository<ModelPricingEntity, Long> {

    Mono<ModelPricingEntity> findByModelId(String modelId);

    Flux<ModelPricingEntity> findByEnabledTrue();
}