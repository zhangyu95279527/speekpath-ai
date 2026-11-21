package com.speakpath.common.api;

import java.util.List;
import java.util.Map;

/**
 * 业务异常：携带稳定错误码与可选字段错误。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, what + " not found");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException invalid(String message, String field, String reason) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
