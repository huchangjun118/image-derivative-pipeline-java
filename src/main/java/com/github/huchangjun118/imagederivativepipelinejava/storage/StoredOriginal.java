package com.github.huchangjun118.imagederivativepipelinejava.storage;

/**
 * 已入库的原图描述。
 *
 * @param id          原图标识（内容哈希）
 * @param contentHash 原图字节内容哈希（hex）
 * @param sizeBytes   原图字节数
 * @param width       归一后宽度
 * @param height      归一后高度
 */
public record StoredOriginal(String id, String contentHash, long sizeBytes, int width, int height) {
}
