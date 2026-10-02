package com.github.huchangjun118.imagederivativepipelinejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.github.huchangjun118.imagederivativepipelinejava.pipeline.ProcessingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图片处理流水线端到端测试。
 */
class ImagePipelineTest {

    @TempDir
    Path tempDir;

    private ProcessingService service;
    private PipelineProperties props;

    @BeforeEach
    void setUp() {
        props = defaultProps(tempDir);
        service = newService(props);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private PipelineProperties defaultProps(Path dir) {
        PipelineProperties p = new PipelineProperties();
        p.setStorageDir(dir);
        p.setWorkerThreads(4);
        p.setQueueCapacity(128);
        p.setTaskTimeoutMillis(30_000);
        p.setMaxUploadBytes(20L * 1024 * 1024);
        p.setMaxSourceDimension(8000);
        p.setMaxSourcePixels(40_000_000);
        p.setMaxTargetDimension(4096);
        p.setMaxTargetPixels(16_000_000);
        p.setCacheTtlMillis(7L * 24 * 60 * 60 * 1000);
        return p;
    }

    private ProcessingService newService(PipelineProperties p) {
        ObjectMapper mapper = new ObjectMapper();
        DerivativeStore store = new DerivativeStore(p, mapper);
        try {
            store.init();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new ProcessingService(p, store, new ImageNormalizer(new com.github.huchangjun118.imagederivativepipelinejava.image.ExifOrientationExtractor()),
                new ImageProcessor());
    }

    private DerivativeParams params(int w, int h, DerivativeParams.FitMode fit, ImageFormat fmt, float q) {
        return new DerivativeParams(w, h, fit, fmt, q);
    }

    // ------------------------------------------------------------------
    // 方向纠正
    // ------------------------------------------------------------------

    @Test
    void orientationCorrection_rotatesByExif() {
        byte[] jpeg = TestImages.jpegWithOrientation(TestImages.rgbImage(100, 60), 6);
        SourceImage source = service.upload(jpeg);
        assertEquals(60, source.width());
        assertEquals(100, source.height());
        BufferedImage n = source.normalized();
        // 左上角 (200,100,50) 色块经顺时针 90 度旋转后到右上角
        assertColorClose(new java.awt.Color(200, 100, 50), new java.awt.Color(n.getRGB(55, 5)), 30);
        // 右下角 (50,100,200) 色块旋转后到左下角
        assertColorClose(new java.awt.Color(50, 100, 200), new java.awt.Color(n.getRGB(5, 95)), 30);
    }

    @Test
    void orientationCorrection_identityWhenNoOrientation() {
        byte[] png = TestImages.toPng(TestImages.rgbImage(40, 30));
        SourceImage source = service.upload(png);
        assertEquals(40, source.width());
        assertEquals(30, source.height());
    }

    // ------------------------------------------------------------------
    // 色彩归一
    // ------------------------------------------------------------------

    @Test
    void colorNormalization_grayAndRgbConsistent() {
        byte[] grayPng = TestImages.toPng(TestImages.grayImage(8, 8, 128));
        byte[] rgbPng = TestImages.toPng(TestImages.rgbImage(8, 8));
        SourceImage gray = service.upload(grayPng);
        // 灰度图归一后 R=G=B
        BufferedImage g = gray.normalized();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int rgb = g.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int gg = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                assertEquals(r, gg, "灰度归一后通道应相等");
                assertEquals(gg, b, "灰度归一后通道应相等");
            }
        }
        // RGB 图归一后像素不丢失
        SourceImage rgb = service.upload(rgbPng);
        assertNotNull(rgb.normalized());
    }

