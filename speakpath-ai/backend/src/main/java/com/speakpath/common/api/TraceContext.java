package com.speakpath.common.api;

/**
 * 当前请求的 traceId 上下文（由 TraceIdFilter 设置）。
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String traceId() {
        String id = TRACE_ID.get();
        return id == null ? "unknown" : id;
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
