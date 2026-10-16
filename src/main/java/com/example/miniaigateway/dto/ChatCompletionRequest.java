package com.example.miniaigateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class ChatCompletionRequest {

    private static final double DEFAULT_TEMPERATURE = 0.7;

    private String model;

    private List<Message> messages;

    private Double temperature = DEFAULT_TEMPERATURE;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    private Boolean stream = false;

    @Data
    public static class Message {
        private String role;
        private String content;
    }
}