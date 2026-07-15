# Recreate Guide — Dify SSE Chat Monorepo

> **Purpose:** This document is a complete, self-contained blueprint for recreating the
> entire working codebase (React + Spring Boot WebFlux + Dify AI, SSE end-to-end).
> Part 1 covers setup steps, commands, and hard-won gotchas. Part 2 contains the exact
> content of every source file. Following this document from top to bottom reproduces
> a repository where **all 19 backend tests and 23 frontend tests pass** and both
> production builds succeed.

**Stack:** React 18 + TypeScript + Vite | Spring Boot 3.3.x (WebFlux, Java 17) | Dify AI | Docker

---

# Part 1: Setup Steps

## 1.1 Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17 (a **recent patch release**, e.g. Temurin 17.0.13+) | See gotcha G5 about old 17.0.0 builds |
| Node.js | 18+ (tested with 22) | npm 9+ |
| Maven | none needed | the Maven Wrapper is committed (downloads Maven 3.9.9) |
| Docker | optional | only for containerized deployment |

## 1.2 Directory layout

```
dify-sse-app/
├── backend/
│   ├── .mvn/wrapper/maven-wrapper.properties
│   ├── mvnw, mvnw.cmd                  # Maven wrapper scripts (see 1.3)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/example/dify/
│       │   ├── DifySseApplication.java
│       │   ├── config/   (WebClientConfig, CorsConfig, GlobalExceptionHandler)
│       │   ├── controller/ChatController.java
│       │   ├── dto/      (ChatRequest, DifyChatRequest, DifyStreamEvent)
│       │   ├── exception/DifyApiException.java
│       │   └── service/  (DifyClientService, RateLimitService)
│       ├── main/resources/application.yml
│       └── test/java/com/example/dify/   (5 test classes, 19 tests)
├── frontend/
│   ├── package.json, vite.config.ts, tsconfig.json, index.html
│   ├── .env.example, nginx.conf, Dockerfile
│   └── src/
│       ├── main.tsx, App.tsx, App.css, setupTests.ts
│       ├── types/dify.ts
│       ├── lib/sse.ts            (+ sse.test.ts)
│       ├── hooks/useDifyStream.ts (+ useDifyStream.test.ts)
│       └── components/ChatInterface.tsx/.css (+ ChatInterface.test.tsx)
├── nginx/nginx.conf              # production reverse proxy (SSE-safe)
├── docker-compose.yml
├── .env.example
├── .gitignore
└── README.md
```

## 1.3 Maven Wrapper installation

The wrapper is the **script-only** variant (no jar). Download the two scripts from the
official Apache maven-wrapper 3.3.2 tag into `backend/`:

```bash
cd backend
curl -fsSL -o mvnw     https://raw.githubusercontent.com/apache/maven-wrapper/maven-wrapper-3.3.2/maven-wrapper-distribution/src/resources/only-mvnw
curl -fsSL -o mvnw.cmd https://raw.githubusercontent.com/apache/maven-wrapper/maven-wrapper-3.3.2/maven-wrapper-distribution/src/resources/only-mvnw.cmd
```

Then create `backend/.mvn/wrapper/maven-wrapper.properties` (content in Part 2).

**Required patch (gotcha G4):** in `mvnw.cmd`, the line that launches Maven does not quote
the resolved path and breaks when the Windows user profile contains spaces. Change:

```bat
@IF NOT "%__MVNW_CMD__%"=="" (%__MVNW_CMD__% %*)
```

to:

```bat
@IF NOT "%__MVNW_CMD__%"=="" ("%__MVNW_CMD__%" %*)
```

## 1.4 Build & test commands

```bash
# Backend (19 tests: unit + @WebFluxTest slice + SSE integration via okhttp MockWebServer)
cd backend
./mvnw test

# Frontend (23 tests: SSE parser, streaming hook, component)
cd frontend
npm install
npm test
npm run build        # tsc --noEmit && vite build
```

Run locally:

```bash
# Backend  (set DIFY_API_KEY / DIFY_BASE_URL env vars first)
cd backend && ./mvnw spring-boot:run     # -> http://localhost:8080

# Frontend
cd frontend && npm run dev               # -> http://localhost:3000
```

Docker:

```bash
cp .env.example .env    # fill in DIFY_API_KEY
docker compose up --build
# production profile (nginx reverse proxy on :80, SSE buffering disabled):
VITE_API_URL=/api docker compose --profile production up --build
```

Smoke-test the SSE endpoint:

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "X-User-Id: test-user" \
  -d '{"message": "Hello", "inputs": {}}'
```

## 1.5 Dify configuration

1. Dify Studio → create a **Chatflow** (or Workflow) app.
2. **API Access → API Keys** → generate a key (`app-...`).
3. Base URL: `https://api.dify.ai/v1` (cloud) or `http://your-host/v1` (self-hosted).
4. Multi-turn: first request omits `conversation_id`; Dify returns one inside `message`
   events; the frontend stores and resends it automatically.

---

# Gotchas (deviations from naive implementations — all baked into Part 2)

**G1 — WebFlux already decodes SSE frames.** `WebClient ... .bodyToFlux(String.class)`
on a `text/event-stream` response emits each event's **data payload only** — the
`data:` prefix is already stripped by the codec. Code that filters for lines starting
with `data:` (as many blog posts show) drops *every* event. `DifyClientService`
therefore parses the emitted strings as JSON directly (with a tolerant strip of a
`data:` prefix in case an intermediary delivers unframed lines).

**G2 — No `server.servlet.context-path` in WebFlux.** That property is Servlet-stack
only and is silently ignored by WebFlux. The `/api` prefix lives in the controller
mapping (`@RequestMapping("/api/chat")`), which also keeps `@WebFluxTest` slice tests
working without extra base-path configuration.

