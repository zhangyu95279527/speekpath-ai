package com.speakpath.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求生成 traceId（ULID 风格前缀 01），贯穿日志与响应信封。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = (incoming == null || incoming.isBlank()) ? newTraceId() : incoming;
        TraceContext.set(traceId);
        MDC.put("traceId", traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            TraceContext.clear();
        }
    }

    private String newTraceId() {
        // ULID 风格前缀 + UUID：可排序且足够随机
        return "01" + Long.toHexString(System.currentTimeMillis() / 10) + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }
}
