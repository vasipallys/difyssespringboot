package com.example.dify.controller;

import com.example.dify.dto.ChatRequest;
import com.example.dify.dto.DifyChatRequest;
import com.example.dify.dto.DifyStreamEvent;
import com.example.dify.service.DifyClientService;
import com.example.dify.service.RateLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final DifyClientService difyClient;
    private final RateLimitService rateLimitService;

    /**
     * Main SSE streaming endpoint.
     * Proxies Dify's SSE stream to the React client with zero buffering.
     *
     * Produces: text/event-stream
     * Each event carries the original Dify event type and payload.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<DifyStreamEvent>> streamChat(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {

        if (!rateLimitService.isAllowed(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }

        String reqId = requestId != null ? requestId : UUID.randomUUID().toString();
        log.info("[{}] Streaming chat request. User: {}, Conversation: {}",
            reqId, userId, request.getConversationId());

        DifyChatRequest difyRequest = DifyChatRequest.builder()
            .query(request.getMessage())
            .conversationId(request.getConversationId())
            .user(userId)
            .inputs(request.getInputs() != null ? request.getInputs() : Map.of())
            .responseMode("streaming")
            .build();

        return difyClient.streamChat(difyRequest)
            .map(event -> ServerSentEvent.<DifyStreamEvent>builder()
                .id(event.getId() != null ? event.getId() : reqId)
                .event(event.getEvent())
                .data(event)
                .build())
            .onErrorResume(error -> {
                log.error("[{}] Stream error: {}", reqId, error.getMessage(), error);
                return Flux.just(ServerSentEvent.<DifyStreamEvent>builder()
                    .event("error")
                    .data(DifyStreamEvent.builder()
                        .event("error")
                        .error(error.getMessage())
                        .build())
                    .build());
            })
            .doOnComplete(() -> log.info("[{}] Stream completed", reqId));
    }

    /**
     * Lightweight health check endpoint (actuator /actuator/health is also available).
     */
    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("OK");
    }
}
