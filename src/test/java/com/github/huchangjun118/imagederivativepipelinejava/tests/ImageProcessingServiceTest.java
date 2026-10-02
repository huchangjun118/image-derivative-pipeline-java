package com.github.huchangjun118.imagederivativepipelinejava.tests;

import com.github.huchangjun118.imagederivativepipelinejava.api.ApiException;
import com.github.huchangjun118.imagederivativepipelinejava.config.PipelineProperties;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeCache;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeParams;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeResult;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.ImageProcessingService;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.ImageProcessor;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.ProcessingExecutor;
import com.github.huchangjun118.imagederivativepipelinejava.storage.OriginalImageStore;
import com.github.huchangjun118.imagederivativepipelinejava.storage.StoredOriginal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageProcessingServiceTest {

    @TempDir
    Path tempDir;

    private record Fixture(ImageProcessingService service, PipelineProperties props,
                           ProcessingExecutor executor) implements AutoCloseable {
        @Override
        public void close() {
            executor.close();
        }
    }

    private Fixture fixture(ImageProcessor processor, java.util.function.Consumer<PipelineProperties> tweak) {
        return fixture("default", processor, tweak);
    }

    private Fixture fixture(String name, ImageProcessor processor,
                            java.util.function.Consumer<PipelineProperties> tweak) {
        PipelineProperties props = new PipelineProperties();
        props.setCacheDir(tempDir.resolve(name + "/cache").toString());
        props.setOriginalDir(tempDir.resolve(name + "/originals").toString());
        tweak.accept(props);
        ProcessingExecutor executor = new ProcessingExecutor(props);
        ImageProcessingService service = new ImageProcessingService(props,
                new OriginalImageStore(Path.of(props.getOriginalDir())),
                new DerivativeCache(Path.of(props.getCacheDir())),
                processor, executor);
        return new Fixture(service, props, executor);
    }

    private Fixture fixture() {
        return fixture(new ImageProcessor(), p -> { });
    }

    private static byte[] sampleJpeg() {
        return TestImages.toJpeg(TestImages.gradient(64, 48));
    }

    private static DerivativeParams params() {
        return new DerivativeParams(32, 24, DerivativeParams.Fit.FIT, "jpeg", 85);
    }

    @Test
    void cacheReuseReturnsIdenticalBytes() {
        try (Fixture f = fixture()) {
            StoredOriginal original = f.service().upload(sampleJpeg());
            DerivativeResult first = f.service().derive(original.id(), params());
            DerivativeResult second = f.service().derive(original.id(), params());

            assertFalse(first.cacheHit());
            assertTrue(second.cacheHit());
            assertArrayEquals(first.bytes(), second.bytes(), "复用缓存与重新计算必须一致");
        }
    }

    @Test
    void resultsAreDeterministicAcrossRestart() {
        byte[] firstRun;
        String imageId;
        try (Fixture f = fixture()) {
            StoredOriginal original = f.service().upload(sampleJpeg());
            imageId = original.id();
            firstRun = f.service().derive(imageId, params()).bytes();
        }
        // 模拟重启：全新实例、相同存储目录
        try (Fixture f = fixture()) {
            DerivativeResult afterRestart = f.service().derive(imageId, params());
            assertTrue(afterRestart.cacheHit(), "重启后应命中已有缓存");
            assertArrayEquals(firstRun, afterRestart.bytes(), "重启后重新处理必须逐字节一致");
        }
        // 清空缓存后重算，结果仍须一致
        try (Fixture f = fixture()) {
            Files.list(Path.of(f.props().getCacheDir())).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            DerivativeResult recomputed = f.service().derive(imageId, params());
            assertFalse(recomputed.cacheHit());
            assertArrayEquals(firstRun, recomputed.bytes(), "清缓存后重算必须逐字节一致");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void corruptedCacheIsNotServedAndIsRecomputed() throws IOException {
        try (Fixture f = fixture()) {
            StoredOriginal original = f.service().upload(sampleJpeg());
            DerivativeResult good = f.service().derive(original.id(), params());

            Path cacheFile = Files.list(Path.of(f.props().getCacheDir())).findFirst().orElseThrow();
            byte[] corrupted = Files.readAllBytes(cacheFile);
            corrupted[corrupted.length - 1] ^= 0xFF; // 破坏 payload
            Files.write(cacheFile, corrupted);

            DerivativeResult afterCorruption = f.service().derive(original.id(), params());
            assertFalse(afterCorruption.cacheHit(), "损坏的缓存不得被当成有效结果");
            assertArrayEquals(good.bytes(), afterCorruption.bytes());
        }
    }

    @Test
    void truncatedCacheIsNotServed() throws IOException {
        try (Fixture f = fixture()) {
            StoredOriginal original = f.service().upload(sampleJpeg());
            DerivativeResult good = f.service().derive(original.id(), params());

            Path cacheFile = Files.list(Path.of(f.props().getCacheDir())).findFirst().orElseThrow();
            byte[] raw = Files.readAllBytes(cacheFile);
            Files.write(cacheFile, java.util.Arrays.copyOf(raw, raw.length / 2)); // 截断

            DerivativeResult result = f.service().derive(original.id(), params());
            assertFalse(result.cacheHit(), "写入不完整的产物不得被当成有效结果");
            assertArrayEquals(good.bytes(), result.bytes());
        }
    }

    @Test
    void concurrentDeriveComputesOnceAndAgrees() throws Exception {
        AtomicInteger computeCount = new AtomicInteger();
        ImageProcessor counting = new ImageProcessor() {
            @Override
            public BufferedImage resize(BufferedImage normalized, DerivativeParams params) {
                computeCount.incrementAndGet();
                return super.resize(normalized, params);
            }
        };
        try (Fixture f = fixture(counting, p -> { })) {
            StoredOriginal original = f.service().upload(sampleJpeg());
            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<DerivativeResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return f.service().derive(original.id(), params());
                }));
            }
            start.countDown();
            byte[] expected = null;
            for (Future<DerivativeResult> future : futures) {
                DerivativeResult r = future.get(30, TimeUnit.SECONDS);
                if (expected == null) {
                    expected = r.bytes();
                } else {
                    assertArrayEquals(expected, r.bytes(), "并发结果必须逐字节一致");
                }
            }
            pool.shutdown();
            assertEquals(1, computeCount.get(), "同一 key 并发请求只应计算一次");
        }
    }

    @Test
    void invalidParamsAreRejectedBeforeProcessing() {
        try (Fixture f = fixture()) {
            StoredOriginal original = f.service().upload(sampleJpeg());

            ApiException e1 = assertThrows(ApiException.class, () -> f.service().derive(
                    original.id(), new DerivativeParams(0, 24, DerivativeParams.Fit.FIT, "jpeg", 85)));
            assertEquals("INVALID_DIMENSION", e1.getCode());
            assertEquals(400, e1.getStatus());

            ApiException e2 = assertThrows(ApiException.class, () -> f.service().derive(
                    original.id(), new DerivativeParams(32, 24, DerivativeParams.Fit.FIT, "tiff", 85)));
            assertEquals("INVALID_FORMAT", e2.getCode());

            ApiException e3 = assertThrows(ApiException.class, () -> f.service().derive(
                    original.id(), new DerivativeParams(32, 24, DerivativeParams.Fit.FIT, "jpeg", 0)));
            assertEquals("INVALID_QUALITY", e3.getCode());

            ApiException e4 = assertThrows(ApiException.class, () -> f.service().derive(
                    original.id(), new DerivativeParams(999999, 24, DerivativeParams.Fit.FIT, "jpeg", 85)));
            assertEquals("INVALID_DIMENSION", e4.getCode());
        }
    }

    @Test
    void corruptUploadIsRejected() {
        try (Fixture f = fixture()) {
            ApiException e = assertThrows(ApiException.class,
                    () -> f.service().upload(new byte[]{1, 2, 3, 4, 5}));
            assertEquals("UNSUPPORTED_FORMAT", e.getCode());
            assertEquals(415, e.getStatus());

            // 头部合法但内容损坏的 JPEG
            byte[] truncated = java.util.Arrays.copyOf(sampleJpeg(), 100);
            ApiException e2 = assertThrows(ApiException.class, () -> f.service().upload(truncated));
            assertTrue(e2.getCode().equals("IMAGE_UNDECODABLE")
                    || e2.getCode().equals("UNSUPPORTED_FORMAT"), "损坏图片必须被拒绝: " + e2.getCode());
        }
    }

    @Test
    void oversizedImageIsRejectedBeforeDecoding() {
        try (Fixture f = fixture(new ImageProcessor(), p -> p.setMaxPixels(1000))) {
            ApiException e = assertThrows(ApiException.class,
                    () -> f.service().upload(sampleJpeg()));
            assertEquals("IMAGE_TOO_LARGE", e.getCode());
            assertEquals(413, e.getStatus());
        }
    }

    @Test
    void unknownImageIdIsNotFound() {
        try (Fixture f = fixture()) {
            ApiException e = assertThrows(ApiException.class,
                    () -> f.service().derive("0".repeat(64), params()));
            assertEquals("IMAGE_NOT_FOUND", e.getCode());
            assertEquals(404, e.getStatus());
        }
    }

    @Test
    void queueOverflowIsRejectedWithDistinctReason() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        ImageProcessor slow = new ImageProcessor() {
            @Override
            public BufferedImage resize(BufferedImage normalized, DerivativeParams params) {
                try {
                    block.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.resize(normalized, params);
            }
        };
        // 1 个 worker + 1 个排队位：第 3 个请求必须被拒绝
        try (Fixture f = fixture(slow, p -> {
            p.setWorkerThreads(1);
            p.setQueueCapacity(1);
        })) {
            StoredOriginal original = f.service().upload(sampleJpeg());
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                Future<ApiException> first = submitExpectingNothing(pool, f, original.id(), 32, 24);
                Future<ApiException> second = submitExpectingNothing(pool, f, original.id(), 33, 24);
                // 等两个任务占满 worker 与队列
                Thread.sleep(300);
                ApiException e = assertThrows(ApiException.class, () -> f.service().derive(
                        original.id(), new DerivativeParams(34, 24, DerivativeParams.Fit.FIT, "jpeg", 85)));
                assertEquals("QUEUE_FULL", e.getCode());
                assertEquals(429, e.getStatus());
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            } finally {
                block.countDown();
                pool.shutdown();
                pool.awaitTermination(15, TimeUnit.SECONDS);
            }
        }
    }

    private Future<ApiException> submitExpectingNothing(ExecutorService pool, Fixture f,
                                                        String id, int w, int h) {
        return pool.submit(() -> {
            try {
                f.service().derive(id, new DerivativeParams(w, h, DerivativeParams.Fit.FIT, "jpeg", 85));
                return null;
            } catch (ApiException e) {
                return e;
            }
        });
    }

    @Test
    void timeoutCancelsAndDoesNotPolluteSubsequentRequests() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        ImageProcessor slow = new ImageProcessor() {
            @Override
            public BufferedImage resize(BufferedImage normalized, DerivativeParams params) {
                try {
                    block.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("任务被中断，放弃处理");
                }
                return super.resize(normalized, params);
            }
        };
        try (Fixture f = fixture("timeout-case", slow, p -> p.setProcessingTimeoutMillis(300))) {
            StoredOriginal original = f.service().upload(sampleJpeg());
            ApiException e = assertThrows(ApiException.class,
                    () -> f.service().derive(original.id(), params()));
            assertEquals("PROCESSING_TIMEOUT", e.getCode());
            assertEquals(504, e.getStatus());
            assertTrue(e.getMessage().contains(original.id()), "失败原因要能定位到原图");
            // 超时取消不得留下会污染后续请求的中间状态
            assertTrue(f.service().getExisting(original.id(), params()).isEmpty(),
                    "被取消的任务不得留下半成品缓存");
        }
        // 超时取消后：正常处理器可以立即恢复服务
        try (Fixture f = fixture()) {
            StoredOriginal original = f.service().upload(sampleJpeg());
            DerivativeResult result = f.service().derive(original.id(), params());
            assertFalse(result.cacheHit());
            assertTrue(result.bytes().length > 0);
        }
    }

    @Test
    void processingFailureIsReportedWithImageIdAndRecoverable() {
        ImageProcessor failing = new ImageProcessor() {
            @Override
            public byte[] encode(BufferedImage image, DerivativeParams params) throws IOException {
                throw new IOException("模拟编码失败");
            }
        };
        String imageId;
        try (Fixture f = fixture(failing, p -> { })) {
            StoredOriginal original = f.service().upload(sampleJpeg());
            imageId = original.id();
            ApiException e = assertThrows(ApiException.class,
                    () -> f.service().derive(imageId, params()));
            assertEquals("PROCESSING_FAILED", e.getCode());
            assertEquals(422, e.getStatus());
            assertTrue(e.getMessage().contains(imageId), "失败原因要能定位到具体请求与原图");
        }
        // 失败后不留污染状态：换正常处理器重试同一参数可以成功
        try (Fixture f = fixture()) {
            DerivativeResult result = f.service().derive(imageId, params());
            assertFalse(result.cacheHit());
            DerivativeResult cached = f.service().derive(imageId, params());
            assertTrue(cached.cacheHit());
            assertArrayEquals(result.bytes(), cached.bytes());
        }
    }

    @Test
    void differentParamsProduceDifferentResults() {
        try (Fixture f = fixture()) {
            StoredOriginal original = f.service().upload(sampleJpeg());
            DerivativeResult a = f.service().derive(original.id(),
                    new DerivativeParams(32, 24, DerivativeParams.Fit.FIT, "jpeg", 85));
            DerivativeResult b = f.service().derive(original.id(),
                    new DerivativeParams(16, 12, DerivativeParams.Fit.FIT, "jpeg", 85));
            DerivativeResult c = f.service().derive(original.id(),
                    new DerivativeParams(32, 24, DerivativeParams.Fit.FIT, "png", 85));
            assertNotEquals(java.util.Arrays.hashCode(a.bytes()), java.util.Arrays.hashCode(b.bytes()));
            assertNotEquals(java.util.Arrays.hashCode(a.bytes()), java.util.Arrays.hashCode(c.bytes()));
        }
    }
}
