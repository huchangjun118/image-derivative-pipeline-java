package com.github.huchangjun118.imagederivativepipelinejava.derivative;

/**
 * 一次派生计算的结果。
 *
 * @param cacheHit  是否命中缓存（复用已有产物）
 * @param bytes     派生图字节
 * @param mediaType 输出媒体类型
 */
public record DerivativeResult(boolean cacheHit, byte[] bytes, String mediaType) {
}
