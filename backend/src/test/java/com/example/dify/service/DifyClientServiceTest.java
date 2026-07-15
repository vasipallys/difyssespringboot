package com.example.dify.service;

import com.example.dify.dto.DifyChatRequest;
import com.example.dify.dto.DifyStreamEvent;
import com.example.dify.exception.DifyApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DifyClientServiceTest {

    private MockWebServer server;
    private DifyClientService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WebClient webClient = WebClient.builder()
            .baseUrl(server.url("/v1").toString())
            .defaultHeader("Authorization", "Bearer test-key")
            .build();
        service = new DifyClientService(webClient, objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private DifyChatRequest sampleRequest() {
        return DifyChatRequest.builder()
            .query("Hello")
            .user("test-user")
            .build();
    }

    @Test
    void streamChat_emitsEachSseEventInOrder() {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("""
                data: {"event":"message","answer":"Hello","conversation_id":"conv-1","task_id":"t1"}

                data: {"event":"message","answer":" world","conversation_id":"conv-1","task_id":"t1"}

                data: {"event":"message_end","conversation_id":"conv-1","message_id":"789"}

                """));

        StepVerifier.create(service.streamChat(sampleRequest()))
            .assertNext(event -> {
                assertThat(event.getEvent()).isEqualTo("message");
                assertThat(event.getAnswer()).isEqualTo("Hello");
                assertThat(event.getConversationId()).isEqualTo("conv-1");
            })
            .assertNext(event -> {
                assertThat(event.getEvent()).isEqualTo("message");
                assertThat(event.getAnswer()).isEqualTo(" world");
            })
            .assertNext(event -> {
                assertThat(event.getEvent()).isEqualTo("message_end");
                assertThat(event.getMessageId()).isEqualTo("789");
            })
            .verifyComplete();
    }

    @Test
    void streamChat_sendsCorrectRequestToDify() throws Exception {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"event\":\"message_end\"}\n\n"));

        StepVerifier.create(service.streamChat(sampleRequest()))
            .expectNextCount(1)
            .verifyComplete();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/chat-messages");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(recorded.getHeader("Accept")).contains("text/event-stream");

        JsonNode body = objectMapper.readTree(recorded.getBody().readUtf8());
        assertThat(body.get("query").asText()).isEqualTo("Hello");
        assertThat(body.get("response_mode").asText()).isEqualTo("streaming");
        assertThat(body.get("user").asText()).isEqualTo("test-user");
    }

    @Test
    void streamChat_skipsMalformedJsonEvents() {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("""
                data: {not-valid-json}

                data: {"event":"message","answer":"ok"}

                """));

        StepVerifier.create(service.streamChat(sampleRequest()))
            .assertNext(event -> assertThat(event.getAnswer()).isEqualTo("ok"))
            .verifyComplete();
    }

    @Test
    void streamChat_propagatesDifyApiErrorWithStatusCode() {
        server.enqueue(new MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"unauthorized\",\"message\":\"Invalid API key\"}"));

        StepVerifier.create(service.streamChat(sampleRequest()))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(DifyApiException.class);
                DifyApiException ex = (DifyApiException) error;
                assertThat(ex.getStatusCode()).isEqualTo(401);
                assertThat(ex.getMessage()).contains("Invalid API key");
            })
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void streamChat_handlesServerError() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        StepVerifier.create(service.streamChat(sampleRequest()))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(DifyApiException.class);
                assertThat(((DifyApiException) error).getStatusCode()).isEqualTo(500);
            })
            .verify(Duration.ofSeconds(5));
    }
}
