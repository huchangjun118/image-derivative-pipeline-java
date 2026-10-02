package com.github.huchangjun118.imagederivativepipelinejava.derivative;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * 纯图像处理：解码、方向纠正、色彩归一、缩放与确定性编码。
 * 本类不依赖任何 IO 与外部服务，所有方法对相同输入产生相同输出。
 */
public class ImageProcessor {

    /** 探测图片尺寸与格式，只读取头部，不解码像素。 */
    public ProbeResult probe(byte[] data) throws IOException {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("无法识别的图片格式");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new ProbeResult(reader.getWidth(0), reader.getHeight(0),
                        reader.getFormatName().toLowerCase(Locale.ROOT));
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * 解码上传字节并归一：按 EXIF 方向摆正，统一到 sRGB 色彩空间。
     * 相同原图（即使色彩空间不同）归一后得到一致像素。
     */
    public BufferedImage decodeAndNormalize(byte[] data) throws IOException {
        int orientation = readExifOrientation(data);
        BufferedImage decoded = decode(data);
        BufferedImage srgb = toSrgb(decoded);
        return applyOrientation(srgb, orientation);
    }

    private BufferedImage decode(byte[] data) throws IOException {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("无法识别的图片格式");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new IOException("图片解码失败");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /** 读取 EXIF 方向（1-8），无元信息或解析失败时返回 1（不旋转）。 */
    private int readExifOrientation(byte[] data) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignored) {
            // 无 EXIF 或解析失败：按不旋转处理
        }
        return 1;
    }