    @Test
    void colorNormalization_linearToSrgb() {
        // 线性 RGB 空间中值为 128（约 0.5 线性光），归一到 sRGB 后约为 187
        java.awt.color.ColorSpace linear = java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_LINEAR_RGB);
        ColorModel cm = new ComponentColorModel(linear, false, false,
                java.awt.Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        BufferedImage linearImg = new BufferedImage(cm,
                cm.createCompatibleWritableRaster(4, 4), false, null);
        WritableRaster raster = linearImg.getRaster();
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                raster.setSample(x, y, 0, 128);
                raster.setSample(x, y, 1, 128);
                raster.setSample(x, y, 2, 128);
            }
        }
        ImageNormalizer normalizer = new ImageNormalizer(
                new com.github.huchangjun118.imagederivativepipelinejava.image.ExifOrientationExtractor());
        BufferedImage srgb = normalizer.normalizeColor(linearImg);
        int r = (srgb.getRGB(1, 1) >> 16) & 0xFF;
        assertEquals(187, r, 4, "线性 0.5 归一到 sRGB 应约为 187");
    }

    // ------------------------------------------------------------------
    // 确定性
    // ------------------------------------------------------------------

    @Test
    void deterministicOutput_sameBytesOnRepeat() {
        byte[] png = TestImages.toPng(TestImages.rgbImage(120, 90));
        SourceImage source = service.upload(png);
        DerivativeParams p = params(40, 40, DerivativeParams.FitMode.CONTAIN, ImageFormat.JPEG, 0.8f);
        byte[] d1 = service.readDerivative(service.getOrCreateDerivative(source.imageId(), p).derivativeId());
        byte[] d2 = service.readDerivative(service.getOrCreateDerivative(source.imageId(), p).derivativeId());
        assertArrayEquals(d1, d2, "重复处理必须逐字节一致");

        DerivativeParams p2 = params(40, 40, DerivativeParams.FitMode.COVER, ImageFormat.PNG, 1.0f);
        byte[] d3 = service.readDerivative(service.getOrCreateDerivative(source.imageId(), p2).derivativeId());
        byte[] d4 = service.readDerivative(service.getOrCreateDerivative(source.imageId(), p2).derivativeId());
        assertArrayEquals(d3, d4, "PNG 派生也必须逐字节一致");
    }

    @Test
    void deterministicOutput_concurrentAllEqual() throws Exception {
        byte[] png = TestImages.toPng(TestImages.rgbImage(100, 100));
        SourceImage source = service.upload(png);
        DerivativeParams p = params(50, 50, DerivativeParams.FitMode.COVER, ImageFormat.JPEG, 0.75f);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<byte[]>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> service.readDerivative(
                    service.getOrCreateDerivative(source.imageId(), p).derivativeId())));
        }
        byte[] first = null;
        for (Future<byte[]> f : futures) {
            byte[] bytes = f.get(30, TimeUnit.SECONDS);
            if (first == null) {
                first = bytes;
            } else {
                assertArrayEquals(first, bytes, "并发处理结果必须逐字节一致");
            }
        }
        pool.shutdownNow();
    }

    @Test
    void deterministicOutput_restartReusesCache() {
        byte[] png = TestImages.toPng(TestImages.rgbImage(80, 80));
        SourceImage source = service.upload(png);
        DerivativeParams p = params(30, 30, DerivativeParams.FitMode.CONTAIN, ImageFormat.PNG, 1.0f);
        byte[] d1 = service.readDerivative(service.getOrCreateDerivative(source.imageId(), p).derivativeId());

        // 模拟重启：新实例、同一存储目录
        service.shutdown();
        ProcessingService service2 = newService(props);
        try {
            StoredDerivative cached = service2.getOrCreateDerivative(source.imageId(), p);
            byte[] d2 = service2.readDerivative(cached.derivativeId());
            assertArrayEquals(d1, d2, "重启后重新处理必须逐字节一致");
            assertEquals(1L, service2.stats().get("cacheHits"), "重启后应命中磁盘缓存");
        } finally {
            service2.shutdown();
            service = null;
        }
    }

    // ------------------------------------------------------------------
    // 缓存复用与损坏恢复
    // ------------------------------------------------------------------

    @Test
    void cacheReuse_secondCallHitsCache() {
        byte[] png = TestImages.toPng(TestImages.rgbImage(60, 60));
        SourceImage source = service.upload(png);
        DerivativeParams p = params(20, 20, DerivativeParams.FitMode.CONTAIN, ImageFormat.JPEG, 0.8f);
        service.getOrCreateDerivative(source.imageId(), p);
        Map<String, Object> stats1 = service.stats();
        service.getOrCreateDerivative(source.imageId(), p);
        Map<String, Object> stats2 = service.stats();
        assertEquals(1L, stats2.get("cacheHits"));
        assertEquals(stats1.get("cacheMisses"), stats2.get("cacheMisses"));
    }

    @Test
    void cacheCorruption_recomputesAndConsistent() throws Exception {
        byte[] png = TestImages.toPng(TestImages.rgbImage(70, 70));
        SourceImage source = service.upload(png);
        DerivativeParams p = params(30, 30, DerivativeParams.FitMode.CONTAIN, ImageFormat.JPEG, 0.8f);
        StoredDerivative d1 = service.getOrCreateDerivative(source.imageId(), p);
        byte[] expected = service.readDerivative(d1.derivativeId());

        // 损坏产物文件
        Path bin = d1.path();
        byte[] content = Files.readAllBytes(bin);
        content[0] = (byte) (content[0] ^ 0xFF);
        Files.write(bin, content);

        // 损坏产物不得被当成有效结果
        byte[] recomputed = service.readDerivative(
                service.getOrCreateDerivative(source.imageId(), p).derivativeId());
        assertArrayEquals(expected, recomputed, "缓存损坏后重新计算结果必须与原结果一致");
    }

    @Test
    void incompleteTempFilesNeverPublished() {
        byte[] png = TestImages.toPng(TestImages.rgbImage(60, 60));
        SourceImage source = service.upload(png);
        service.getOrCreateDerivative(source.imageId(),
                params(20, 20, DerivativeParams.FitMode.CONTAIN, ImageFormat.JPEG, 0.8f));
        try (var paths = Files.walk(tempDir)) {
            long tmpCount = paths.filter(pp -> pp.getFileName().toString().contains(".tmp")).count();
            assertEquals(0, tmpCount, "不得残留临时文件");
        } catch (Exception e) {
            fail(e);
        }
    }

    // ------------------------------------------------------------------
    // 并发与容量
    // ------------------------------------------------------------------

    @Test
    void concurrentDerivatives_allComplete() throws Exception {
        byte[] png = TestImages.toPng(TestImages.rgbImage(200, 200));
        SourceImage source = service.upload(png);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<StoredDerivative>> futures = new ArrayList<>();
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                StoredDerivative d = service.getOrCreateDerivative(source.imageId(),
                        params(50 + idx % 5, 50 + idx % 4, DerivativeParams.FitMode.COVER,
                                ImageFormat.JPEG, 0.8f));
                ok.incrementAndGet();
                return d;
            }));
        }
        for (Future<StoredDerivative> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        assertEquals(20, ok.get());
        pool.shutdownNow();
    }

    @Test
    void capacityExceeded_rejectedWithDistinguishableError() throws Exception {
        props.setWorkerThreads(1);
        props.setQueueCapacity(1);
        props.setTaskTimeoutMillis(30_000);
        service.shutdown();
        service = newService(props);

        byte[] png = TestImages.toPng(TestImages.rgbImage(600, 600));
        SourceImage source = service.upload(png);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            futures.add(pool.submit((Callable<Void>) () -> {
                try {
                    service.getOrCreateDerivative(source.imageId(),
                            params(400 + idx, 400, DerivativeParams.FitMode.COVER, ImageFormat.JPEG, 0.8f));
                } catch (PipelineException e) {
                    if (e.getErrorCode() == ErrorCode.CAPACITY_EXCEEDED) {
                        rejected.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        assertTrue(rejected.get() > 0, "容量打满时必须拒绝并给出 CAPACITY_EXCEEDED");
        assertTrue((long) service.stats().get("rejected") > 0);
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 超时与失败
    // ------------------------------------------------------------------

    @Test
    void timeout_failsWithTimeoutAndRetries() {
        props.setTaskTimeoutMillis(5);
        service.shutdown();
        ProcessingService fastFail = newService(props);
        byte[] png = TestImages.toPng(TestImages.rgbImage(2000, 2000));
        SourceImage source = fastFail.upload(png);
        PipelineException ex = assertThrows(PipelineException.class,
                () -> fastFail.getOrCreateDerivative(source.imageId(),
                        params(1500, 1500, DerivativeParams.FitMode.COVER, ImageFormat.JPEG, 0.8f)));
        assertEquals(ErrorCode.PROCESSING_TIMEOUT, ex.getErrorCode());
        assertNotNull(ex.getImageId());
        assertNotNull(ex.getDerivativeId());
        assertNotNull(ex.getRequestId());
        fastFail.shutdown();

        // 超时后不得残留临时文件
        try (var paths = Files.walk(tempDir)) {
            long tmpCount = paths.filter(pp -> pp.getFileName().toString().contains(".tmp")).count();
            assertEquals(0, tmpCount, "超时取消不得残留临时文件");
        } catch (Exception e) {
            fail(e);
        }

        // 恢复正常超时后重试成功
        props.setTaskTimeoutMillis(30_000);
        service = newService(props);
        StoredDerivative d = service.getOrCreateDerivative(source.imageId(),
                params(1500, 1500, DerivativeParams.FitMode.COVER, ImageFormat.JPEG, 0.8f));
        assertNotNull(d);
    }

    @Test
    void failureContext_carriesImageAndRequest() {
        PipelineException ex = assertThrows(PipelineException.class,
                () -> service.getOrCreateDerivative("a".repeat(64),
                        params(10, 10, DerivativeParams.FitMode.CONTAIN, ImageFormat.JPEG, 0.8f)));
        assertEquals(ErrorCode.IMAGE_NOT_FOUND, ex.getErrorCode());
        assertNotNull(ex.getImageId());
        assertNotNull(ex.getRequestId());
    }

    // ------------------------------------------------------------------
    // 一致性读取
    // ------------------------------------------------------------------

    @Test
    void consistentRead_downloadMatchesChecksum() throws Exception {
        byte[] png = TestImages.toPng(TestImages.rgbImage(90, 90));
        SourceImage source = service.upload(png);
        DerivativeParams p = params(40, 40, DerivativeParams.FitMode.CONTAIN, ImageFormat.PNG, 1.0f);
        StoredDerivative d = service.getOrCreateDerivative(source.imageId(), p);
        byte[] bytes = service.readDerivative(d.derivativeId());
        // 下载内容与磁盘产物逐字节一致（一致读取）
        assertArrayEquals(bytes, Files.readAllBytes(d.path()));
        assertTrue(bytes.length > 0);
        // 历史派生结果按原参数可取回
        StoredDerivative again = service.getOrCreateDerivative(source.imageId(), p);
        assertEquals(d.derivativeId(), again.derivativeId());
        assertArrayEquals(bytes, service.readDerivative(again.derivativeId()));
    }

    // ------------------------------------------------------------------
    // 兼容性校验
    // ------------------------------------------------------------------

    @Test
    void validation_rejectsCorruptUpload() {
        PipelineException ex = assertThrows(PipelineException.class,
                () -> service.upload(new byte[]{1, 2, 3, 4, 5}));
        assertEquals(ErrorCode.DECODE_FAILED, ex.getErrorCode());
    }

    @Test
    void validation_rejectsInvalidParams() {
        byte[] png = TestImages.toPng(TestImages.rgbImage(20, 20));
        SourceImage source = service.upload(png);
        PipelineException ex = assertThrows(PipelineException.class,
                () -> service.getOrCreateDerivative(source.imageId(),
                        params(0, 10, DerivativeParams.FitMode.CONTAIN, ImageFormat.JPEG, 0.8f)));
        assertEquals(ErrorCode.INVALID_PARAMETER, ex.getErrorCode());

        PipelineException ex2 = assertThrows(PipelineException.class,
                () -> service.getOrCreateDerivative(source.imageId(),
                        params(99999, 10, DerivativeParams.FitMode.CONTAIN, ImageFormat.JPEG, 0.8f)));
        assertEquals(ErrorCode.INVALID_PARAMETER, ex2.getErrorCode());

        PipelineException ex3 = assertThrows(PipelineException.class,
                () -> service.getOrCreateDerivative(source.imageId(),
                        params(10, 10, DerivativeParams.FitMode.CONTAIN, ImageFormat.JPEG, 5.0f)));
        assertEquals(ErrorCode.INVALID_PARAMETER, ex3.getErrorCode());
    }

    @Test
    void validation_rejectsOversizedSource() {
        props.setMaxSourceDimension(100);
        service.shutdown();
        service = newService(props);
        byte[] png = TestImages.toPng(TestImages.rgbImage(200, 200));
        PipelineException ex = assertThrows(PipelineException.class, () -> service.upload(png));
        assertEquals(ErrorCode.SOURCE_TOO_LARGE, ex.getErrorCode());
    }

    @Test
    void validation_rejectsOversizedUpload() {
        props.setMaxUploadBytes(100);
        service.shutdown();
        service = newService(props);
        byte[] png = TestImages.toPng(TestImages.rgbImage(50, 50));
        assertTrue(png.length > 100);
        PipelineException ex = assertThrows(PipelineException.class, () -> service.upload(png));
        assertEquals(ErrorCode.SOURCE_TOO_LARGE, ex.getErrorCode());
    }

    private void assertColorClose(java.awt.Color expected, java.awt.Color actual, int tolerance) {
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) <= tolerance,
                "R 通道: expected=" + expected.getRed() + ", actual=" + actual.getRed());
        assertTrue(Math.abs(expected.getGreen() - actual.getGreen()) <= tolerance,
                "G 通道: expected=" + expected.getGreen() + ", actual=" + actual.getGreen());
        assertTrue(Math.abs(expected.getBlue() - actual.getBlue()) <= tolerance,
                "B 通道: expected=" + expected.getBlue() + ", actual=" + actual.getBlue());
    }

    // ------------------------------------------------------------------
    // 方向纠正（精确像素映射）
    // ------------------------------------------------------------------

    @Test
    void orientationCorrection_exactCornerMapping() {
        // 4x3 图，四角不同颜色
        int w = 4, h = 3;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int red = 0xFFFF0000;
        int green = 0xFF00FF00;
        int blue = 0xFF0000FF;
        int yellow = 0xFFFFFF00;
        img.setRGB(0, 0, red);
        img.setRGB(w - 1, 0, green);
        img.setRGB(0, h - 1, blue);
        img.setRGB(w - 1, h - 1, yellow);

        ImageNormalizer normalizer = new ImageNormalizer(
                new com.github.huchangjun118.imagederivativepipelinejava.image.ExifOrientationExtractor());

        // 方向 6：顺时针旋转 90，dest 3x4
        BufferedImage o6 = normalizer.applyOrientation(img, 6);
        assertEquals(3, o6.getWidth());
        assertEquals(4, o6.getHeight());
        assertEquals(blue, o6.getRGB(0, 0), "方向6: 左上应为蓝色");
        assertEquals(red, o6.getRGB(2, 0), "方向6: 右上应为红色");
        assertEquals(yellow, o6.getRGB(0, 3), "方向6: 左下应为黄色");
        assertEquals(green, o6.getRGB(2, 3), "方向6: 右下应为绿色");

        // 方向 3：旋转 180，dest 4x3
        BufferedImage o3 = normalizer.applyOrientation(img, 3);
        assertEquals(4, o3.getWidth());
        assertEquals(3, o3.getHeight());
        assertEquals(yellow, o3.getRGB(0, 0), "方向3: 左上应为黄色");
        assertEquals(blue, o3.getRGB(3, 0), "方向3: 右上应为蓝色");
        assertEquals(green, o3.getRGB(0, 2), "方向3: 左下应为绿色");
        assertEquals(red, o3.getRGB(3, 2), "方向3: 右下应为红色");

        // 方向 2：水平翻转，dest 4x3
        BufferedImage o2 = normalizer.applyOrientation(img, 2);
        assertEquals(green, o2.getRGB(0, 0), "方向2: 左上应为绿色");
        assertEquals(red, o2.getRGB(3, 0), "方向2: 右上应为红色");

        // 方向 8：逆时针旋转 90，dest 3x4
        BufferedImage o8 = normalizer.applyOrientation(img, 8);
        assertEquals(3, o8.getWidth());
        assertEquals(4, o8.getHeight());
        assertEquals(green, o8.getRGB(0, 0), "方向8: 左上应为绿色");
        assertEquals(yellow, o8.getRGB(2, 0), "方向8: 右上应为黄色");
        assertEquals(red, o8.getRGB(0, 3), "方向8: 左下应为红色");
        assertEquals(blue, o8.getRGB(2, 3), "方向8: 右下应为蓝色");
    }

    // ------------------------------------------------------------------
    // 缩放模式与 alpha
    // ------------------------------------------------------------------

    @Test
    void fitModes_produceExpectedDimensions() throws Exception {
        byte[] png = TestImages.toPng(TestImages.rgbImage(80, 60));
        SourceImage source = service.upload(png);

        byte[] contain = service.readDerivative(service.getOrCreateDerivative(source.imageId(),
                params(40, 40, DerivativeParams.FitMode.CONTAIN, ImageFormat.PNG, 1.0f)).derivativeId());
        BufferedImage containImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(contain));
        assertEquals(40, containImg.getWidth());
        assertEquals(40, containImg.getHeight());

        byte[] cover = service.readDerivative(service.getOrCreateDerivative(source.imageId(),
                params(40, 40, DerivativeParams.FitMode.COVER, ImageFormat.PNG, 1.0f)).derivativeId());
        BufferedImage coverImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(cover));
        assertEquals(40, coverImg.getWidth());
        assertEquals(40, coverImg.getHeight());

        byte[] stretch = service.readDerivative(service.getOrCreateDerivative(source.imageId(),
                params(40, 40, DerivativeParams.FitMode.STRETCH, ImageFormat.PNG, 1.0f)).derivativeId());
        BufferedImage stretchImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(stretch));
        assertEquals(40, stretchImg.getWidth());
        assertEquals(40, stretchImg.getHeight());
    }

    @Test
    void alpha_preservedForPngAndFlattenedForJpeg() throws Exception {
        BufferedImage withAlpha = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = withAlpha.createGraphics();
        g.setColor(new java.awt.Color(255, 0, 0, 128));
        g.fillRect(0, 0, 40, 40);
        g.dispose();
        byte[] png = TestImages.toPng(withAlpha);
        SourceImage source = service.upload(png);
        assertTrue(source.normalized().getColorModel().hasAlpha());

        byte[] pngDeriv = service.readDerivative(service.getOrCreateDerivative(source.imageId(),
                params(20, 20, DerivativeParams.FitMode.CONTAIN, ImageFormat.PNG, 1.0f)).derivativeId());
        BufferedImage pngOut = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pngDeriv));
        assertTrue(pngOut.getColorModel().hasAlpha(), "PNG 派生应保留 alpha 通道");

        byte[] jpegDeriv = service.readDerivative(service.getOrCreateDerivative(source.imageId(),
                params(20, 20, DerivativeParams.FitMode.CONTAIN, ImageFormat.JPEG, 0.8f)).derivativeId());
        BufferedImage jpegOut = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpegDeriv));
        assertFalse(jpegOut.getColorModel().hasAlpha(), "JPEG 派生应压平 alpha（白底）");
    }

    @Test
    void cacheTtl_expiredResultRecomputes() throws Exception {
        props.setCacheTtlMillis(1);
        service.shutdown();
        service = newService(props);
        byte[] png = TestImages.toPng(TestImages.rgbImage(50, 50));
        SourceImage source = service.upload(png);
        DerivativeParams p = params(20, 20, DerivativeParams.FitMode.CONTAIN, ImageFormat.JPEG, 0.8f);
        service.getOrCreateDerivative(source.imageId(), p);
        Thread.sleep(5);
        service.getOrCreateDerivative(source.imageId(), p);
        assertEquals(2L, service.stats().get("cacheMisses"), "过期后应重新计算");
        assertEquals(0L, service.stats().get("cacheHits"));
    }
}
