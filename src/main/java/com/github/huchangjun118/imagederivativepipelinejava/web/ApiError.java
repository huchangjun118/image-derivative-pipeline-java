package com.github.huchangjun118.imagederivativepipelinejava.web;

import java.time.Instant;

/**
 * 统一错误响应体。
 */
public record ApiError(
        String errorCode,
        String message,
        String imageId,
        String derivativeId,
        String requestId,
        Instant timestamp
) {
}
