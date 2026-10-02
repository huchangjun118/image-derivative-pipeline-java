package com.github.huchangjun118.imagederivativepipelinejava.derivative;

import java.util.Locale;
import java.util.Objects;

/**
 * 派生请求参数：目标尺寸、格式与编码质量。
 * 派生结果只由原图内容与该参数组合决定。
 */
public final class DerivativeParams {

    public enum Fit {
        /** 等比缩放至不超出目标框。 */
        FIT,
        /** 等比缩放并居中裁剪至恰好填满目标框。 */
        FILL
    }

    private final int width;
    private final int height;
    private final Fit fit;
    private final String format;
    private final int quality;

    public DerivativeParams(int width, int height, Fit fit, String format, int quality) {
        this.width = width;
        this.height = height;
        this.fit = fit;
        this.format = format.toLowerCase(Locale.ROOT);
        this.quality = quality;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Fit getFit() {
        return fit;
    }

    public String getFormat() {
        return format;
    }

    public int getQuality() {
        return quality;
    }

    /** 用于缓存键的规范化字符串。 */
    public String canonical() {
        return "w" + width + "_h" + height + "_" + fit.name().toLowerCase(Locale.ROOT)
                + "_" + format + "_q" + quality;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DerivativeParams that)) {
            return false;
        }
        return width == that.width && height == that.height && quality == that.quality
                && fit == that.fit && format.equals(that.format);
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, fit, format, quality);
    }

    @Override
    public String toString() {
        return canonical();
    }
}
