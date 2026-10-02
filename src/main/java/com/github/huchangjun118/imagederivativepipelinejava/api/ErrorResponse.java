package com.github.huchangjun118.imagederivativepipelinejava.api;

import java.time.Instant;

/**
 * 统一错误响应体。
 */
public class ErrorResponse {

    private final String code;
    private final String message;
    private final String requestId;
    private final Instant timestamp;

    public ErrorResponse(String code, String message, String requestId) {
        this.code = code;
        this.message = message;
        this.requestId = requestId;
        this.timestamp = Instant.now();
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
