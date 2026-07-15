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
