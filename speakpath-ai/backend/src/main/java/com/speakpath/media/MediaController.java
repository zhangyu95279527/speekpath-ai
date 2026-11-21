package com.speakpath.media;

import com.speakpath.common.api.ApiEnvelope;
import com.speakpath.common.security.CurrentUser;
import com.speakpath.infrastructure.rate.RateLimitService;
import com.speakpath.infrastructure.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * 媒体上传：服务端签发预签名 URL，客户端直传对象存储（私有桶）。
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final StorageService storage;
    private final RateLimitService rateLimiter;

    public MediaController(StorageService storage, RateLimitService rateLimiter) {
        this.storage = storage;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/presign-upload")
    public ApiEnvelope<Map<String, Object>> presign(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        rateLimiter.enforce("media-upload", rateLimiter.clientIp(request), 30, Duration.ofMinutes(10));
        String fileName = (String) body.get("fileName");
        String contentType = (String) body.get("contentType");
        return ApiEnvelope.ok(storage.presignUpload(CurrentUser.require().id(), fileName, contentType));
    }

    /**
     * 服务端直传（base64）：浏览器端预签名 PUT 存在跨域限制时使用；
     * 适用于短音频（如口语会话的用户录音）。
     */
    @PostMapping("/upload")
    public ApiEnvelope<Map<String, Object>> upload(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        rateLimiter.enforce("media-upload", rateLimiter.clientIp(request), 30, Duration.ofMinutes(10));
        String base64 = (String) body.get("audioBase64");
        String fileName = (String) body.get("fileName");
        String contentType = (String) body.get("contentType");
        if (base64 == null || base64.isBlank()) {
            throw com.speakpath.common.api.ApiException.invalid("audioBase64 required", "audioBase64", "");
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw com.speakpath.common.api.ApiException.invalid("Invalid base64 audio", "audioBase64", "");
        }
        if (bytes.length == 0 || bytes.length > 8 * 1024 * 1024) {
            throw com.speakpath.common.api.ApiException.invalid("Audio too large", "audioBase64", "max 8MB");
        }
        String objectKey = storage.putUserAudio(CurrentUser.require().id(),
                fileName == null || fileName.isBlank() ? "recording.webm" : fileName,
                contentType == null || contentType.isBlank() ? "audio/webm" : contentType, bytes);
        return ApiEnvelope.ok(Map.of("objectKey", objectKey, "sizeBytes", bytes.length));
    }
}
