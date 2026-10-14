package com.example.miniaigateway.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;

@Data
@Table("user_quota")
public class UserQuotaEntity {

    @Id
    private String userId;
    private Long dailyLimit;
    private Long usedToday;
    private LocalDate lastReset;
}