package com.speakpath.common.api;

import org.springframework.http.HttpStatus;

/**
 * 稳定错误码（规格书 §4.2），不向客户端泄露堆栈或供应商原始错误。
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
    AI_PROVIDER_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
    AI_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY),
    AI_OUTPUT_INVALID(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
