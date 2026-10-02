package com.github.huchangjun118.imagederivativepipelinejava.web;

import com.github.huchangjun118.imagederivativepipelinejava.error.ErrorCode;
import com.github.huchangjun118.imagederivativepipelinejava.error.PipelineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;

/**
 * 统一异常映射：错误码 -> HTTP 状态码 + 可解释的错误体。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PipelineException.class)
    public ResponseEntity<ApiError> handlePipeline(PipelineException ex) {
        ErrorCode code = ex.getErrorCode();
        if (code.httpStatus() >= 500) {
            log.error("流水线错误: code={}, imageId={}, derivativeId={}, requestId={}",
                    code, ex.getImageId(), ex.getDerivativeId(), ex.getRequestId(), ex);
        } else {
            log.warn("流水线错误: code={}, message={}, imageId={}, derivativeId={}, requestId={}",
                    code, ex.getMessage(), ex.getImageId(), ex.getDerivativeId(), ex.getRequestId());
        }
        HttpStatus status = HttpStatus.valueOf(code.httpStatus());
        ApiError body = new ApiError(
                code.name(),
                ex.getMessage() != null ? ex.getMessage() : code.defaultMessage(),
                ex.getImageId(),
                ex.getDerivativeId(),
                ex.getRequestId(),
                Instant.now());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadLimit(MaxUploadSizeExceededException ex) {
        ApiError body = new ApiError(
                ErrorCode.SOURCE_TOO_LARGE.name(),
                "上传文件超过大小上限",
                null, null, null, Instant.now());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        log.error("未预期的服务端错误", ex);
        ApiError body = new ApiError(
                ErrorCode.PROCESSING_FAILED.name(),
                "服务内部错误: " + ex.getMessage(),
                null, null, null, Instant.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
