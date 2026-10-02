package com.github.huchangjun118.imagederivativepipelinejava.image;

import java.awt.image.BufferedImage;

/**
 * 解码后的原图。normalized 为已完成方向纠正与色彩归一的 sRGB 图像。
 */
public record SourceImage(
        String imageId,
        ImageFormat format,
        int width,
        int height,
        int orientation,
        BufferedImage normalized,
        long sizeBytes
) {
}
