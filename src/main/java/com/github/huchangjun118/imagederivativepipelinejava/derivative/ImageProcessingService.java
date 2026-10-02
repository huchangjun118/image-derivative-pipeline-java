package com.github.huchangjun118.imagederivativepipelinejava.derivative;

import com.github.huchangjun118.imagederivativepipelinejava.api.ApiException;
import com.github.huchangjun118.imagederivativepipelinejava.config.PipelineProperties;
import com.github.huchangjun118.imagederivativepipelinejava.storage.OriginalImageStore;
import com.github.huchangjun118.imagederivativepipelinejava.storage.StoredOriginal;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 派生管线门面：接收上传、校验、调度处理、缓存复用与一致读取。
 * 所有派生结果只由原图内容与请求参数决定；同一 key 并发请求只计算一次。
 */
public class ImageProcessingService {

    private static final Set<String> SUPPORTED_INPUT = Set.of("jpeg", "png", "gif", "bmp", "webp");
    private static final Set<String> SUPPORTED_OUTPUT = Set.of("jpeg", "jpg", "png");

    private final PipelineProperties properties;
    private final OriginalImageStore originalStore;
    private final DerivativeCache cache;
    private final ImageProcessor processor;
    private final ProcessingExecutor executor;

    /** 进行中的派生任务：同一缓存键的单飞入口。 */
    private final ConcurrentHashMap<String, CompletableFuture<DerivativeResult>> inFlight =
            new ConcurrentHashMap<>();

    public ImageProcessingService(PipelineProperties properties,
                                  OriginalImageStore originalStore,
                                  DerivativeCache cache,
                                  ImageProcessor processor,
                                  ProcessingExecutor executor) {
        this.properties = properties;
        this.originalStore = originalStore;
        this.cache = cache;
        this.processor = processor;
        this.executor = executor;
    }

    /**
     * 接收上传的原图：校验格式与尺寸，解码归一后入库。
     * 所有校验在处理开始前完成，非法请求不会产出任何中间结果。
     */
    public StoredOriginal upload(byte[] data) {
        if (data == null || data.length == 0) {
            throw ApiException.badRequest("EMPTY_UPLOAD", "上传内容为空");
        }
        if (data.length > properties.getMaxUploadBytes()) {
            throw ApiException.payloadTooLarge("UPLOAD_TOO_LARGE",
                    "上传大小 " + data.length + " 字节，超过上限 " + properties.getMaxUploadBytes());
        }

        ImageProcessor.ProbeResult probe;
        try {
            probe = processor.probe(data);
        } catch (IOException e) {
            throw ApiException.unsupportedMediaType("UNSUPPORTED_FORMAT",
                    "无法识别的图片格式，仅支持 JPEG/PNG 等常见格式: " + e.getMessage());
        }
        if (!SUPPORTED_INPUT.contains(probe.format())) {
            throw ApiException.unsupportedMediaType("UNSUPPORTED_FORMAT",
                    "不支持的图片格式: " + probe.format() + "，仅支持 " + SUPPORTED_INPUT);
        }
        long pixels = (long) probe.width() * probe.height();
        if (pixels > properties.getMaxPixels()) {
            throw ApiException.payloadTooLarge("IMAGE_TOO_LARGE",
                    "图片尺寸 " + probe.width() + "x" + probe.height()
                            + " 超过像素上限 " + properties.getMaxPixels());
        }
        if (pixels * 4 > properties.getMaxDecodedBytes()) {
            throw ApiException.payloadTooLarge("IMAGE_TOO_LARGE",
                    "图片解码后占用超过内存上限 " + properties.getMaxDecodedBytes() + " 字节");
        }

        BufferedImage normalized;
        try {
            normalized = processor.decodeAndNormalize(data);
        } catch (IOException | RuntimeException e) {
            throw ApiException.unprocessable("IMAGE_UNDECODABLE",
                    "图片无法解码: " + e.getMessage());
        }

        String id = originalStore.save(data);
        return new StoredOriginal(id, id, data.length,
                normalized.getWidth(), normalized.getHeight());
    }

    /**
     * 生成（或复用）指定参数的派生图。
     * 命中缓存直接返回；未命中时同一 key 只计算一次，支持超时与取消。
     */
    public DerivativeResult derive(String imageId, DerivativeParams params) {
        validateParams(params);
        byte[] original = originalStore.load(imageId)
                .orElseThrow(() -> ApiException.notFound("IMAGE_NOT_FOUND",
                        "原图不存在: " + imageId));

        String cacheKey = cacheKey(imageId, params);
        Optional<byte[]> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return new DerivativeResult(true, cached.get(), mediaType(params));
        }

