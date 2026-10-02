package com.github.huchangjun118.imagederivativepipelinejava.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一异常处理：把业务异常转换为带错误码的响应，便于调用方区分原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e, ServletWebRequest request) {
        if (e.getStatus() >= 500) {
            log.warn("request failed: {} {}", e.getCode(), e.getMessage(), e);
        }
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorResponse(e.getCode(), e.getMessage(), requestId(request)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException e,
                                                          ServletWebRequest request) {
        return ResponseEntity.status(413)
                .body(new ErrorResponse("UPLOAD_TOO_LARGE", "上传大小超过限制",
                        requestId(request)));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e,
                                                            ServletWebRequest request) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("MISSING_PARAM", "缺少请求参数: " + e.getParameterName(),
                        requestId(request)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                            ServletWebRequest request) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_PARAM",
                        "参数 " + e.getName() + " 取值非法: " + e.getValue(), requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception e, ServletWebRequest request) {
        log.error("unexpected error", e);
        return ResponseEntity.status(500)
                .body(new ErrorResponse("INTERNAL_ERROR", "内部错误: " + e.getMessage(),
                        requestId(request)));
    }

    private String requestId(ServletWebRequest request) {
        String id = request.getHeader("X-Request-Id");
        return id != null ? id : "unknown";
    }
}
