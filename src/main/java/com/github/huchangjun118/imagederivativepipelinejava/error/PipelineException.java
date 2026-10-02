package com.github.huchangjun118.imagederivativepipelinejava.error;

/**
 * 流水线统一异常，携带错误码与定位信息（imageId / derivativeId / requestId）。
 */
public class PipelineException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String imageId;
    private final String derivativeId;
    private final String requestId;

    public PipelineException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null, null, null);
    }

    public PipelineException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, cause, null, null, null);
    }

    public PipelineException(ErrorCode errorCode, String message, Throwable cause,
                             String imageId, String derivativeId, String requestId) {
        super(message, cause);
        this.errorCode = errorCode;
        this.imageId = imageId;
        this.derivativeId = derivativeId;
        this.requestId = requestId;
    }

    public ErrorCode getErrorCode() { return errorCode; }
    public String getImageId() { return imageId; }
    public String getDerivativeId() { return derivativeId; }
    public String getRequestId() { return requestId; }

    public PipelineException withContext(String imageId, String derivativeId, String requestId) {
        return new PipelineException(errorCode, getMessage(), getCause(), imageId, derivativeId, requestId);
    }
}
