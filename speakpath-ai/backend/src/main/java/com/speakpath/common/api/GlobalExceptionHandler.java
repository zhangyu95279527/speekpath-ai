package com.speakpath.common.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全局异常处理：统一信封、稳定错误码，不泄露堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleApi(ApiException ex) {
        return build(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fields = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.add(Map.of("field", fe.getField(), "reason", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()));
        }
        return build(ErrorCode.VALIDATION_ERROR, "Request validation failed", Map.of("fields", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(ErrorCode.BAD_REQUEST, "Malformed request body", null);
    }

    /** 路径/查询参数类型转换失败（如 id=undefined）：返回 400，不打 ERROR 堆栈。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(ErrorCode.BAD_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue(), null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleMissingHeader(MissingRequestHeaderException ex) {
        return build(ErrorCode.VALIDATION_ERROR, "Missing required header: " + ex.getHeaderName(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN, "Access denied", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleNoResource(NoResourceFoundException ex) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, "Resource not found", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.BAD_REQUEST, "Method not supported", null);
    }

    /** 客户端在响应完成前断开（刷新/关闭页面等）：非服务端故障，不写响应、不打 ERROR 堆栈。 */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException ex) {
        log.info("Client aborted request before response completed: {}", ex.getMessage());
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncNotUsable(AsyncRequestNotUsableException ex) {
        log.info("Async request no longer usable (client disconnected): {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleUnknown(Exception ex) {
        log.error("Unhandled exception traceId={}", TraceContext.traceId(), ex);
        return build(ErrorCode.INTERNAL_ERROR, "Internal server error", null);
    }

    private ResponseEntity<ApiEnvelope<Map<String, Object>>> build(ErrorCode code, String message, Map<String, Object> details) {
        HttpStatus status = code.status();
        return ResponseEntity.status(status)
                .body(ApiEnvelope.of(code.name(), message, details));
    }
}
