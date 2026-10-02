package com.github.huchangjun118.imagederivativepipelinejava.derivative;

import java.nio.file.Path;

/**
 * 缓存命中的派生图产物。
 */
public record StoredDerivative(
        String derivativeId,
        String imageId,
        DerivativeParams params,
        Path path,
        long sizeBytes,
        long createdAt,
        long lastAccessedAt
) {
}
