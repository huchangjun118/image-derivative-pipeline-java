package com.github.huchangjun118.imagederivativepipelinejava.image;

/**
 * 支持的图片格式。
 */
public enum ImageFormat {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png");

    private final String mimeType;
    private final String extension;

    ImageFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() { return mimeType; }
    public String extension() { return extension; }

    public static ImageFormat fromMimeType(String mime) {
        if (mime == null) return null;
        String m = mime.toLowerCase();
        for (ImageFormat f : values()) {
            if (f.mimeType.equals(m)) return f;
        }
        return null;
    }
}