**G3 — Validation exception type.** In WebFlux, `@Valid` body failures throw
`WebExchangeBindException` (not `MethodArgumentNotValidException`, and not the
non-existent `WebExchangeBindingException` some documents mention). The
`GlobalExceptionHandler` also handles `ResponseStatusException` explicitly so a 429
from rate limiting is not swallowed by the generic 500 handler.

**G4 — mvnw.cmd path-with-spaces bug.** See 1.3. Without the quoting patch the wrapper
fails with `'C:\Users\Xyz' is not recognized ...` for any user profile containing spaces.

**G5 — Windows AF_UNIX / NIO selector failures.** Netty/NIO `Selector.open()` creates a
wakeup pipe using AF_UNIX sockets in the temp directory (JDK 17+ on Windows). On
machines where security software blocks AF_UNIX connects in the user temp dir, every
WebClient test fails with `IOException: Unable to establish loopback connection` /
`SocketException: Invalid argument: connect`. Fix for local runs: point the socket dir
somewhere AF_UNIX works, e.g.
`./mvnw test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"`, and use a recent JDK 17
patch release. (Do **not** hardcode this in the pom — the path is machine-specific and
would break Linux/Docker builds.)

**G6 — `message_id` is a String.** Dify's `message_id` is modeled as `String` in the DTO
(Jackson coerces numeric JSON to String), because Dify emits UUID-style ids; an
`Integer` field would explode on real traffic.

**G7 — POST-based SSE on the client.** `EventSource` supports only GET. The frontend
uses `fetch` + `ReadableStream` + a small stateful SSE frame parser (`src/lib/sse.ts`)
that buffers partial frames across chunk boundaries, handles CRLF, multi-line `data:`
fields, comments, and `id:`/`event:` fields.

**G8 — CRA is deprecated; use Vite.** The frontend is Vite + React 18 + TypeScript with
Vitest (env var `VITE_API_URL`, not `REACT_APP_API_URL`). jsdom lacks
`scrollIntoView`, so `setupTests.ts` stubs it — without the stub every component test
crashes in the auto-scroll effect.

**G9 — Proxy buffering kills SSE.** Any nginx in front of the backend needs
`proxy_buffering off; proxy_cache off; proxy_http_version 1.1;` and read/send timeouts
≥ the backend's 120s (see `nginx/nginx.conf`).


---

# Part 2: Complete File Contents

Create each file below with exactly this content.

## `.gitignore`

```
# ---- Backend (Java / Maven) ----
backend/target/
backend/.mvn/wrapper/maven-wrapper.jar
*.class
*.jar
!backend/.mvn/wrapper/maven-wrapper.jar

# ---- Frontend (Node / Vite) ----
frontend/node_modules/
frontend/dist/
frontend/build/
frontend/coverage/
frontend/.env.local
frontend/.env.development.local
frontend/.env.test.local
frontend/.env.production.local

# ---- IDE ----
.idea/
*.iml
.vscode/
*.swp
*.swo

# ---- OS ----
.DS_Store
Thumbs.db

# ---- Environment / secrets ----
.env
!.env.example
!frontend/.env.example

# ---- Logs ----
*.log
logs/
```

## `.env.example`

```bash
# Copy to .env and fill in real values. Never commit .env.
DIFY_BASE_URL=https://api.dify.ai/v1
DIFY_API_KEY=your-dify-api-key-here
CORS_ORIGINS=http://localhost:3000

# Build-time API base URL for the frontend image
# (use /api when serving through the nginx reverse proxy profile)
VITE_API_URL=http://localhost:8080/api
```

## `docker-compose.yml`

```yaml
services:
  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      - DIFY_BASE_URL=${DIFY_BASE_URL:-https://api.dify.ai/v1}
      - DIFY_API_KEY=${DIFY_API_KEY}
      - CORS_ORIGINS=${CORS_ORIGINS:-http://localhost:3000}
    networks:
      - dify-network
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 20s

  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
      args:
        # Same-origin path when served behind the nginx reverse proxy;
        # use http://localhost:8080/api when hitting the backend directly.
        VITE_API_URL: ${VITE_API_URL:-http://localhost:8080/api}
    ports:
      - "3000:80"
    depends_on:
      - backend
    networks:
      - dify-network
    restart: unless-stopped

  # Optional: Nginx reverse proxy for production (profile: production)
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/conf.d/default.conf:ro
    depends_on:
      - backend
      - frontend
    networks:
      - dify-network
    restart: unless-stopped
    profiles:
      - production

networks:
  dify-network:
    driver: bridge
```

## `nginx/nginx.conf`

