package com.example.dify.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DifyStreamEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesMessageEvent() throws Exception {
        String json = """
            {"event":"message","task_id":"t1","id":"m1","answer":"Hello",
             "conversation_id":"c1","created_at":1700000000}
            """;

        DifyStreamEvent event = objectMapper.readValue(json, DifyStreamEvent.class);

        assertThat(event.getEvent()).isEqualTo("message");
        assertThat(event.getTaskId()).isEqualTo("t1");
        assertThat(event.getAnswer()).isEqualTo("Hello");
        assertThat(event.getConversationId()).isEqualTo("c1");
        assertThat(event.getCreatedAt()).isEqualTo(1700000000L);
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        String json = "{\"event\":\"message\",\"brand_new_dify_field\":{\"x\":1}}";

        DifyStreamEvent event = objectMapper.readValue(json, DifyStreamEvent.class);

        assertThat(event.getEvent()).isEqualTo("message");
    }

    @Test
    void deserializesWorkflowFinishedEventWithDataMap() throws Exception {
        String json = """
            {"event":"workflow_finished","workflow_run_id":"run-1",
             "data":{"status":"succeeded","outputs":{"answer":"done"}}}
            """;

        DifyStreamEvent event = objectMapper.readValue(json, DifyStreamEvent.class);

        assertThat(event.getEvent()).isEqualTo("workflow_finished");
        assertThat(event.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(event.getData()).containsEntry("status", "succeeded");
    }

    @Test
    void coercesNumericMessageIdToString() throws Exception {
        String json = "{\"event\":\"message_end\",\"message_id\":789}";

        DifyStreamEvent event = objectMapper.readValue(json, DifyStreamEvent.class);

        assertThat(event.getMessageId()).isEqualTo("789");
    }
}
