package com.github.huchangjun118.imagederivativepipelinejava.tests;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 进程内生成测试图片，不依赖任何外部资源。
 */
final class TestImages {

    private TestImages() {
    }

    /** 生成一张每像素颜色都不同的渐变图（TYPE_INT_RGB）。 */
    static BufferedImage gradient(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255) / Math.max(1, w - 1);
                int g = (y * 255) / Math.max(1, h - 1);
                int b = ((x + y) * 255) / Math.max(1, w + h - 2);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    static byte[] toPng(BufferedImage img) {
        return write(img, "png");
    }

    static byte[] toJpeg(BufferedImage img) {
        return write(img, "jpeg");
    }

    private static byte[] write(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 向 JPEG 注入 EXIF 方向标记（APP1 段），模拟带方向元信息的原图。
     */
    static byte[] jpegWithExifOrientation(byte[] jpeg, int orientation) {
        if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IllegalArgumentException("not a jpeg");
        }
        // TIFF（小端）：II 42 offset=8；IFD0 含一个方向条目
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write('I');
        tiff.write('I');
        tiff.write(42);
        tiff.write(0);
        writeIntLE(tiff, 8);            // IFD0 偏移
        writeShortLE(tiff, 1);          // 条目数
        writeShortLE(tiff, 0x0112);     // Orientation tag
        writeShortLE(tiff, 3);          // SHORT
        writeIntLE(tiff, 1);            // count
        writeShortLE(tiff, orientation);
        writeShortLE(tiff, 0);          // 填充
        writeIntLE(tiff, 0);            // 下一个 IFD
        byte[] tiffBytes = tiff.toByteArray();

        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.write(0xFF);
        app1.write(0xE1);
        int len = 2 + 6 + tiffBytes.length;
        app1.write((len >> 8) & 0xFF);
        app1.write(len & 0xFF);
        app1.write(new byte[]{'E', 'x', 'i', 'f', 0, 0}, 0, 6);
        app1.write(tiffBytes, 0, tiffBytes.length);

        byte[] app1Bytes = app1.toByteArray();
        byte[] result = new byte[jpeg.length + app1Bytes.length];
        System.arraycopy(jpeg, 0, result, 0, 2);                 // SOI
        System.arraycopy(app1Bytes, 0, result, 2, app1Bytes.length);
        System.arraycopy(jpeg, 2, result, 2 + app1Bytes.length, jpeg.length - 2);
        return result;
    }

    private static void writeShortLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}