```nginx
upstream backend {
    server backend:8080;
}

upstream frontend {
    server frontend:80;
}

server {
    listen 80;
    server_name your-domain.com;

    # Frontend
    location / {
        proxy_pass http://frontend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # API with SSE support - CRITICAL CONFIGURATION
    location /api/ {
        proxy_pass http://backend;
        proxy_http_version 1.1;

        # SSE-specific headers
        proxy_set_header Connection '';
        proxy_set_header Cache-Control 'no-cache';

        # Disable buffering - ESSENTIAL for SSE
        proxy_buffering off;
        proxy_cache off;

        # Timeouts matching backend
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;

        # Headers
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## `backend/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>dify-sse-backend</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    <name>dify-sse-backend</name>
    <description>Spring Boot WebFlux SSE proxy for Dify AI</description>

    <properties>
        <java.version>17</java.version>
        <mockwebserver.version>4.12.0</mockwebserver.version>
    </properties>

    <dependencies>
        <!-- WebFlux for reactive SSE proxying -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Actuator for production health/metrics -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>mockwebserver</artifactId>
            <version>${mockwebserver.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## `backend/.mvn/wrapper/maven-wrapper.properties`

```properties
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
wrapperVersion=3.3.2
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
```

## `backend/src/main/resources/application.yml`

```yaml
server:
  port: 8080
  netty:
    connection-timeout: 10s

# Dify AI Configuration
dify:
  base-url: ${DIFY_BASE_URL:https://api.dify.ai/v1}
  api-key: ${DIFY_API_KEY:your-api-key-here}
  timeout-seconds: ${DIFY_TIMEOUT_SECONDS:120}

# CORS Configuration
cors:
  allowed-origins: ${CORS_ORIGINS:http://localhost:3000}

# Rate limiting (per user, sliding window)
rate-limit:
  max-requests: ${RATE_LIMIT_MAX_REQUESTS:10}
  window-seconds: ${RATE_LIMIT_WINDOW_SECONDS:60}

# Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true

# Logging
logging:
  level:
    com.example.dify: INFO
    reactor.netty: INFO
```

## `backend/src/main/java/com/example/dify/DifySseApplication.java`

```java
package com.example.dify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DifySseApplication {

    public static void main(String[] args) {
        SpringApplication.run(DifySseApplication.class, args);
    }
}
```

## `backend/src/main/java/com/example/dify/dto/ChatRequest.java`

```java
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
```

## `backend/src/main/java/com/example/dify/dto/DifyChatRequest.java`

```java
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
```

## `backend/src/main/java/com/example/dify/dto/DifyStreamEvent.java`

```java
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
```

## `backend/src/main/java/com/example/dify/exception/DifyApiException.java`

```java
package com.example.dify.exception;

import lombok.Getter;

/**
 * Raised when the Dify API responds with a non-2xx status.
 */
@Getter
public class DifyApiException extends RuntimeException {

    private final int statusCode;

    public DifyApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
}
```

## `backend/src/main/java/com/example/dify/config/WebClientConfig.java`

```java
package com.example.dify.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Value("${dify.base-url}")
    private String difyBaseUrl;

    @Value("${dify.api-key}")
    private String difyApiKey;

    @Value("${dify.timeout-seconds:120}")
    private int timeoutSeconds;

    @Bean
    public WebClient difyWebClient() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .responseTimeout(Duration.ofSeconds(timeoutSeconds))
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS)));

        return WebClient.builder()
            .baseUrl(difyBaseUrl)
            .defaultHeader("Authorization", "Bearer " + difyApiKey)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
```

## `backend/src/main/java/com/example/dify/config/CorsConfig.java`

```java
package com.example.dify.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setExposedHeaders(List.of("X-Request-Id", "X-Conversation-Id"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}
```

## `backend/src/main/java/com/example/dify/config/GlobalExceptionHandler.java`

```java
package com.example.dify.config;

import com.example.dify.exception.DifyApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(DifyApiException.class)
    public ResponseEntity<Map<String, Object>> handleDifyApiException(DifyApiException e) {
        log.error("Dify API error: {}", e.getMessage());
        return ResponseEntity.status(e.getStatusCode())
            .body(Map.of(
                "error", "Dify API Error",
                "message", e.getMessage(),
                "timestamp", Instant.now().toString()
            ));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(WebExchangeBindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                "error", "Validation Error",
                "message", message,
                "timestamp", Instant.now().toString()
            ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
            .body(Map.of(
                "error", e.getStatusCode().toString(),
                "message", e.getReason() != null ? e.getReason() : "Request failed",
                "timestamp", Instant.now().toString()
            ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of(
                "error", "Internal Server Error",
                "message", "An unexpected error occurred",
                "timestamp", Instant.now().toString()
            ));
    }
}
```

## `backend/src/main/java/com/example/dify/service/DifyClientService.java`

```java
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
```

## `backend/src/main/java/com/example/dify/service/RateLimitService.java`

```java
package com.example.dify.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory fixed-window rate limiter, keyed by user id.
 * For multi-instance deployments swap this for a shared store (e.g. Redis).
 */
@Service
public class RateLimitService {

    private final int maxRequests;
    private final Duration window;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitService(
            @Value("${rate-limit.max-requests:10}") int maxRequests,
            @Value("${rate-limit.window-seconds:60}") long windowSeconds) {
        this.maxRequests = maxRequests;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    public boolean isAllowed(String userId) {
        long now = System.currentTimeMillis();
        Window w = windows.compute(userId, (key, existing) -> {
            if (existing == null || now - existing.startMillis >= window.toMillis()) {
                return new Window(now);
            }
            return existing;
        });
        return w.count.incrementAndGet() <= maxRequests;
    }

    private static final class Window {
        private final long startMillis;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
```

## `backend/src/main/java/com/example/dify/controller/ChatController.java`

```java
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
```

## `backend/src/test/java/com/example/dify/DifySseApplicationTests.java`

```java
package com.example.dify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DifySseApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the full application context (WebClient, CORS, controller, services) wires up.
    }
}
```

## `backend/src/test/java/com/example/dify/dto/DifyStreamEventTest.java`

```java
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
```

## `backend/src/test/java/com/example/dify/service/DifyClientServiceTest.java`

```java
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
```

## `backend/src/test/java/com/example/dify/service/RateLimitServiceTest.java`

```java
package com.example.dify.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {

    @Test
    void allowsRequestsUpToTheLimit() {
        RateLimitService service = new RateLimitService(3, 60);

        assertThat(service.isAllowed("u1")).isTrue();
        assertThat(service.isAllowed("u1")).isTrue();
        assertThat(service.isAllowed("u1")).isTrue();
        assertThat(service.isAllowed("u1")).isFalse();
    }

    @Test
    void limitsAreTrackedPerUser() {
        RateLimitService service = new RateLimitService(1, 60);

        assertThat(service.isAllowed("u1")).isTrue();
        assertThat(service.isAllowed("u2")).isTrue();
        assertThat(service.isAllowed("u1")).isFalse();
        assertThat(service.isAllowed("u2")).isFalse();
    }

    @Test
    void windowResetsAfterExpiry() throws InterruptedException {
        RateLimitService service = new RateLimitService(1, 1);

        assertThat(service.isAllowed("u1")).isTrue();
        assertThat(service.isAllowed("u1")).isFalse();

        Thread.sleep(1100);
        assertThat(service.isAllowed("u1")).isTrue();
    }
}
```

## `backend/src/test/java/com/example/dify/controller/ChatControllerTest.java`

```java
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
```

## `backend/Dockerfile`

```dockerfile
# backend/Dockerfile
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app
COPY .mvn .mvn
COPY mvnw mvnw.cmd pom.xml ./
# Pre-fetch dependencies for better layer caching
RUN ./mvnw -q dependency:go-offline || true

COPY src ./src
RUN ./mvnw -q clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

# Provide real values at runtime (docker-compose / K8s secrets); never bake keys in.
ENV DIFY_BASE_URL=https://api.dify.ai/v1 \
    CORS_ORIGINS=http://localhost:3000

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

## `frontend/package.json`

```json
{
  "name": "dify-sse-frontend",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^6.5.0",
    "@testing-library/react": "^16.0.1",
    "@testing-library/user-event": "^14.5.2",
    "@types/react": "^18.3.10",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.2",
    "jsdom": "^25.0.1",
    "typescript": "~5.6.2",
    "vite": "^5.4.8",
    "vitest": "^2.1.2"
  }
}
```

## `frontend/vite.config.ts`

```typescript
/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/setupTests.ts',
    css: false,
  },
});
```

## `frontend/tsconfig.json`

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "types": ["vite/client", "vitest/globals", "@testing-library/jest-dom"]
  },
  "include": ["src", "vite.config.ts"]
}
```

