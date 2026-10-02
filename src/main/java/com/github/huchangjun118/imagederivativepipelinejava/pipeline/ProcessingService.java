package com.github.huchangjun118.imagederivativepipelinejava.pipeline;

import com.github.huchangjun118.imagederivativepipelinejava.config.PipelineProperties;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeParams;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeStore;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.StoredDerivative;
import com.github.huchangjun118.imagederivativepipelinejava.error.ErrorCode;
import com.github.huchangjun118.imagederivativepipelinejava.error.PipelineException;
import com.github.huchangjun118.imagederivativepipelinejava.image.ImageFormat;
import com.github.huchangjun118.imagederivativepipelinejava.image.ImageNormalizer;
import com.github.huchangjun118.imagederivativepipelinejava.image.ImageProcessor;
import com.github.huchangjun118.imagederivativepipelinejava.image.SourceImage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 处理服务：原图上传、派生图生成、并发与容量控制。
 * <p>
 * 容量策略：固定工作线程 + 有界队列，队列满时拒绝并返回
 * {@link ErrorCode#CAPACITY_EXCEEDED}；同一派生图标识的并发请求合并为
 * 同一个计算任务（in-flight 去重）；任务带超时与取消，任何失败都不会
 * 发布半成品（产物经原子移动发布）。
 */
@Service
public class ProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);
    private static final String ID_PATTERN = "[0-9a-f]{64}";

    private final PipelineProperties properties;
    private final DerivativeStore store;
    private final ImageNormalizer normalizer;
    private final ImageProcessor processor;

    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, FutureTask<StoredDerivative>> inFlight = new ConcurrentHashMap<>();
    private final LruCache<String, SourceImage> decodedCache;

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    public ProcessingService(PipelineProperties properties, DerivativeStore store,
                             ImageNormalizer normalizer, ImageProcessor processor) {
        this.properties = properties;
        this.store = store;
        this.normalizer = normalizer;
        this.processor = processor;
        this.executor = new ThreadPoolExecutor(
                properties.getWorkerThreads(), properties.getWorkerThreads(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "image-worker-" + UUID.randomUUID());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.decodedCache = new LruCache<>(Math.max(4, properties.getWorkerThreads()));
    }

    /**
     * 上传原图：校验大小、解码、方向与色彩归一后持久化。
     */
    public SourceImage upload(byte[] bytes) {
        String requestId = UUID.randomUUID().toString();
        if (bytes == null || bytes.length == 0) {
            throw new PipelineException(ErrorCode.DECODE_FAILED, "上传内容为空", null, null, null, requestId);
        }
        if (bytes.length > properties.getMaxUploadBytes()) {
            throw new PipelineException(ErrorCode.SOURCE_TOO_LARGE,
                    "上传大小 " + bytes.length + " 超过上限 " + properties.getMaxUploadBytes(),
                    null, null, null, requestId);
        }
        String imageId = store.originalId(bytes);
        SourceImage source = normalizer.decodeAndNormalize(imageId, bytes,
                properties.getMaxSourceDimension(), properties.getMaxSourcePixels());
        store.storeOriginal(imageId, bytes);
        decodedCache.put(imageId, source);
        log.info("原图上传成功: imageId={}, format={}, size={}x{}, requestId={}",
                imageId, source.format(), source.width(), source.height(), requestId);
        return source;
    }

    /**
     * 获取原图元信息（优先使用已解码缓存）。
     */
    public SourceImage getImage(String imageId) {
        validateImageId(imageId);
        SourceImage cached = decodedCache.get(imageId);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = store.loadOriginal(imageId)
                .orElseThrow(() -> new PipelineException(ErrorCode.IMAGE_NOT_FOUND,
                        "原图不存在或已损坏: " + imageId, null, imageId, null, null));
        SourceImage source = normalizer.decodeAndNormalize(imageId, bytes,
                properties.getMaxSourceDimension(), properties.getMaxSourcePixels());
        decodedCache.put(imageId, source);
        return source;
    }

    /**
     * 生成派生图：缓存命中直接复用，否则提交计算并等待（受超时与容量约束）。
     */
    public StoredDerivative getOrCreateDerivative(String imageId, DerivativeParams params) {
        String requestId = UUID.randomUUID().toString();
        validateImageId(imageId);
        validateParams(params, requestId);
        String derivativeId = store.derivativeId(imageId, params);

        FutureTask<StoredDerivative> task = newTask(imageId, params, derivativeId, requestId);
        FutureTask<StoredDerivative> existing = inFlight.putIfAbsent(derivativeId, task);
        FutureTask<StoredDerivative> chosen = existing != null ? existing : task;
        boolean owner = existing == null;
        if (owner) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException e) {
                inFlight.remove(derivativeId, task);
                rejected.incrementAndGet();
                throw new PipelineException(ErrorCode.CAPACITY_EXCEEDED,
                        "处理队列已满（队列容量 " + properties.getQueueCapacity() + "），请稍后重试",
                        e, imageId, derivativeId, requestId);
            }
        }
        try {
            return chosen.get(properties.getTaskTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 只有任务发起者取消共享任务，避免波及其他等待同一结果的请求
            if (owner) {
                chosen.cancel(true);
            }
            throw new PipelineException(ErrorCode.PROCESSING_TIMEOUT,
                    "处理超时（" + properties.getTaskTimeoutMillis() + "ms）: imageId=" + imageId,
                    e, imageId, derivativeId, requestId);
        } catch (InterruptedException e) {
            if (owner) {
                chosen.cancel(true);
            }
            Thread.currentThread().interrupt();
            throw new PipelineException(ErrorCode.PROCESSING_CANCELLED,
                    "处理被中断: imageId=" + imageId, e, imageId, derivativeId, requestId);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PipelineException pe) {
                throw pe.withContext(imageId, derivativeId, requestId);
            }
            throw new PipelineException(ErrorCode.PROCESSING_FAILED,
                    "处理失败: " + cause.getMessage(), cause, imageId, derivativeId, requestId);
        }
    }

    /**
     * 创建任务，任务结束（成功/失败/取消）后自动清理 in-flight 登记。
     */
    private FutureTask<StoredDerivative> newTask(String imageId, DerivativeParams params,
                                                 String derivativeId, String requestId) {
        return new FutureTask<>(() -> compute(imageId, params, derivativeId, requestId)) {
            @Override
            protected void done() {
                inFlight.remove(derivativeId, this);
            }
        };
    }

    /**
     * 异步生成派生图，返回 Future 供调用方自行控制超时与取消。
     */
    public Future<StoredDerivative> submitDerivative(String imageId, DerivativeParams params) {
        String requestId = UUID.randomUUID().toString();
        validateImageId(imageId);
        validateParams(params, requestId);
        String derivativeId = store.derivativeId(imageId, params);
        FutureTask<StoredDerivative> task = newTask(imageId, params, derivativeId, requestId);
        FutureTask<StoredDerivative> existing = inFlight.putIfAbsent(derivativeId, task);
        if (existing == null) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException e) {
                inFlight.remove(derivativeId, task);
                rejected.incrementAndGet();
                throw new PipelineException(ErrorCode.CAPACITY_EXCEEDED,
                        "处理队列已满，请稍后重试", e, imageId, derivativeId, requestId);
            }
            return task;
        }
        return existing;
    }

    /**
     * 读取派生图产物（一致读取：产物经原子发布，不会读到半成品）。
     */
    public StoredDerivative loadDerivative(String derivativeId) {
        if (derivativeId == null || !derivativeId.matches(ID_PATTERN)) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER, "派生图标识非法: " + derivativeId,
                    null, null, derivativeId, null);
        }
        return store.load(derivativeId)
                .orElseThrow(() -> new PipelineException(ErrorCode.DERIVATIVE_NOT_FOUND,
                        "派生结果不存在或已损坏: " + derivativeId, null, null, derivativeId, null));
    }

    /**
     * 读取派生图字节。
     */
    public byte[] readDerivative(String derivativeId) {
        StoredDerivative d = loadDerivative(derivativeId);
        try {
            return java.nio.file.Files.readAllBytes(d.path());
        } catch (Exception e) {
            throw new PipelineException(ErrorCode.DERIVATIVE_NOT_FOUND,
                    "派生结果读取失败: " + derivativeId, e, null, derivativeId, null);
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poolSize", executor.getPoolSize());
        m.put("activeCount", executor.getActiveCount());
        m.put("queueSize", executor.getQueue().size());
        m.put("queueCapacity", properties.getQueueCapacity());
        m.put("completedTasks", executor.getCompletedTaskCount());
        m.put("inFlight", inFlight.size());
        m.put("cacheHits", cacheHits.get());
        m.put("cacheMisses", cacheMisses.get());
        m.put("rejected", rejected.get());
        return m;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------

    private StoredDerivative compute(String imageId, DerivativeParams params,
                                     String derivativeId, String requestId) {
        long start = System.currentTimeMillis();
        StoredDerivative cached = store.load(derivativeId).orElse(null);
        if (cached != null) {
            cacheHits.incrementAndGet();
            log.info("派生图缓存命中: imageId={}, derivativeId={}, requestId={}",
                    imageId, derivativeId, requestId);
            return cached;
        }
        cacheMisses.incrementAndGet();
        byte[] originalBytes = store.loadOriginal(imageId)
                .orElseThrow(() -> new PipelineException(ErrorCode.IMAGE_NOT_FOUND,
                        "原图不存在或已损坏: " + imageId, null, imageId, derivativeId, requestId));
        SourceImage source;
        try {
            source = decodedCache.get(imageId);
            if (source == null) {
                source = normalizer.decodeAndNormalize(imageId, originalBytes,
                        properties.getMaxSourceDimension(), properties.getMaxSourcePixels());
                decodedCache.put(imageId, source);
            }
        } catch (PipelineException e) {
            throw e.withContext(imageId, derivativeId, requestId);
        } catch (RuntimeException e) {
            throw new PipelineException(ErrorCode.PROCESSING_FAILED,
                    "原图解码失败: " + e.getMessage(), e, imageId, derivativeId, requestId);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new PipelineException(ErrorCode.PROCESSING_CANCELLED,
                    "处理已取消: imageId=" + imageId, null, imageId, derivativeId, requestId);
        }
        byte[] bytes;
        try {
            bytes = processor.process(source.normalized(), params);
        } catch (PipelineException e) {
            throw e.withContext(imageId, derivativeId, requestId);
        } catch (RuntimeException e) {
            throw new PipelineException(ErrorCode.PROCESSING_FAILED,
                    "派生图处理失败: " + e.getMessage(), e, imageId, derivativeId, requestId);
        }
        StoredDerivative result = store.store(imageId, params, bytes);
        log.info("派生图生成完成: imageId={}, derivativeId={}, size={}x{}, {}B, 耗时={}ms, requestId={}",
                imageId, derivativeId, params.width(), params.height(), bytes.length,
                System.currentTimeMillis() - start, requestId);
        return result;
    }

    private void validateImageId(String imageId) {
        if (imageId == null || !imageId.matches(ID_PATTERN)) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER,
                    "原图标识非法: " + imageId, null, imageId, null, null);
        }
    }

    private void validateParams(DerivativeParams params, String requestId) {
        if (params == null) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER, "派生参数不能为空",
                    null, null, null, requestId);
        }
        int w = params.width();
        int h = params.height();
        if (w <= 0 || h <= 0) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER,
                    "目标尺寸必须为正整数: " + w + "x" + h, null, null, null, requestId);
        }
        if (w > properties.getMaxTargetDimension() || h > properties.getMaxTargetDimension()) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER,
                    "目标尺寸 " + w + "x" + h + " 超过上限 " + properties.getMaxTargetDimension(),
                    null, null, null, requestId);
        }
        if ((long) w * (long) h > properties.getMaxTargetPixels()) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER,
                    "目标总像素超限: " + ((long) w * h), null, null, null, requestId);
        }
        if (params.fitMode() == null) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER,
                    "fitMode 不能为空", null, null, null, requestId);
        }
        if (params.format() != ImageFormat.JPEG && params.format() != ImageFormat.PNG) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER,
                    "不支持的目标格式: " + params.format(), null, null, null, requestId);
        }
        float q = params.quality();
        if (Float.isNaN(q) || q < 0.01f || q > 1.0f) {
            throw new PipelineException(ErrorCode.INVALID_PARAMETER,
                    "编码质量必须在 (0, 1] 范围内: " + q, null, null, null, requestId);
        }
    }

    /**
     * 有界 LRU 缓存，限制内存中驻留的解码图像数量。
     */
    private static class LruCache<K, V> {
        private final int maxSize;
        private final LinkedHashMap<K, V> map;

        LruCache(int maxSize) {
            this.maxSize = maxSize;
            this.map = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > LruCache.this.maxSize;
                }
            };
        }

        synchronized V get(K key) {
            return map.get(key);
        }

        synchronized void put(K key, V value) {
            map.put(key, value);
        }
    }
}