    /**
     * 把解码图像转换到 sRGB 色彩空间的标准类型（RGB 或 ARGB）。
     * 对非 sRGB（如 CMYK、带 ICC 配置文件）的源使用其内嵌 profile 做色彩转换，
     * 保证不同色彩空间的同一张图归一后像素一致。
     */
    public BufferedImage toSrgb(BufferedImage src) {
        ColorModel cm = src.getColorModel();
        boolean hasAlpha = cm.hasAlpha();
        int targetType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        ColorSpace cs = cm.getColorSpace();

        BufferedImage converted;
        if (cs.isCS_sRGB()) {
            // 已是 sRGB：仅做类型标准化
            converted = new BufferedImage(src.getWidth(), src.getHeight(), targetType);
            Graphics2D g = converted.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
        } else {
            // 非 sRGB：用源图像自带的色彩空间做转换
            ColorConvertOp op = new ColorConvertOp(cs, ColorSpace.getInstance(ColorSpace.CS_sRGB),
                    standardHints());
            BufferedImage tmp = new BufferedImage(src.getWidth(), src.getHeight(), targetType);
            // ColorConvertOp 要求源与目标兼容；先拷贝到与源色彩模型一致的结构再转换
            if (src.getType() == BufferedImage.TYPE_CUSTOM || !isCompatibleForOp(src)) {
                BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), targetType);
                Graphics2D g = copy.createGraphics();
                g.drawImage(src, 0, 0, null);
                g.dispose();
                converted = op.filter(copy, tmp);
            } else {
                converted = op.filter(src, tmp);
            }
        }
        return converted;
    }

    private boolean isCompatibleForOp(BufferedImage src) {
        int type = src.getType();
        return type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_3BYTE_BGR || type == BufferedImage.TYPE_4BYTE_ABGR
                || type == BufferedImage.TYPE_BYTE_GRAY;
    }

    /** 按 EXIF 方向值（1-8）摆正图像。 */
    private BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        int dw = swap ? h : w;
        int dh = swap ? w : h;

        AffineTransform tx = new AffineTransform();
        switch (orientation) {
            case 2 -> { // 水平翻转
                tx.scale(-1, 1);
                tx.translate(-w, 0);
            }
            case 3 -> { // 旋转 180
                tx.translate(w, h);
                tx.rotate(Math.PI);
            }
            case 4 -> { // 垂直翻转
                tx.scale(1, -1);
                tx.translate(0, -h);
            }
            case 5 -> { // 转置
                tx.rotate(Math.PI / 2);
                tx.scale(1, -1);
            }
            case 6 -> { // 顺时针 90
                tx.translate(h, 0);
                tx.rotate(Math.PI / 2);
            }
            case 7 -> { // 转置 + 180
                tx.translate(h, w);
                tx.rotate(Math.PI / 2);
                tx.scale(-1, 1);
            }
            case 8 -> { // 逆时针 90
                tx.translate(0, w);
                tx.rotate(-Math.PI / 2);
            }
            default -> {
                return src;
            }
        }

        BufferedImage dst = new BufferedImage(dw, dh,
                src.getType() == BufferedImage.TYPE_INT_ARGB
                        ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHints(standardHints());
        g.drawImage(src, tx, null);
        g.dispose();
        return dst;
    }

    /**
     * 按目标参数缩放归一后的图像。多步缩放保证大图缩小时的画质与稳定耗时。
     */
    public BufferedImage resize(BufferedImage normalized, DerivativeParams params) {
        int sw = normalized.getWidth();
        int sh = normalized.getHeight();
        int tw;
        int th;
        BufferedImage cropped = normalized;
        if (params.getFit() == DerivativeParams.Fit.FILL) {
            // 等比放大到覆盖目标框后居中裁剪
            double scale = Math.max(params.getWidth() / (double) sw, params.getHeight() / (double) sh);
            int cw = (int) Math.round(sw * scale);
            int ch = (int) Math.round(sh * scale);
            cropped = scaleTo(normalized, cw, ch);
            int x = Math.max(0, (cw - params.getWidth()) / 2);
            int y = Math.max(0, (ch - params.getHeight()) / 2);
            return cropped.getSubimage(x, y,
                    Math.min(params.getWidth(), cropped.getWidth()),
                    Math.min(params.getHeight(), cropped.getHeight()));
        }
        // FIT：等比缩放到不超出目标框
        double scale = Math.min(params.getWidth() / (double) sw, params.getHeight() / (double) sh);
        tw = Math.max(1, (int) Math.round(sw * scale));
        th = Math.max(1, (int) Math.round(sh * scale));
        return scaleTo(normalized, tw, th);
    }

    /** 多步缩放：每次最多缩小一半，保证质量与确定性。 */
    private BufferedImage scaleTo(BufferedImage src, int targetW, int targetH) {
        BufferedImage current = src;
        int cw = src.getWidth();
        int ch = src.getHeight();
        do {
            if (cw > targetW) {
                cw = Math.max(targetW, cw / 2);
            } else {
                cw = targetW;
            }
            if (ch > targetH) {
                ch = Math.max(targetH, ch / 2);
            } else {
                ch = targetH;
            }
            int type = current.getColorModel().hasAlpha()
                    ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage next = new BufferedImage(cw, ch, type);
            Graphics2D g = next.createGraphics();
            g.setRenderingHints(standardHints());
            g.drawImage(current, 0, 0, cw, ch, null);
            g.dispose();
            current = next;
        } while (cw != targetW || ch != targetH);
        return current;
    }

    /**
     * 确定性编码：相同像素与参数必须得到逐字节一致的输出。
     * JPEG 使用固定 writer 与显式质量参数；PNG 为无损编码。
     */
    public byte[] encode(BufferedImage image, DerivativeParams params) throws IOException {
        String format = params.getFormat();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (format) {
            case "jpeg", "jpg" -> {
                BufferedImage rgb = flattenForJpeg(image);
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(params.getQuality() / 100f);
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(rgb, null, null), param);
                } finally {
                    writer.dispose();
                }
            }
            case "png" -> {
                if (!ImageIO.write(image, "png", out)) {
                    throw new IOException("无可用 PNG 编码器");
                }
            }
            default -> throw new IllegalArgumentException("不支持的输出格式: " + format);
        }
        return out.toByteArray();
    }

    /** JPEG 不支持 alpha：把透明像素合成到白色背景上，保证确定性。 */
    private BufferedImage flattenForJpeg(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private RenderingHints standardHints() {
        RenderingHints hints = new RenderingHints(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        hints.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        hints.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        hints.put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        return hints;
    }

    /**
     * 头部探测结果。
     */
    public record ProbeResult(int width, int height, String format) {
    }
}
