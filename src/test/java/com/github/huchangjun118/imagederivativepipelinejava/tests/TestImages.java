package com.github.huchangjun118.imagederivativepipelinejava.tests;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 测试用图片生成工具。
 */
final class TestImages {

    private TestImages() {
    }

    static BufferedImage rgbImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(200, 100, 50));
        g.fillRect(0, 0, w / 2, h / 2);
        g.setColor(new Color(50, 100, 200));
        g.fillRect(w / 2, h / 2, w / 2, h / 2);
        g.setColor(Color.BLACK);
        g.drawRect(0, 0, w - 1, h - 1);
        g.dispose();
        return img;
    }

    static BufferedImage grayImage(int w, int h, int gray) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(gray, gray, gray));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    static byte[] toPng(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] toJpeg(BufferedImage img, float quality) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            var writers = ImageIO.getImageWritersByFormatName("jpeg");
            var writer = writers.next();
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            try (var ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            }
            writer.dispose();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 在 JPEG 字节中插入 EXIF APP1 段，写入指定方向值。
     */
    static byte[] jpegWithOrientation(BufferedImage img, int orientation) {
        byte[] jpeg = toJpeg(img, 0.9f);
        byte[] app1 = buildExifApp1(orientation);
        ByteBuffer out = ByteBuffer.allocate(jpeg.length + app1.length);
        out.put(jpeg, 0, 2); // SOI
        out.put(app1);
        out.put(jpeg, 2, jpeg.length - 2);
        return out.array();
    }

    private static byte[] buildExifApp1(int orientation) {
        // TIFF 头：II + 0x002A + IFD0 偏移 8
        ByteBuffer tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I');
        tiff.put((byte) 'I');
        tiff.putShort((short) 0x002A);
        tiff.putInt(8); // IFD0 偏移
        // IFD0
        tiff.putShort((short) 1); // 条目数
        // 条目：tag=0x0112, type=SHORT(3), count=1, value
        tiff.putShort((short) 0x0112);
        tiff.putShort((short) 3);
        tiff.putInt(1);
        tiff.putShort((short) orientation);
        tiff.putShort((short) 0); // 填充
        tiff.putInt(0); // 下一个 IFD 偏移
        byte[] tiffBytes = tiff.array();

        byte[] exifPrefix = new byte[]{'E', 'x', 'i', 'f', 0, 0};
        ByteBuffer payload = ByteBuffer.allocate(exifPrefix.length + tiffBytes.length);
        payload.put(exifPrefix);
        payload.put(tiffBytes);

        ByteBuffer app1 = ByteBuffer.allocate(2 + 2 + payload.capacity());
        app1.put((byte) 0xFF);
        app1.put((byte) 0xE1);
        app1.putShort((short) (payload.capacity() + 2));
        app1.put(payload.array());
        return app1.array();
    }
}
