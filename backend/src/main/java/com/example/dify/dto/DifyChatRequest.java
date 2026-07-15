package com.example.dify.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request body sent to Dify's POST /chat-messages endpoint.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DifyChatRequest {

    @JsonProperty("inputs")
    @Builder.Default
    private Map<String, Object> inputs = Map.of();

    @JsonProperty("query")
    private String query;

    @JsonProperty("response_mode")
    @Builder.Default
    private String responseMode = "streaming";

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("user")
    private String user;

    @JsonProperty("files")
    @Builder.Default
    private List<Object> files = List.of();
}
