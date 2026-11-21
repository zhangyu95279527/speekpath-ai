package com.speakpath.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 统一 API 响应信封：{ code, message, data, traceId, timestamp }。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiEnvelope<T>(
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp) {

    public static <T> ApiEnvelope<T> ok(T data) {
        return new ApiEnvelope<>("OK", "success", data, TraceContext.traceId(), Instant.now());
    }

    public static <T> ApiEnvelope<T> of(String code, String message, T data) {
        return new ApiEnvelope<>(code, message, data, TraceContext.traceId(), Instant.now());
    }
}
