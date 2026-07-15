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
