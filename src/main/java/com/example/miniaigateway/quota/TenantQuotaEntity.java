package com.example.miniaigateway.quota;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;

@Data
@Table("tenant_quota")
public class TenantQuotaEntity {

    @Id
    private String tenantId;
    private Long dailyLimit;
    private Long usedToday;
    private LocalDate lastReset;
}