## `frontend/index.html`

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Dify AI Chat</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

## `frontend/.env.example`

```bash
# Base URL of the Spring Boot backend (no trailing slash)
VITE_API_URL=http://localhost:8080/api
```

## `frontend/src/main.tsx`

```tsx
// src/main.tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
```

## `frontend/src/App.tsx`

```tsx
// src/App.tsx
import { ChatInterface } from './components/ChatInterface';
import './App.css';

function App() {
  return (
    <div className="App">
      <ChatInterface />
    </div>
  );
}

export default App;
```

## `frontend/src/App.css`

```css
/* src/App.css */

* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

body {
  background: #e8e8e8;
}

.App {
  min-height: 100vh;
  display: flex;
  justify-content: center;
}
```

## `frontend/src/setupTests.ts`

```typescript
import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';

// jsdom does not implement scrollIntoView (used for auto-scrolling the chat)
Element.prototype.scrollIntoView = vi.fn();
```

## `frontend/src/types/dify.ts`

```typescript
// src/types/dify.ts

export interface DifyStreamEvent {
  event: string;
  task_id?: string;
  id?: string;
  message_id?: string;
  conversation_id?: string;
  answer?: string;
  created_at?: number;
  error?: string;
  data?: Record<string, unknown>;
  tool_call_id?: string;
  tool_call?: Record<string, unknown>;
  tool_response?: Record<string, unknown>;
  workflow_run_id?: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  isStreaming?: boolean;
  error?: string;
  timestamp: Date;
}

export interface ChatRequest {
  message: string;
  conversationId?: string;
  inputs?: Record<string, unknown>;
}
```

## `frontend/src/lib/sse.ts`

```typescript
// src/lib/sse.ts
// Minimal, spec-compliant-enough SSE frame parser for fetch + ReadableStream.
// EventSource only supports GET, so POST-based SSE needs manual parsing.

export interface SseFrame {
  event?: string;
  data: string;
  id?: string;
}

/**
 * Creates a stateful parser. Feed it decoded text chunks (in arrival order);
 * it returns the complete SSE frames found so far and buffers partial frames
 * across chunk boundaries.
 */
export function createSseParser(): (chunk: string) => SseFrame[] {
  let buffer = '';

  return function feed(chunk: string): SseFrame[] {
    buffer += chunk;
    // Normalize CRLF; a trailing lone \r is kept until its \n arrives.
    buffer = buffer.replace(/\r\n/g, '\n');

    const frames: SseFrame[] = [];
    let separatorIndex: number;

    while ((separatorIndex = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);

      const frame = parseBlock(block);
      if (frame) {
        frames.push(frame);
      }
    }

    return frames;
  };
}

function parseBlock(block: string): SseFrame | null {
  let event: string | undefined;
  let id: string | undefined;
  const dataLines: string[] = [];

  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) continue; // empty line or comment

    const colonIndex = line.indexOf(':');
    const field = colonIndex === -1 ? line : line.slice(0, colonIndex);
    let value = colonIndex === -1 ? '' : line.slice(colonIndex + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    switch (field) {
      case 'event':
        event = value;
        break;
      case 'data':
        dataLines.push(value);
        break;
      case 'id':
        id = value;
        break;
      default:
        // Ignore unknown fields (e.g. retry)
        break;
    }
  }

  if (dataLines.length === 0 && !event) return null;
  return { event, data: dataLines.join('\n'), id };
}
```

## `frontend/src/hooks/useDifyStream.ts`

