package com.github.huchangjun118.imagederivativepipelinejava.image;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 从图片字节中解析 EXIF 方向标签（0x0112）。
 * 支持 JPEG APP1/EXIF 段与 PNG eXIf 块；无方向信息时返回 1（正常方向）。
 */
@Component
public class ExifOrientationExtractor {

    private static final int ORIENTATION_TAG = 0x0112;
    private static final int TYPE_SHORT = 3;

    /**
     * @return EXIF 方向值（1-8），无方向信息或解析失败时返回 1。
     */
    public int extractOrientation(byte[] data) {
        if (data == null || data.length < 4) {
            return 1;
        }
        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) {
            return extractJpegOrientation(data);
        }
        // PNG 签名 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && (data[2] & 0xFF) == 0x4E && (data[3] & 0xFF) == 0x47) {
            return extractPngOrientation(data);
        }
        return 1;
    }

    private int extractJpegOrientation(byte[] data) {
        int pos = 2; // SOI 之后
        while (pos + 4 <= data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                return 1;
            }
            // 跳过填充的 0xFF
            while (pos < data.length && (data[pos] & 0xFF) == 0xFF) {
                pos++;
            }
            if (pos >= data.length) {
                return 1;
            }
            int marker = data[pos] & 0xFF;
            pos++;
            // 无长度段：SOI/EOI/RSTn
            if (marker == 0xD8 || marker == 0xD9 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            if (pos + 2 > data.length) {
                return 1;
            }
            int length = readUnsignedShortBE(data, pos);
            if (length < 2 || pos + length > data.length) {
                return 1;
            }
            if (marker == 0xE1 && length > 6) {
                int payloadStart = pos + 2;
                if (startsWithExif(data, payloadStart, length - 2)) {
                    int tiffStart = payloadStart + 6;
                    Integer orientation = parseTiffOrientation(data, tiffStart, pos + length);
                    if (orientation != null) {
                        return orientation;
                    }
                }
            }
            pos += length;
        }
        return 1;
    }

    private int extractPngOrientation(byte[] data) {
        int pos = 8; // PNG 签名之后
        while (pos + 8 <= data.length) {
            int chunkLength = readIntBE(data, pos);
            int typeStart = pos + 4;
            if (typeStart + 4 > data.length) {
                return 1;
            }
            String type = new String(data, typeStart, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int dataStart = typeStart + 4;
            int dataEnd = dataStart + chunkLength;
            if (dataEnd + 4 > data.length) {
                return 1;
            }
            if ("eXIf".equals(type)) {
                int tiffStart = dataStart;
                // 部分实现的 eXIf 数据带 "Exif\0\0" 前缀
                if (startsWithExif(data, dataStart, chunkLength)) {
                    tiffStart = dataStart + 6;
                }
                Integer orientation = parseTiffOrientation(data, tiffStart, dataEnd);
                if (orientation != null) {
                    return orientation;
                }
            }
            pos = dataEnd + 4; // 跳过 CRC
        }
        return 1;
    }

    private boolean startsWithExif(byte[] data, int start, int available) {
        if (available < 6) {
            return false;
        }
        return (data[start] & 0xFF) == 'E'
                && (data[start + 1] & 0xFF) == 'x'
                && (data[start + 2] & 0xFF) == 'i'
                && (data[start + 3] & 0xFF) == 'f'
                && (data[start + 4] & 0xFF) == 0
                && (data[start + 5] & 0xFF) == 0;
    }

    /**
     * 解析 TIFF 结构中的 IFD0 方向标签。
     *
     * @return 方向值；找不到时返回 null。
     */
    private Integer parseTiffOrientation(byte[] data, int tiffStart, int limit) {
        if (tiffStart + 8 > limit) {
            return null;
        }
        int b0 = data[tiffStart] & 0xFF;
        int b1 = data[tiffStart + 1] & 0xFF;
        ByteOrder order;
        if (b0 == 'I' && b1 == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (b0 == 'M' && b1 == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            return null;
        }
        // wrap(data) 的绝对索引相对于 data 起点，所有偏移需加 tiffStart
        ByteBuffer buf = ByteBuffer.wrap(data).order(order);
        int ifdOffset = buf.getInt(tiffStart + 4);
        if (ifdOffset < 0 || tiffStart + ifdOffset + 2 > limit) {
            return null;
        }
        int count = buf.getChar(tiffStart + ifdOffset);
        int entriesStart = tiffStart + ifdOffset + 2;
        for (int i = 0; i < count; i++) {
            int entryOffset = entriesStart + i * 12;
            if (entryOffset + 12 > limit) {
                return null;
            }
            int tag = buf.getChar(entryOffset);
            if (tag == ORIENTATION_TAG) {
                int type = buf.getChar(entryOffset + 2);
                if (type != TYPE_SHORT) {
                    return null;
                }
                // SHORT 内联在 value 字段前 2 字节，getChar 按字节序读取
                int orientation = buf.getChar(entryOffset + 8);
                if (orientation >= 1 && orientation <= 8) {
                    return orientation;
                }
                return null;
            }
        }
        return null;
    }

    private int readUnsignedShortBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
