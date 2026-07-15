package com.example.dify.service;

import com.example.dify.dto.DifyChatRequest;
import com.example.dify.dto.DifyStreamEvent;
import com.example.dify.exception.DifyApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class DifyClientService {

    private final WebClient difyWebClient;
    private final ObjectMapper objectMapper;

    /**
     * Streams chat messages from Dify AI.
     * Returns a Flux of DifyStreamEvent that emits each SSE event as it arrives.
     * No buffering - events flow through as soon as Dify sends them.
     */
    public Flux<DifyStreamEvent> streamChat(DifyChatRequest request) {
        log.debug("Streaming chat to Dify. User: {}", request.getUser());

        return difyWebClient.post()
            .uri("/chat-messages")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(request)
            .retrieve()
            .onStatus(
                HttpStatusCode::isError,
                response -> response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(errorBody -> new DifyApiException(
                        "Dify API error: " + response.statusCode() + " - " + errorBody,
                        response.statusCode().value()
                    ))
            )
            // WebFlux decodes text/event-stream and emits each event's data payload.
            .bodyToFlux(String.class)
            .map(this::stripSsePrefix)
            .filter(json -> !json.isEmpty())
            .concatMap(this::parseEvent)
            .doOnNext(event -> log.debug("Received Dify event: {}", event.getEvent()))
            .doOnError(error -> log.error("Error in Dify stream", error))
            .doOnComplete(() -> log.debug("Dify stream completed"));
    }

    // Tolerate raw "data:" prefixes in case an intermediary delivers unframed lines.
    private String stripSsePrefix(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("data:") ? trimmed.substring(5).trim() : trimmed;
    }

    private Mono<DifyStreamEvent> parseEvent(String json) {
        try {
            return Mono.just(objectMapper.readValue(json, DifyStreamEvent.class));
        } catch (Exception e) {
            log.warn("Skipping unparseable Dify event: {}", json, e);
            return Mono.empty();
        }
    }
}