```typescript
// src/hooks/useDifyStream.ts
import { useCallback, useRef, useState } from 'react';
import { ChatMessage, DifyStreamEvent } from '../types/dify';
import { createSseParser } from '../lib/sse';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export interface UseDifyStreamReturn {
  messages: ChatMessage[];
  isStreaming: boolean;
  error: string | null;
  sendMessage: (message: string, inputs?: Record<string, unknown>) => Promise<void>;
  stopStream: () => void;
  conversationId: string | null;
  clearMessages: () => void;
}

const generateId = () => Math.random().toString(36).substring(2, 15);

export const useDifyStream = (): UseDifyStreamReturn => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conversationId, setConversationId] = useState<string | null>(null);

  const abortControllerRef = useRef<AbortController | null>(null);

  const stopStream = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    setIsStreaming(false);
  }, []);

  const handleEvent = useCallback(
    (eventType: string, data: DifyStreamEvent, assistantId: string) => {
      switch (eventType) {
        case 'message': {
          // Token chunk from LLM
          if (data.answer) {
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? { ...msg, content: msg.content + data.answer }
                  : msg
              )
            );
          }
          if (data.conversation_id) {
            setConversationId(data.conversation_id);
          }
          break;
        }

        case 'agent_message': {
          // Agent reasoning step - could be surfaced as a thought process
          if (data.answer) {
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? { ...msg, content: msg.content + data.answer }
                  : msg
              )
            );
          }
          break;
        }

        case 'workflow_finished':
        case 'message_end': {
          if (data.conversation_id) {
            setConversationId(data.conversation_id);
          }
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId ? { ...msg, isStreaming: false } : msg
            )
          );
          break;
        }

        case 'error': {
          const message = data.error || 'Unknown error from Dify';
          setError(message);
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? { ...msg, isStreaming: false, error: message }
                : msg
            )
          );
          break;
        }

        case 'ping': {
          // Keep-alive, ignore
          break;
        }

        default: {
          // tool_call, tool_response, node_started, node_finished, workflow_started...
          // Not rendered in this UI; useful for debugging.
          console.debug('[SSE] Unhandled event type:', eventType, data);
        }
      }
    },
    []
  );

  const sendMessage = useCallback(
    async (message: string, inputs?: Record<string, unknown>) => {
      // Stop any existing stream
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
      setError(null);
      setIsStreaming(true);

      const userMessage: ChatMessage = {
        id: generateId(),
        role: 'user',
        content: message,
        timestamp: new Date(),
      };

      const assistantId = generateId();
      const assistantMessage: ChatMessage = {
        id: assistantId,
        role: 'assistant',
        content: '',
        isStreaming: true,
        timestamp: new Date(),
      };

      setMessages((prev) => [...prev, userMessage, assistantMessage]);

      const controller = new AbortController();
      abortControllerRef.current = controller;

      try {
        const response = await fetch(`${API_BASE}/chat/stream`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Request-Id': generateId(),
          },
          body: JSON.stringify({
            message,
            inputs: inputs || {},
            conversationId: conversationId || undefined,
          }),
          signal: controller.signal,
        });

        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`HTTP ${response.status}: ${errorText}`);
        }

        if (!response.body) {
          throw new Error('No response body received');
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        const parse = createSseParser();

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          for (const frame of parse(decoder.decode(value, { stream: true }))) {
            if (!frame.data) continue;
            try {
              const eventData: DifyStreamEvent = JSON.parse(frame.data);
              handleEvent(frame.event || eventData.event, eventData, assistantId);
            } catch (e) {
              console.error('Failed to parse SSE data:', frame.data, e);
            }
          }
        }

        // Mark streaming as complete
        setMessages((prev) =>
          prev.map((msg) =>
            msg.id === assistantId ? { ...msg, isStreaming: false } : msg
          )
        );
      } catch (err) {
        const e = err as Error;
        if (e.name === 'AbortError') {
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? { ...msg, isStreaming: false, content: msg.content + '\n[Stopped]' }
                : msg
            )
          );
        } else {
          console.error('Stream error:', e);
          setError(e.message);
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? { ...msg, isStreaming: false, error: e.message }
                : msg
            )
          );
        }
      } finally {
        setIsStreaming(false);
        abortControllerRef.current = null;
      }
    },
    [conversationId, handleEvent]
  );

  const clearMessages = useCallback(() => {
    setMessages([]);
    setConversationId(null);
    setError(null);
  }, []);

  return {
    messages,
    isStreaming,
    error,
    sendMessage,
    stopStream,
    conversationId,
    clearMessages,
  };
};
```

## `frontend/src/components/ChatInterface.tsx`

