package com.speakpath.ai.speech;

import com.speakpath.common.api.ApiEnvelope;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.security.CurrentUser;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.infrastructure.rate.RateLimitService;
import com.speakpath.infrastructure.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音能力对外接口：TTS 合成、发音评测。
 * ASR 集成在测评提交链路，REALTIME 集成在口语会话创建链路，均由 SpeechGateway 按 enable+provider 路由。
 */
@RestController
@RequestMapping("/api/v1/speech")
public class SpeechController {

    private final SpeechGateway gateway;
    private final StorageService storage;
    private final RateLimitService rateLimiter;

    public SpeechController(SpeechGateway gateway, StorageService storage, RateLimitService rateLimiter) {
        this.gateway = gateway;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
    }

    /** TTS：text → 音频（base64）。mock/无密钥时返回 fallback 提示，客户端降级浏览器 TTS。 */
    @PostMapping("/tts")
    public ApiEnvelope<Map<String, Object>> tts(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        rateLimiter.enforce("speech-tts", rateLimiter.clientIp(request), 30, Duration.ofMinutes(10));
        String text = body.get("text") == null ? "" : body.get("text").toString();
        String voice = body.get("voice") == null ? null : body.get("voice").toString();
        SpeechResult result = gateway.invoke(AiCapability.TTS, SpeechRequest.tts(text, voice));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", result.vendorCode());
        out.put("model", result.modelCode());
        out.put("mock", result.mock());
        out.put("format", result.audioFormat());
        out.put("audioBase64", result.audio() == null ? null
                : Base64.getEncoder().encodeToString(result.audio()));
        if (result.payload() != null) {
            out.put("fallback", result.payload().path("fallback").asText(""));
        }
        return ApiEnvelope.ok(out);
    }

    /**
     * 发音评测：服务端授权拿 warrant_id，客户端直连阿里云评测服务器做 WebSocket 流式评测。
     * 自由发挥场景（无参考文本）时，服务端先用 ASR 转写作为 refText；跟读场景可直接传 referenceText。
     */
    @PostMapping("/pronunciation")
    public ApiEnvelope<Map<String, Object>> pronunciation(@RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        rateLimiter.enforce("speech-pron", rateLimiter.clientIp(request), 30, Duration.ofMinutes(10));
        String objectKey = body.get("audioObjectKey") == null ? null : body.get("audioObjectKey").toString();
        String referenceText = body.get("referenceText") == null ? "" : body.get("referenceText").toString();

        String refText = referenceText;
        String transcript = referenceText;
        // 自由发挥场景：无参考文本时，先用 ASR 转写作为 refText
        if ((refText == null || refText.isBlank()) && objectKey != null && !objectKey.isBlank()) {
            storage.validateUserPrefix(objectKey);
            byte[] audio = storage.download(objectKey);
            SpeechResult asr = gateway.invoke(AiCapability.ASR,
                    SpeechRequest.asr(audio, audioFormat(objectKey), null));
            transcript = asr.text() == null ? "" : asr.text();
            refText = transcript;
        }
        if (refText == null || refText.isBlank()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("provider", "MOCK");
            out.put("transcript", "");
            out.put("note", "缺少参考文本（referenceText）或音频无法转写");
            return ApiEnvelope.ok(out);
        }

        // 服务端授权：拿 warrant_id，返回给客户端直连评测服务器
        SpeechRequest req = new SpeechRequest(AiCapability.PRONUNCIATION, null, null, null,
                refText, null, null,
                Map.of("userId", CurrentUser.require().id().toString(),
                        "clientIp", rateLimiter.clientIp(request)));
        SpeechResult result = gateway.invoke(AiCapability.PRONUNCIATION, req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", result.vendorCode());
        out.put("model", result.modelCode());
        out.put("mock", result.mock());
        out.put("transcript", transcript);
        out.put("refText", refText);
        if (result.payload() != null) {
            out.put("result", result.payload());
        }
        return ApiEnvelope.ok(out);
    }

    private String audioFormat(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        if (dot < 0 || dot == objectKey.length() - 1) {
            return "wav";
        }
        return objectKey.substring(dot + 1).toLowerCase();
    }
}
