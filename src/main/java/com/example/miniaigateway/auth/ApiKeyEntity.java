package com.example.miniaigateway.auth;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Table("api_key")
public class ApiKeyEntity {

    @Id
    private Long id;

    private String keyHash;
    private String keyPrefix;
    private String tenantId;
    private String tenantName;
    private String status;
    private Integer rpmLimit;
    private Integer tpmLimit;
    private Long dailyQuota;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
}