```tsx
// src/components/ChatInterface.tsx
import React, { useEffect, useRef, useState } from 'react';
import { useDifyStream } from '../hooks/useDifyStream';
import './ChatInterface.css';

export const ChatInterface: React.FC = () => {
  const [input, setInput] = useState('');
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const {
    messages,
    isStreaming,
    error,
    sendMessage,
    stopStream,
    conversationId,
    clearMessages,
  } = useDifyStream();

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || isStreaming) return;
    void sendMessage(input.trim());
    setInput('');
    // Reset textarea height
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const handleTextareaInput = (e: React.FormEvent<HTMLTextAreaElement>) => {
    const target = e.currentTarget;
    target.style.height = 'auto';
    target.style.height = Math.min(target.scrollHeight, 200) + 'px';
  };

  // Auto-scroll to bottom
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <div className="chat-container">
      <header className="chat-header">
        <h1>Dify AI Chat</h1>
        <div className="header-actions">
          {conversationId && (
            <span className="conversation-id" title={conversationId}>
              Session: {conversationId.slice(0, 8)}...
            </span>
          )}
          <button className="clear-btn" onClick={clearMessages}>
            Clear
          </button>
        </div>
      </header>

      <div className="messages-container">
        {messages.length === 0 && (
          <div className="empty-state">
            <p>Start a conversation with Dify AI</p>
            <p className="hint">Type a message below and press Enter</p>
          </div>
        )}

        {messages.map((msg) => (
          <div
            key={msg.id}
            className={`message ${msg.role} ${msg.isStreaming ? 'streaming' : ''} ${
              msg.error ? 'error' : ''
            }`}
          >
            <div className="message-avatar">
              {msg.role === 'user' ? '👤' : '🤖'}
            </div>
            <div className="message-content">
              <div className="message-text">
                {msg.content ||
                  (msg.isStreaming ? <span className="typing">Thinking...</span> : '')}
                {msg.isStreaming && <span className="cursor">▌</span>}
              </div>
              {msg.error && <div className="message-error">Error: {msg.error}</div>}
              <div className="message-meta">{msg.timestamp.toLocaleTimeString()}</div>
            </div>
          </div>
        ))}

        {error && !messages.some((m) => m.error) && (
          <div className="global-error">
            <span>⚠️ {error}</span>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      <form className="input-area" onSubmit={handleSubmit}>
        <textarea
          ref={textareaRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          onInput={handleTextareaInput}
          placeholder="Type your message... (Shift+Enter for new line)"
          rows={1}
          disabled={isStreaming}
        />
        {isStreaming ? (
          <button type="button" className="stop-btn" onClick={stopStream}>
            ⏹ Stop
          </button>
        ) : (
          <button type="submit" className="send-btn" disabled={!input.trim()}>
            ➤ Send
          </button>
        )}
      </form>
    </div>
  );
};
```

## `frontend/src/components/ChatInterface.css`

```css
/* src/components/ChatInterface.css */

.chat-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  max-width: 900px;
  margin: 0 auto;
  background: #f5f5f5;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  background: #fff;
  border-bottom: 1px solid #e0e0e0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.chat-header h1 {
  margin: 0;
  font-size: 1.25rem;
  color: #1a1a1a;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.conversation-id {
  font-size: 0.75rem;
  color: #666;
  background: #f0f0f0;
  padding: 4px 8px;
  border-radius: 4px;
}

.clear-btn {
  padding: 6px 12px;
  border: 1px solid #ddd;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.875rem;
}

.clear-btn:hover {
  background: #f5f5f5;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.empty-state {
  text-align: center;
  color: #999;
  margin-top: 40px;
}

.empty-state .hint {
  font-size: 0.875rem;
  color: #bbb;
}

.message {
  display: flex;
  gap: 12px;
  max-width: 85%;
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.message.assistant {
  align-self: flex-start;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border: 1px solid #e0e0e0;
  font-size: 1.25rem;
  flex-shrink: 0;
}

.message-content {
  background: #fff;
  padding: 12px 16px;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
  max-width: 100%;
  word-wrap: break-word;
}

.message.user .message-content {
  background: #007bff;
  color: #fff;
}

.message-text {
  line-height: 1.5;
  white-space: pre-wrap;
}

.message-text .typing {
  color: #999;
  font-style: italic;
}

.cursor {
  display: inline-block;
  animation: blink 1s step-end infinite;
  color: #007bff;
  margin-left: 2px;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.message-meta {
  font-size: 0.7rem;
  color: #999;
  margin-top: 6px;
  text-align: right;
}

.message.user .message-meta {
  color: rgba(255, 255, 255, 0.7);
}

.message.error .message-content {
  border: 1px solid #ff4444;
  background: #fff5f5;
}

.message-error {
  color: #ff4444;
  font-size: 0.875rem;
  margin-top: 8px;
  padding: 8px;
  background: #ffeeee;
  border-radius: 6px;
}

.global-error {
  align-self: center;
  background: #ff4444;
  color: #fff;
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 0.875rem;
}

.input-area {
  display: flex;
  gap: 12px;
  padding: 16px 24px;
  background: #fff;
  border-top: 1px solid #e0e0e0;
  align-items: flex-end;
}

.input-area textarea {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #ddd;
  border-radius: 12px;
  resize: none;
  font-family: inherit;
  font-size: 0.95rem;
  line-height: 1.5;
  min-height: 24px;
  max-height: 200px;
  outline: none;
  transition: border-color 0.2s;
}

.input-area textarea:focus {
  border-color: #007bff;
}

.input-area textarea:disabled {
  background: #f5f5f5;
  cursor: not-allowed;
}

.send-btn,
.stop-btn {
  padding: 12px 20px;
  border: none;
  border-radius: 12px;
  font-size: 0.95rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.send-btn {
  background: #007bff;
  color: #fff;
}

.send-btn:hover:not(:disabled) {
  background: #0056b3;
}

.send-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.stop-btn {
  background: #ff4444;
  color: #fff;
}

.stop-btn:hover {
  background: #cc0000;
}

/* Scrollbar styling */
.messages-container::-webkit-scrollbar {
  width: 8px;
}

.messages-container::-webkit-scrollbar-track {
  background: transparent;
}

.messages-container::-webkit-scrollbar-thumb {
  background: #ccc;
  border-radius: 4px;
}

.messages-container::-webkit-scrollbar-thumb:hover {
  background: #aaa;
}
```

## `frontend/src/lib/sse.test.ts`

