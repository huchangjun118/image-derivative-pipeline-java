package com.github.huchangjun118.imagederivativepipelinejava.derivative;

import com.github.huchangjun118.imagederivativepipelinejava.image.ImageFormat;

import java.util.HexFormat;

/**
 * 派生图请求参数。相同参数组合必须产生确定性的派生图标识。
 */
public record DerivativeParams(
        int width,
        int height,
        FitMode fitMode,
        ImageFormat format,
        float quality
) {

    public enum FitMode {
        /** 保持比例完整放入目标尺寸（可能留白）。 */
        CONTAIN,
        /** 保持比例填满目标尺寸（裁剪溢出部分）。 */
        COVER,
        /** 拉伸到目标尺寸（不保持比例）。 */
        STRETCH
    }

    /**
     * 参数规范化为确定性的字符串，用于派生图标识计算。
     */
    public String canonical() {
        return width + "x" + height + ":" + fitMode.name() + ":"
                + format.name() + ":q" + HexFormat.of().toHexDigits(Float.floatToIntBits(quality));
    }
}
