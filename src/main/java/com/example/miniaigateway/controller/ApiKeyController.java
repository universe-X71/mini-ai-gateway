package com.example.miniaigateway.controller;

import com.example.miniaigateway.auth.ApiKeyEntity;
import com.example.miniaigateway.auth.ApiKeyService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/admin/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * 创建 API Key
     */
    @PostMapping
    public Mono<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String tenantId = (String) body.get("tenantId");
        String tenantName = (String) body.get("tenantName");
        Integer rpmLimit = (Integer) body.get("rpmLimit");
        Integer tpmLimit = (Integer) body.get("tpmLimit");
        Long dailyQuota = body.get("dailyQuota") == null ? null
                : Long.valueOf(body.get("dailyQuota").toString());

        return apiKeyService.createKey(tenantId, tenantName, rpmLimit, tpmLimit, dailyQuota);
    }

    /**
     * 列出某个租户的所有 Key
     */
    @GetMapping("/tenant/{tenantId}")
    public Flux<ApiKeyEntity> listByTenant(@PathVariable String tenantId) {
        return apiKeyService.listKeys(tenantId)
                .map(e -> {
                    e.setKeyHash(null);   // 不返回哈希
                    return e;
                });
    }

    /**
     * 撤销 Key
     */
    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> revoke(@PathVariable Long id) {
        return apiKeyService.revokeKey(id)
                .map(ok -> Map.of("success", ok));
    }
}