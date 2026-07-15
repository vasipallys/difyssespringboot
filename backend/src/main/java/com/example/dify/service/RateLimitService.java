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
