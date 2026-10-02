package com.github.huchangjun118.imagederivativepipelinejava.error;

/**
 * 流水线错误码，每个错误码映射到确定的 HTTP 状态码，便于调用方区分失败原因。
 */
public enum ErrorCode {

    /** 上传内容无法解码（格式损坏或不支持的编码）。 */
    DECODE_FAILED(400, "图片无法解码或已损坏"),

    /** 上传格式不受支持。 */
    UNSUPPORTED_FORMAT(400, "不支持的图片格式"),

    /** 原图尺寸超过允许范围。 */
    SOURCE_TOO_LARGE(400, "原图尺寸超出允许范围"),

    /** 请求参数非法。 */
    INVALID_PARAMETER(400, "请求参数非法"),

    /** 原图不存在。 */
    IMAGE_NOT_FOUND(404, "原图不存在"),

    /** 派生结果不存在。 */
    DERIVATIVE_NOT_FOUND(404, "派生结果不存在"),

    /** 处理队列已满，系统容量达到上限。 */
    CAPACITY_EXCEEDED(503, "处理容量已达上限，请稍后重试"),

    /** 处理超时。 */
    PROCESSING_TIMEOUT(504, "处理超时"),

    /** 处理被取消。 */
    PROCESSING_CANCELLED(500, "处理已取消"),

    /** 处理过程发生内部错误。 */
    PROCESSING_FAILED(500, "处理失败");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() { return httpStatus; }
    public String defaultMessage() { return defaultMessage; }
}
