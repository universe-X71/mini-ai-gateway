package com.example.miniaigateway.routing;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Table("model_pricing")
public class ModelPricingEntity {

    @Id
    private Long id;

    private String modelId;
    private String displayName;
    private BigDecimal promptPrice;
    private BigDecimal completionPrice;
    private String source;
    private Boolean enabled;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}