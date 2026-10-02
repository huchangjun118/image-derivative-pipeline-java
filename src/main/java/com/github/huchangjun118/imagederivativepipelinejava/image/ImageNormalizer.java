package com.github.huchangjun118.imagederivativepipelinejava.image;

import com.github.huchangjun118.imagederivativepipelinejava.error.ErrorCode;
import com.github.huchangjun118.imagederivativepipelinejava.error.PipelineException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 图片解码、方向纠正与色彩归一。
 * <p>
 * 解码阶段在真正读取像素前先校验格式与尺寸上限；方向纠正按 EXIF 方向标签做
 * 整数映射（旋转/翻转），不引入插值误差；色彩归一将任意色彩空间（灰度、
 * 内嵌 ICC、索引色等）统一转换到 sRGB，保证不同色彩空间的同一张图归一后
 * 像素一致。
 */
@Component
public class ImageNormalizer {

    private final ExifOrientationExtractor orientationExtractor;

    public ImageNormalizer(ExifOrientationExtractor orientationExtractor) {
        this.orientationExtractor = orientationExtractor;
    }

    /**
     * 解码原图并完成方向纠正与色彩归一，输出 sRGB 图像。
     *
     * @throws PipelineException 格式不支持、解码失败或尺寸超限时抛出。
     */
    public SourceImage decodeAndNormalize(String imageId, byte[] bytes,
                                          int maxSourceDimension, long maxSourcePixels) {
        if (bytes == null || bytes.length == 0) {
            throw new PipelineException(ErrorCode.DECODE_FAILED, "上传内容为空", null, imageId, null, null);
        }
        int orientation = orientationExtractor.extractOrientation(bytes);

        ImageFormat format;
        BufferedImage decoded;
        int width;
        int height;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                // 无法识别的内容按解码失败处理（格式损坏或数据截断）
                throw new PipelineException(ErrorCode.DECODE_FAILED,
                        "无法识别的图片格式或图片已损坏", null, imageId, null, null);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                String formatName = reader.getFormatName();
                format = mapFormat(formatName);
                if (format == null) {
                    throw new PipelineException(ErrorCode.UNSUPPORTED_FORMAT,
                            "不支持的图片格式: " + formatName, null, imageId, null, null);
                }
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                validateDimensions(width, height, maxSourceDimension, maxSourcePixels, imageId);
                try {
                    decoded = reader.read(0);
                } catch (IOException | RuntimeException e) {
                    throw new PipelineException(ErrorCode.DECODE_FAILED,
                            "图片解码失败: " + e.getMessage(), e, imageId, null, null);
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new PipelineException(ErrorCode.DECODE_FAILED,
                    "图片解码失败: " + e.getMessage(), e, imageId, null, null);
        }

        if (decoded == null) {
            throw new PipelineException(ErrorCode.DECODE_FAILED, "图片解码结果为空", null, imageId, null, null);
        }

        BufferedImage oriented = applyOrientation(decoded, orientation);
        BufferedImage normalized = normalizeColor(oriented);
        return new SourceImage(imageId, format, normalized.getWidth(), normalized.getHeight(),
                orientation, normalized, bytes.length);
    }

    private ImageFormat mapFormat(String formatName) {
        if (formatName == null) {
            return null;
        }
        switch (formatName.toUpperCase()) {
            case "JPEG":
            case "JPG":
                return ImageFormat.JPEG;
            case "PNG":
                return ImageFormat.PNG;
            default:
                return null;
        }
    }

    private void validateDimensions(int width, int height, int maxDimension, long maxPixels, String imageId) {
        if (width <= 0 || height <= 0) {
            throw new PipelineException(ErrorCode.DECODE_FAILED,
                    "图片尺寸非法: " + width + "x" + height, null, imageId, null, null);
        }
        if (width > maxDimension || height > maxDimension) {
            throw new PipelineException(ErrorCode.SOURCE_TOO_LARGE,
                    "图片边长 " + width + "x" + height + " 超过上限 " + maxDimension,
                    null, imageId, null, null);
        }
        if ((long) width * (long) height > maxPixels) {
            throw new PipelineException(ErrorCode.SOURCE_TOO_LARGE,
                    "图片总像素 " + ((long) width * height) + " 超过上限 " + maxPixels,
                    null, imageId, null, null);
        }
    }

    /**
     * 按 EXIF 方向值对图像做旋转/翻转纠正。方向 1 或非法值原样返回。
     */
    public BufferedImage applyOrientation(BufferedImage src, int orientation) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (orientation <= 1 || orientation >= 9) {
            return src;
        }
        boolean swap = orientation == 5 || orientation == 6 || orientation == 7 || orientation == 8;
        int destW = swap ? h : w;
        int destH = swap ? w : h;

        // 直接像素拷贝，避免 AffineTransform + drawImage 的亚像素中心偏移
        int[] srcPixels = new int[w * h];
        src.getRGB(0, 0, w, h, srcPixels, 0, w);
        int[] destPixels = new int[destW * destH];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx;
                int dy;
                switch (orientation) {
                    case 2: // 水平翻转: (x,y) -> (w-1-x, y)
                        dx = w - 1 - x;
                        dy = y;
                        break;
                    case 3: // 旋转 180: (x,y) -> (w-1-x, h-1-y)
                        dx = w - 1 - x;
                        dy = h - 1 - y;
                        break;
                    case 4: // 垂直翻转: (x,y) -> (x, h-1-y)
                        dx = x;
                        dy = h - 1 - y;
                        break;
                    case 5: // 主对角线翻转（转置）: (x,y) -> (y, x)
                        dx = y;
                        dy = x;
                        break;
                    case 6: // 顺时针旋转 90: (x,y) -> (h-1-y, x)
                        dx = h - 1 - y;
                        dy = x;
                        break;
                    case 7: // 反对角线翻转: (x,y) -> (h-1-y, w-1-x)
                        dx = h - 1 - y;
                        dy = w - 1 - x;
                        break;
                    case 8: // 逆时针旋转 90: (x,y) -> (y, w-1-x)
                        dx = y;
                        dy = w - 1 - x;
                        break;
                    default:
                        dx = x;
                        dy = y;
                        break;
                }
                destPixels[dy * destW + dx] = srcPixels[y * w + x];
            }
        }
        boolean hasAlpha = src.getColorModel().hasAlpha();
        BufferedImage dest = new BufferedImage(destW, destH,
                hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        dest.setRGB(0, 0, destW, destH, destPixels, 0, destW);
        return dest;
    }

    /**
     * 将任意色彩空间的图像归一到 sRGB：对非 sRGB 色彩空间显式执行
     * ColorConvertOp 转换（灰度、线性 RGB、内嵌 ICC 等）；索引色等特殊
     * 色彩模型回退到绘制转换，保证输出一致的 sRGB 像素。
     */
    public BufferedImage normalizeColor(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean hasAlpha = src.getColorModel().hasAlpha();
        int targetType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        ColorSpace srgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        BufferedImage out = new BufferedImage(w, h, targetType);

        boolean converted = false;
        if (!src.getColorModel().getColorSpace().isCS_sRGB()) {
            try {
                java.awt.image.ColorConvertOp op = new java.awt.image.ColorConvertOp(srgb, null);
                op.filter(src, out);
                converted = true;
            } catch (RuntimeException ignore) {
                // 某些特殊色彩模型（如索引色）无法直接转换，回退到绘制
            }
        }
        if (!converted) {
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            if (!hasAlpha) {
                // 不透明图像铺白底，避免源图带透明像素时边缘发黑
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
            }
            g.drawImage(src, 0, 0, null);
            g.dispose();
        }
        return out;
    }
}
