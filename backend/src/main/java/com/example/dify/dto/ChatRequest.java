package com.example.dify.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Client-facing request body for the /api/chat/stream endpoint.
 */
@Data
public class ChatRequest {

    @NotBlank(message = "Message cannot be empty")
    @Size(max = 8000, message = "Message must be at most 8000 characters")
    private String message;

    private String conversationId;

    private Map<String, Object> inputs;
}
