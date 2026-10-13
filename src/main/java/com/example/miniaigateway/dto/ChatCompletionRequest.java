package com.example.miniaigateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class ChatCompletionRequest {

    private String model;

    private List<Message> messages;

    private Double temperature = 0.7;

    @JsonProperty("max_tokens")
    private Integer maxTokens = 1000;

    private Boolean stream = false;

    @Data
    public static class Message {
        private String role;
        private String content;
    }
}