```typescript
// src/lib/sse.test.ts
import { describe, expect, it } from 'vitest';
import { createSseParser } from './sse';

describe('createSseParser', () => {
  it('parses a single complete frame', () => {
    const parse = createSseParser();
    const frames = parse('event: message\ndata: {"answer":"hi"}\n\n');

    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe('message');
    expect(frames[0].data).toBe('{"answer":"hi"}');
  });

  it('parses multiple frames in one chunk', () => {
    const parse = createSseParser();
    const frames = parse(
      'event: message\ndata: {"a":1}\n\nevent: message_end\ndata: {"b":2}\n\n'
    );

    expect(frames).toHaveLength(2);
    expect(frames[0].event).toBe('message');
    expect(frames[1].event).toBe('message_end');
  });

  it('buffers partial frames across chunk boundaries', () => {
    const parse = createSseParser();

    expect(parse('event: mess')).toHaveLength(0);
    expect(parse('age\ndata: {"answer":')).toHaveLength(0);

    const frames = parse('"hello"}\n\n');
    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe('message');
    expect(frames[0].data).toBe('{"answer":"hello"}');
  });

  it('handles CRLF line endings', () => {
    const parse = createSseParser();
    const frames = parse('event: message\r\ndata: {"x":1}\r\n\r\n');

    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe('message');
    expect(frames[0].data).toBe('{"x":1}');
  });

  it('joins multi-line data fields with newlines', () => {
    const parse = createSseParser();
    const frames = parse('data: line1\ndata: line2\n\n');

    expect(frames).toHaveLength(1);
    expect(frames[0].data).toBe('line1\nline2');
  });

  it('captures the id field', () => {
    const parse = createSseParser();
    const frames = parse('id: 42\nevent: message\ndata: {}\n\n');

    expect(frames[0].id).toBe('42');
  });

  it('ignores comment lines', () => {
    const parse = createSseParser();
    const frames = parse(': keep-alive\n\ndata: {"real":true}\n\n');

    expect(frames).toHaveLength(1);
    expect(frames[0].data).toBe('{"real":true}');
  });

  it('returns nothing for incomplete input', () => {
    const parse = createSseParser();
    expect(parse('data: {"unfinished":true}\n')).toHaveLength(0);
  });
});
```

## `frontend/src/hooks/useDifyStream.test.ts`

```typescript
// src/hooks/useDifyStream.test.ts
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useDifyStream } from './useDifyStream';

function sseResponse(sseBody: string, status = 200): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      // Emit in small chunks to exercise cross-chunk buffering
      for (let i = 0; i < sseBody.length; i += 17) {
        controller.enqueue(encoder.encode(sseBody.slice(i, i + 17)));
      }
      controller.close();
    },
  });
  return new Response(stream, {
    status,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

describe('useDifyStream', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fetchMock.mockReset();
  });

  it('accumulates streamed tokens into the assistant message', async () => {
    fetchMock.mockResolvedValue(
      sseResponse(
        'event: message\ndata: {"event":"message","answer":"Hello","conversation_id":"conv-1"}\n\n' +
          'event: message\ndata: {"event":"message","answer":" world"}\n\n' +
          'event: message_end\ndata: {"event":"message_end","conversation_id":"conv-1"}\n\n'
      )
    );

    const { result } = renderHook(() => useDifyStream());

    await act(async () => {
      await result.current.sendMessage('Hi there');
    });

    expect(result.current.messages).toHaveLength(2);
    expect(result.current.messages[0].role).toBe('user');
    expect(result.current.messages[0].content).toBe('Hi there');
    expect(result.current.messages[1].role).toBe('assistant');
    expect(result.current.messages[1].content).toBe('Hello world');
    expect(result.current.messages[1].isStreaming).toBe(false);
    expect(result.current.conversationId).toBe('conv-1');
    expect(result.current.isStreaming).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('sends the message and conversation id to the backend', async () => {
    fetchMock.mockResolvedValue(
      sseResponse('data: {"event":"message_end","conversation_id":"conv-7"}\n\n')
    );

    const { result } = renderHook(() => useDifyStream());

    await act(async () => {
      await result.current.sendMessage('first');
    });

    await waitFor(() => expect(result.current.conversationId).toBe('conv-7'));

    fetchMock.mockResolvedValue(
      sseResponse('data: {"event":"message_end","conversation_id":"conv-7"}\n\n')
    );

    await act(async () => {
      await result.current.sendMessage('second');
    });

    const secondCallBody = JSON.parse(fetchMock.mock.calls[1][1].body as string);
    expect(secondCallBody.message).toBe('second');
    expect(secondCallBody.conversationId).toBe('conv-7');

    const firstCall = fetchMock.mock.calls[0];
    expect(firstCall[0]).toContain('/chat/stream');
    expect(firstCall[1].method).toBe('POST');
  });

  it('handles Dify error events', async () => {
    fetchMock.mockResolvedValue(
      sseResponse('event: error\ndata: {"event":"error","error":"Model overloaded"}\n\n')
    );

    const { result } = renderHook(() => useDifyStream());

    await act(async () => {
      await result.current.sendMessage('Hi');
    });

    expect(result.current.error).toBe('Model overloaded');
    expect(result.current.messages[1].error).toBe('Model overloaded');
    expect(result.current.messages[1].isStreaming).toBe(false);
  });

  it('sets error state when the HTTP request fails', async () => {
    fetchMock.mockResolvedValue(
      new Response('Internal Server Error', { status: 500 })
    );

    const { result } = renderHook(() => useDifyStream());

    await act(async () => {
      await result.current.sendMessage('Hi');
    });

    expect(result.current.error).toContain('HTTP 500');
    expect(result.current.messages[1].error).toContain('HTTP 500');
    expect(result.current.isStreaming).toBe(false);
  });

  it('clearMessages resets messages, conversation and error', async () => {
    fetchMock.mockResolvedValue(
      sseResponse('data: {"event":"message","answer":"x","conversation_id":"c1"}\n\n')
    );

    const { result } = renderHook(() => useDifyStream());

    await act(async () => {
      await result.current.sendMessage('Hi');
    });

    expect(result.current.messages.length).toBeGreaterThan(0);

    act(() => {
      result.current.clearMessages();
    });

    expect(result.current.messages).toHaveLength(0);
    expect(result.current.conversationId).toBeNull();
    expect(result.current.error).toBeNull();
  });

  it('marks the assistant message as stopped when aborted', async () => {
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => {
            const err = new Error('aborted');
            err.name = 'AbortError';
            reject(err);
          });
        })
    );

    const { result } = renderHook(() => useDifyStream());

    let pending: Promise<void>;
    act(() => {
      pending = result.current.sendMessage('Hi');
    });

    await waitFor(() => expect(result.current.isStreaming).toBe(true));

    act(() => {
      result.current.stopStream();
    });

    await act(async () => {
      await pending;
    });

    expect(result.current.isStreaming).toBe(false);
    expect(result.current.messages[1].content).toContain('[Stopped]');
  });
});
```

