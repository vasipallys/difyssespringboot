package com.example.dify.controller;

import com.example.dify.dto.DifyChatRequest;
import com.example.dify.dto.DifyStreamEvent;
import com.example.dify.service.DifyClientService;
import com.example.dify.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private DifyClientService difyClient;

    @MockBean
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        when(rateLimitService.isAllowed(anyString())).thenReturn(true);
    }

    @Test
    void streamChat_proxiesDifyEventsAsSse() {
        when(difyClient.streamChat(any(DifyChatRequest.class))).thenReturn(Flux.just(
            DifyStreamEvent.builder().event("message").answer("Hello").conversationId("conv-1").build(),
            DifyStreamEvent.builder().event("message").answer(" world").conversationId("conv-1").build(),
            DifyStreamEvent.builder().event("message_end").conversationId("conv-1").build()
        ));

        String body = webTestClient.post().uri("/api/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-User-Id", "user-1")
            .bodyValue(Map.of("message", "Hi"))
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

        assertThat(body).contains("event:message");
        assertThat(body).contains("\"answer\":\"Hello\"");
        assertThat(body).contains("\"answer\":\" world\"");
        assertThat(body).contains("event:message_end");
    }

    @Test
    void streamChat_forwardsUserIdAndMessageToDify() {
        when(difyClient.streamChat(any(DifyChatRequest.class))).thenReturn(Flux.just(
            DifyStreamEvent.builder().event("message_end").build()
        ));

        webTestClient.post().uri("/api/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-User-Id", "alice")
            .bodyValue(Map.of("message", "What is SSE?", "conversationId", "conv-9"))
            .exchange()
            .expectStatus().isOk();

        ArgumentCaptor<DifyChatRequest> captor = ArgumentCaptor.forClass(DifyChatRequest.class);
        verify(difyClient).streamChat(captor.capture());
        DifyChatRequest sent = captor.getValue();
        assertThat(sent.getQuery()).isEqualTo("What is SSE?");
        assertThat(sent.getUser()).isEqualTo("alice");
        assertThat(sent.getConversationId()).isEqualTo("conv-9");
        assertThat(sent.getResponseMode()).isEqualTo("streaming");
    }

    @Test
    void streamChat_convertsUpstreamErrorToSseErrorEvent() {
        when(difyClient.streamChat(any(DifyChatRequest.class)))
            .thenReturn(Flux.error(new RuntimeException("upstream exploded")));

        String body = webTestClient.post().uri("/api/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("message", "Hi"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

        assertThat(body).contains("event:error");
        assertThat(body).contains("upstream exploded");
    }

    @Test
    void streamChat_rejectsBlankMessageWith400() {
        webTestClient.post().uri("/api/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("message", "  "))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.error").isEqualTo("Validation Error");
    }

    @Test
    void streamChat_returns429WhenRateLimited() {
        when(rateLimitService.isAllowed("spammer")).thenReturn(false);

        webTestClient.post().uri("/api/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-User-Id", "spammer")
            .bodyValue(Map.of("message", "Hi"))
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectBody()
            .jsonPath("$.message").isEqualTo("Rate limit exceeded");
    }

    @Test
    void health_returnsOk() {
        webTestClient.get().uri("/api/chat/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("OK");
    }
}
