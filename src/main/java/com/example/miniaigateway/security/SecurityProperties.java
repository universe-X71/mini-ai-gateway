package com.example.miniaigateway.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "gateway.security")
public class SecurityProperties {

    private PromptInjection promptInjection = new PromptInjection();
    private Pii pii = new Pii();

    @Data
    public static class PromptInjection {
        private boolean enabled = true;
        private String action = "REJECT";   // REJECT / LOG_ONLY
    }

    @Data
    public static class Pii {
        private boolean enabled = true;
        private String action = "REDACT";   // REJECT / REDACT / LOG_ONLY
    }
}