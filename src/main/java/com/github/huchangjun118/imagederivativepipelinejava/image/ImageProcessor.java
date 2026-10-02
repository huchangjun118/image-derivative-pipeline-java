package com.github.huchangjun118.imagederivativepipelinejava.image;

import com.github.huchangjun118.imagederivativepipelinejava.derivative.DerivativeParams;
import com.github.huchangjun118.imagederivativepipelinejava.error.ErrorCode;
import com.github.huchangjun118.imagederivativepipelinejava.error.PipelineException;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 派生图处理：按参数缩放并以确定性方式编码。
 * <p>
 * 缩放使用统一的高质量渲染提示；编码时不写入任何元数据，JPEG 质量由参数
 * 显式指定，PNG 使用默认无损压缩，从而保证相同输入逐字节一致。
 */
@Component
public class ImageProcessor {

    /**
     * 按参数生成派生图字节。
     */
    public byte[] process(BufferedImage source, DerivativeParams params) {
        BufferedImage resized = resize(source, params);
        return encode(resized, params);
    }

    private BufferedImage resize(BufferedImage source, DerivativeParams params) {
        int sw = source.getWidth();
        int sh = source.getHeight();
        int tw = params.width();
        int th = params.height();
        boolean keepAlpha = source.getColorModel().hasAlpha() && params.format() == ImageFormat.PNG;
        int imageType = keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

        switch (params.fitMode()) {
            case STRETCH: {
                BufferedImage out = new BufferedImage(tw, th, imageType);
                Graphics2D g = createGraphics(out, keepAlpha);
                g.drawImage(source, 0, 0, tw, th, null);
                g.dispose();
                return out;
            }
            case CONTAIN: {
                double scale = Math.min((double) tw / sw, (double) th / sh);
                int dw = Math.max(1, Math.round(sw * (float) scale));
                int dh = Math.max(1, Math.round(sh * (float) scale));
                BufferedImage out = new BufferedImage(tw, th, imageType);
                Graphics2D g = createGraphics(out, keepAlpha);
                int dx = (tw - dw) / 2;
                int dy = (th - dh) / 2;
                g.drawImage(source, dx, dy, dw, dh, null);
                g.dispose();
                return out;
            }
            case COVER:
            default: {
                double scale = Math.max((double) tw / sw, (double) th / sh);
                int dw = Math.max(tw, Math.round(sw * (float) scale));
                int dh = Math.max(th, Math.round(sh * (float) scale));
                BufferedImage scaled = new BufferedImage(dw, dh, imageType);
                Graphics2D g = createGraphics(scaled, keepAlpha);
                g.drawImage(source, 0, 0, dw, dh, null);
                g.dispose();
                int sx = (dw - tw) / 2;
                int sy = (dh - th) / 2;
                BufferedImage out = new BufferedImage(tw, th, imageType);
                Graphics2D g2 = createGraphics(out, keepAlpha);
                g2.drawImage(scaled, 0, 0, tw, th, sx, sy, sx + tw, sy + th, null);
                g2.dispose();
                return out;
            }
        }
    }

    private Graphics2D createGraphics(BufferedImage out, boolean keepAlpha) {
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        if (!keepAlpha) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
        }
        return g;
    }

    private byte[] encode(BufferedImage image, DerivativeParams params) {
        BufferedImage toEncode = image;
        if (params.format() == ImageFormat.JPEG && image.getColorModel().hasAlpha()) {
            // JPEG 不支持透明，压白底
            BufferedImage flat = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = flat.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
            toEncode = flat;
        }
        String formatName = params.format() == ImageFormat.JPEG ? "jpeg" : "png";
        ImageWriter writer = ImageIO.getImageWritersByFormatName(formatName).next();
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        if (params.format() == ImageFormat.JPEG) {
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionQuality(params.quality());
            writeParam.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            if (ios == null) {
                throw new IOException("无法创建 ImageOutputStream");
            }
            writer.setOutput(ios);
            writer.write(null, new IIOImage(toEncode, null, null), writeParam);
        } catch (IOException e) {
            throw new PipelineException(ErrorCode.PROCESSING_FAILED,
                    "派生图编码失败: " + e.getMessage(), e);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
