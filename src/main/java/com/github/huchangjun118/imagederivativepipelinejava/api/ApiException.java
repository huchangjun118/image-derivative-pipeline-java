package com.github.huchangjun118.imagederivativepipelinejava.api;

/**
 * 业务异常：携带 HTTP 状态码与可区分的错误码，便于调用方定位原因。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException payloadTooLarge(String code, String message) {
        return new ApiException(413, code, message);
    }

    public static ApiException unsupportedMediaType(String code, String message) {
        return new ApiException(415, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }

    public static ApiException tooManyRequests(String code, String message) {
        return new ApiException(429, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }
}
