package com.example.dify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * A single SSE event emitted by Dify (chat or workflow app).
 * Unknown fields are ignored so new Dify event fields never break parsing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DifyStreamEvent {

    @JsonProperty("event")
    private String event;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("id")
    private String id;

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("answer")
    private String answer;

    @JsonProperty("created_at")
    private Long createdAt;

    @JsonProperty("error")
    private String error;

    @JsonProperty("data")
    private Map<String, Object> data;

    // Additional fields for specific event types
    @JsonProperty("tool_call_id")
    private String toolCallId;

    @JsonProperty("tool_call")
    private Map<String, Object> toolCall;

    @JsonProperty("tool_response")
    private Map<String, Object> toolResponse;

    @JsonProperty("workflow_run_id")
    private String workflowRunId;
}
