package com.github.huchangjun118.imagederivativepipelinejava.tests;

import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeParams;
import com.github.huchangjun118.imagederivativepipelinejava.derivative.ImageProcessor;
import org.junit.jupiter.api.Test;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    @Test
    void exifOrientationIsApplied() throws IOException {
        // 32x16 图：上半纯红、下半纯蓝，纯色块避免 JPEG 色度抽样干扰方向判定
        BufferedImage src = new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 32; x++) {
                src.setRGB(x, y, y < 8 ? 0xFF0000 : 0x0000FF);
            }
        }
        byte[] jpeg = TestImages.jpegWithExifOrientation(TestImages.toJpeg(src), 6);

        BufferedImage normalized = processor.decodeAndNormalize(jpeg);

        // 方向 6 = 顺时针 90 度：宽高互换
        assertEquals(16, normalized.getWidth());
        assertEquals(32, normalized.getHeight());
        // 旋转后左半应来自原图下半（蓝色系），取远离边界的点
        int left = normalized.getRGB(3, 16) & 0xFFFFFF;
        assertTrue((left & 0xFF) > ((left >> 16) & 0xFF),
                "左半应为蓝色系, got " + Integer.toHexString(left));
        // 旋转后右半应来自原图上半（红色系）
        int right = normalized.getRGB(12, 16) & 0xFFFFFF;
        assertTrue(((right >> 16) & 0xFF) > (right & 0xFF),
                "右半应为红色系, got " + Integer.toHexString(right));
    }

    @Test
    void orientationOneLeavesImageUntouched() throws IOException {
        BufferedImage src = TestImages.gradient(6, 3);
        byte[] jpeg = TestImages.jpegWithExifOrientation(TestImages.toJpeg(src), 1);
        BufferedImage normalized = processor.decodeAndNormalize(jpeg);
        assertEquals(6, normalized.getWidth());
        assertEquals(3, normalized.getHeight());
    }

    @Test
    void sameContentInDifferentColorTypesNormalizesToSamePixels() throws IOException {
        int w = 8, h = 8;
        BufferedImage intRgb = TestImages.gradient(w, h);
        BufferedImage threeBgr = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        threeBgr.getGraphics().drawImage(intRgb, 0, 0, null);

        BufferedImage a = processor.decodeAndNormalize(TestImages.toPng(intRgb));
        BufferedImage b = processor.decodeAndNormalize(TestImages.toPng(threeBgr));

        assertEquals(a.getWidth(), b.getWidth());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertEquals(a.getRGB(x, y), b.getRGB(x, y),
                        "像素 (" + x + "," + y + ") 归一后必须一致");
            }
        }
    }

    @Test
    void linearRgbColorSpaceNormalizesToSrgb() throws IOException {
        // 构造线性 RGB 色彩空间的图，其像素值为目标 sRGB 值的线性化结果
        int w = 16, h = 16;
        BufferedImage srgbRef = TestImages.gradient(w, h);

        ColorSpace linear = ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);
        ComponentColorModel cm = new ComponentColorModel(linear, false, false,
                java.awt.Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        WritableRaster raster = cm.createCompatibleWritableRaster(w, h);
        BufferedImage linearImg = new BufferedImage(cm, raster, false, null);
        byte[] data = ((DataBufferByte) raster.getDataBuffer()).getData();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = srgbRef.getRGB(x, y);
                for (int c = 0; c < 3; c++) {
                    float srgb = ((rgb >> (16 - 8 * c)) & 0xFF) / 255f;
                    double lin = srgb <= 0.04045 ? srgb / 12.92
                            : Math.pow((srgb + 0.055) / 1.055, 2.4);
                    data[(y * w + x) * 3 + c] = (byte) (Math.round(lin * 255) & 0xFF);
                }
            }
        }

        BufferedImage normalized = processor.toSrgb(linearImg);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int expected = srgbRef.getRGB(x, y);
                int actual = normalized.getRGB(x, y);
                for (int c = 0; c < 3; c++) {
                    int shift = 16 - 8 * c;
                    int diff = Math.abs(((expected >> shift) & 0xFF) - ((actual >> shift) & 0xFF));
                    // 8 位线性量化会引入少量误差，但远小于未做色彩转换时的偏差（数十级）
                    assertTrue(diff <= 6, "色彩归一偏差过大: (" + x + "," + y + ") 通道 " + c
                            + " diff=" + diff);
                }
            }
        }
    }

    @Test
    void encodeIsDeterministicAcrossInstances() throws IOException {
        BufferedImage img = TestImages.gradient(32, 24);
        DerivativeParams jpeg = new DerivativeParams(16, 12, DerivativeParams.Fit.FIT, "jpeg", 80);
        DerivativeParams png = new DerivativeParams(16, 12, DerivativeParams.Fit.FIT, "png", 90);

        ImageProcessor another = new ImageProcessor();
        byte[] j1 = processor.encode(processor.resize(img, jpeg), jpeg);
        byte[] j2 = another.encode(another.resize(img, jpeg), jpeg);
        assertArrayEquals(j1, j2, "JPEG 编码必须逐字节一致");

        byte[] p1 = processor.encode(processor.resize(img, png), png);
        byte[] p2 = another.encode(another.resize(img, png), png);
        assertArrayEquals(p1, p2, "PNG 编码必须逐字节一致");
    }

    @Test
    void fullPipelineIsDeterministic() throws IOException {
        byte[] original = TestImages.toJpeg(TestImages.gradient(40, 30));
        DerivativeParams params = new DerivativeParams(20, 15, DerivativeParams.Fit.FIT, "jpeg", 85);

        byte[] r1 = processor.encode(processor.resize(
                processor.decodeAndNormalize(original), params), params);
        byte[] r2 = processor.encode(processor.resize(
                processor.decodeAndNormalize(original), params), params);
        assertArrayEquals(r1, r2, "重复处理必须逐字节一致");
    }

    @Test
    void fitKeepsAspectAndStaysInBox() {
        BufferedImage img = TestImages.gradient(100, 50);
        BufferedImage out = processor.resize(img,
                new DerivativeParams(40, 40, DerivativeParams.Fit.FIT, "jpeg", 85));
        assertTrue(out.getWidth() <= 40 && out.getHeight() <= 40);
        assertEquals(40, out.getWidth());
        assertEquals(20, out.getHeight());
    }

    @Test
    void fillProducesExactTargetSize() {
        BufferedImage img = TestImages.gradient(100, 50);
        BufferedImage out = processor.resize(img,
                new DerivativeParams(40, 40, DerivativeParams.Fit.FILL, "jpeg", 85));
        assertEquals(40, out.getWidth());
        assertEquals(40, out.getHeight());
    }

    @Test
    void pngWithAlphaEncodesDeterministicallyToJpeg() throws IOException {
        BufferedImage argb = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        Arrays.fill(new int[1], 0);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                argb.setRGB(x, y, ((x * 25) << 24) | 0x336699);
            }
        }
        DerivativeParams params = new DerivativeParams(10, 10, DerivativeParams.Fit.FIT, "jpeg", 90);
        byte[] a = processor.encode(argb, params);
        byte[] b = processor.encode(argb, params);
        assertArrayEquals(a, b);
    }
}