## `frontend/src/components/ChatInterface.test.tsx`

```tsx
// src/components/ChatInterface.test.tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatInterface } from './ChatInterface';
import type { UseDifyStreamReturn } from '../hooks/useDifyStream';
import { useDifyStream } from '../hooks/useDifyStream';

vi.mock('../hooks/useDifyStream');

const mockedUseDifyStream = vi.mocked(useDifyStream);

function hookState(overrides: Partial<UseDifyStreamReturn> = {}): UseDifyStreamReturn {
  return {
    messages: [],
    isStreaming: false,
    error: null,
    sendMessage: vi.fn().mockResolvedValue(undefined),
    stopStream: vi.fn(),
    conversationId: null,
    clearMessages: vi.fn(),
    ...overrides,
  };
}

describe('ChatInterface', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the empty state when there are no messages', () => {
    mockedUseDifyStream.mockReturnValue(hookState());
    render(<ChatInterface />);

    expect(screen.getByText('Start a conversation with Dify AI')).toBeInTheDocument();
  });

  it('renders user and assistant messages', () => {
    mockedUseDifyStream.mockReturnValue(
      hookState({
        messages: [
          {
            id: '1',
            role: 'user',
            content: 'Hello there',
            timestamp: new Date(),
          },
          {
            id: '2',
            role: 'assistant',
            content: 'Hi! How can I help?',
            timestamp: new Date(),
          },
        ],
      })
    );
    render(<ChatInterface />);

    expect(screen.getByText('Hello there')).toBeInTheDocument();
    expect(screen.getByText('Hi! How can I help?')).toBeInTheDocument();
  });

  it('sends the trimmed message and clears the input on submit', async () => {
    const state = hookState();
    mockedUseDifyStream.mockReturnValue(state);
    render(<ChatInterface />);

    const textarea = screen.getByPlaceholderText(/Type your message/);
    await userEvent.type(textarea, '  What is SSE?  ');
    await userEvent.click(screen.getByRole('button', { name: /Send/ }));

    expect(state.sendMessage).toHaveBeenCalledWith('What is SSE?');
    expect(textarea).toHaveValue('');
  });

  it('sends the message when Enter is pressed', async () => {
    const state = hookState();
    mockedUseDifyStream.mockReturnValue(state);
    render(<ChatInterface />);

    const textarea = screen.getByPlaceholderText(/Type your message/);
    await userEvent.type(textarea, 'Hello{Enter}');

    expect(state.sendMessage).toHaveBeenCalledWith('Hello');
  });

  it('disables the send button when input is empty', () => {
    mockedUseDifyStream.mockReturnValue(hookState());
    render(<ChatInterface />);

    expect(screen.getByRole('button', { name: /Send/ })).toBeDisabled();
  });

  it('shows a stop button while streaming and wires it to stopStream', async () => {
    const state = hookState({ isStreaming: true });
    mockedUseDifyStream.mockReturnValue(state);
    render(<ChatInterface />);

    const stopButton = screen.getByRole('button', { name: /Stop/ });
    expect(stopButton).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Send/ })).not.toBeInTheDocument();

    await userEvent.click(stopButton);
    expect(state.stopStream).toHaveBeenCalled();
  });

  it('shows the conversation id badge once a session exists', () => {
    mockedUseDifyStream.mockReturnValue(
      hookState({ conversationId: 'abcdef1234567890' })
    );
    render(<ChatInterface />);

    expect(screen.getByText(/Session: abcdef12/)).toBeInTheDocument();
  });

  it('shows a global error banner', () => {
    mockedUseDifyStream.mockReturnValue(hookState({ error: 'Backend unreachable' }));
    render(<ChatInterface />);

    expect(screen.getByText(/Backend unreachable/)).toBeInTheDocument();
  });

  it('clears the conversation via the Clear button', async () => {
    const state = hookState();
    mockedUseDifyStream.mockReturnValue(state);
    render(<ChatInterface />);

    await userEvent.click(screen.getByRole('button', { name: 'Clear' }));
    expect(state.clearMessages).toHaveBeenCalled();
  });
});
```

## `frontend/nginx.conf`

```nginx
server {
    listen 80;
    server_name localhost;
    root /usr/share/nginx/html;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml;

    # Handle React Router
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Cache immutable build assets
    location /assets {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

## `frontend/Dockerfile`

```dockerfile
# frontend/Dockerfile
FROM node:22-alpine AS builder

WORKDIR /app
COPY package*.json ./
RUN npm ci

COPY . .
# Build-time API base URL (baked into the static bundle)
ARG VITE_API_URL=/api
ENV VITE_API_URL=${VITE_API_URL}
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
```

