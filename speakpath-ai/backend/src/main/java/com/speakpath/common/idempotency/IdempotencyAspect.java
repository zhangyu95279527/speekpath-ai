package com.speakpath.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.common.api.ApiEnvelope;
import com.speakpath.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 写接口幂等：同一用户、同一路径、同一 Idempotency-Key 重放返回首次结果。
 */
@Aspect
@Component
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    public static final String HEADER = "Idempotency-Key";

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyAspect(IdempotencyRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(com.speakpath.common.idempotency.Idempotent)")
    public Object enforce(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = attrs.getRequest();
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            return joinPoint.proceed();
        }
        String path = request.getMethod() + " " + request.getRequestURI();
        var userId = CurrentUser.id();
        var existing = repository.findByUserIdAndPathAndIdemKey(userId, path, key);
        if (existing.isPresent()) {
            log.info("Idempotent replay userId={} path={} key={}", userId, path, key);
            IdempotencyRecord record = existing.get();
            return idempotentResponse(record);
        }
        Object result = joinPoint.proceed();
        if (result instanceof ApiEnvelope<?> envelope) {
            String hash = sha256(objectMapper.writeValueAsString(joinPoint.getArgs()));
            String json = objectMapper.writeValueAsString(envelope);
            IdempotencyRecord record = new IdempotencyRecord(userId, path, key, hash, json, 200, Instant.now().plusSeconds(86400));
            repository.save(record);
        }
        return result;
    }

    private Object idempotentResponse(IdempotencyRecord record) {
        try {
            return objectMapper.readValue(record.getResponseJson(), ApiEnvelope.class);
        } catch (Exception e) {
            return ApiEnvelope.of("OK", "success", record.getResponseJson());
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return input;
        }
    }
}