        CompletableFuture<DerivativeResult> future = inFlight.computeIfAbsent(cacheKey,
                key -> submitTask(key, original, params, imageId));
        try {
            return future.get(properties.getProcessingTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ApiException(504, "PROCESSING_TIMEOUT",
                    "派生处理超时（" + properties.getProcessingTimeoutMillis() + "ms），imageId=" + imageId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ApiException(500, "PROCESSING_INTERRUPTED",
                    "派生处理被中断，imageId=" + imageId);
        } catch (ExecutionException e) {
            throw unwrap(e.getCause(), imageId);
        } finally {
            inFlight.remove(cacheKey, future);
        }
    }

    /**
     * 读取已生成的派生图；未生成时返回空（由调用方决定 404 或触发生成）。
     * 读取只命中完整写入的缓存，生成期间不会返回半成品。
     */
    public Optional<DerivativeResult> getExisting(String imageId, DerivativeParams params) {
        validateParams(params);
        return cache.get(cacheKey(imageId, params))
                .map(bytes -> new DerivativeResult(true, bytes, mediaType(params)));
    }

    private CompletableFuture<DerivativeResult> submitTask(String cacheKey, byte[] original,
                                                           DerivativeParams params, String imageId) {
        CompletableFuture<DerivativeResult> future = new CompletableFuture<>();
        Future<?>[] worker = new Future<?>[1];
        future.whenComplete((r, t) -> {
            Future<?> w = worker[0];
            if (w != null && future.isCancelled()) {
                w.cancel(true);
            }
        });
        try {
            worker[0] = executor.submit(() -> {
                try {
                    future.complete(compute(cacheKey, original, params));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
                return null;
            });
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        if (future.isDone() && worker[0] != null && future.isCancelled()) {
            worker[0].cancel(true);
        }
        return future;
    }

    private DerivativeResult compute(String cacheKey, byte[] original, DerivativeParams params)
            throws IOException {
        // 双重检查：单飞表项被移除后、本任务入队前，缓存可能已被先前任务写入
        Optional<byte[]> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return new DerivativeResult(true, cached.get(), mediaType(params));
        }
        BufferedImage normalized = processor.decodeAndNormalize(original);
        BufferedImage resized = processor.resize(normalized, params);
        byte[] bytes = processor.encode(resized, params);
        cache.put(cacheKey, bytes);
        return new DerivativeResult(false, bytes, mediaType(params));
    }

    private RuntimeException unwrap(Throwable cause, String imageId) {
        if (cause instanceof ApiException api) {
            return api;
        }
        if (cause instanceof IOException io) {
            return ApiException.unprocessable("PROCESSING_FAILED",
                    "派生处理失败，imageId=" + imageId + ": " + io.getMessage());
        }
        return new ApiException(500, "PROCESSING_FAILED",
                "派生处理失败，imageId=" + imageId + ": " + cause, cause);
    }

    private void validateParams(DerivativeParams params) {
        if (params == null) {
            throw ApiException.badRequest("INVALID_PARAMS", "缺少派生参数");
        }
        int max = properties.getMaxDimension();
        if (params.getWidth() < 1 || params.getWidth() > max
                || params.getHeight() < 1 || params.getHeight() > max) {
            throw ApiException.badRequest("INVALID_DIMENSION",
                    "目标尺寸 " + params.getWidth() + "x" + params.getHeight()
                            + " 非法，宽高必须在 1.." + max + " 之间");
        }
        if (!SUPPORTED_OUTPUT.contains(params.getFormat())) {
            throw ApiException.badRequest("INVALID_FORMAT",
                    "不支持的输出格式: " + params.getFormat() + "，仅支持 " + SUPPORTED_OUTPUT);
        }
        if (params.getQuality() < 1 || params.getQuality() > 100) {
            throw ApiException.badRequest("INVALID_QUALITY",
                    "质量参数 " + params.getQuality() + " 非法，必须在 1..100 之间");
        }
    }

    private String cacheKey(String imageId, DerivativeParams params) {
        return "v" + properties.getCacheVersion() + "_" + imageId + "_" + params.canonical();
    }

    private String mediaType(DerivativeParams params) {
        return switch (params.getFormat().toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            default -> "image/jpeg";
        };
    }
}
