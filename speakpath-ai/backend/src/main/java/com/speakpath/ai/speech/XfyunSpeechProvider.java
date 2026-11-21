package com.speakpath.ai.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Set;

/**
 * 讯飞开放平台语音：语音听写 / 语音合成 / 发音评测（ISE）/ 超拟人交互。
 * 讯飞为 WebSocket + HMAC 签名三元组（appId + apiKey + apiSecret），
 * 服务端签发鉴权 URL 凭证，真实音频流由客户端直连厂商 WebSocket。
 */
@Component
public class XfyunSpeechProvider implements SpeechProvider {

    private static final Set<AiCapability> CAPABILITIES =
            Set.of(AiCapability.ASR, AiCapability.TTS, AiCapability.PRONUNCIATION, AiCapability.REALTIME);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String vendorCode() {
        return "XFYUN";
    }

    @Override
    public Set<AiCapability> supportedCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public SpeechResult invoke(SpeechRequest request, SpeechConfig config) {
        if (config.appId() == null || config.appId().isBlank()
                || config.apiKey() == null || config.appSecret() == null) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                    "Xfyun requires app-id + api-key + api-secret");
        }
        return switch (request.capability()) {
            case ASR -> credential("iat-api.xfyun.cn", "/v2/iat", request, config, "语音听写");
            case TTS -> credential("tts-api.xfyun.cn", "/v2/tts", request, config, "在线语音合成");
            case PRONUNCIATION -> credential("ise-api.xfyun.cn", "/v2/open-ise", request, config, "发音评测");
            case REALTIME -> realtime(request, config);
            default -> throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Unsupported capability");
        };
    }

    private SpeechResult credential(String host, String path, SpeechRequest request,
                                    SpeechConfig config, String label) {
        String url = buildAuthUrl(host, path, config.apiKey(), config.appSecret());
        ObjectNode p = mapper.createObjectNode();
        p.put("transport", "WEBSOCKET");
        p.put("provider", "XFYUN");
        p.put("capability", label);
        p.put("appId", config.appId());
        p.put("url", url);
        p.put("host", host);
        p.put("sessionId", request.sessionId() == null ? "" : request.sessionId());
        return SpeechResult.payload(p, "XFYUN", label, false);
    }

    private SpeechResult realtime(SpeechRequest request, SpeechConfig config) {
        ObjectNode p = mapper.createObjectNode();
        p.put("transport", "WEBSOCKET");
        p.put("provider", "XFYUN");
        p.put("product", "超拟人交互");
        p.put("appId", config.appId());
        p.put("sessionId", request.sessionId() == null ? "" : request.sessionId());
        return SpeechResult.payload(p, "XFYUN", "sparkos-interactive", false);
    }

    /** 讯飞 WebSocket 鉴权 URL（HMAC-SHA256）。 */
    static String buildAuthUrl(String host, String path, String apiKey, String apiSecret) {
        try {
            String date = ZonedDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);
            String origin = "host: " + host + "\ndate: " + date + "\nGET " + path + " HTTP/1.1";
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getEncoder()
                    .encodeToString(mac.doFinal(origin.getBytes(StandardCharsets.UTF_8)));
            String authOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\", "
                    + "headers=\"host date request-line\", signature=\"" + signature + "\"";
            String authorization = Base64.getEncoder()
                    .encodeToString(authOrigin.getBytes(StandardCharsets.UTF_8));
            return "wss://" + host + path
                    + "?authorization=" + URLEncoder.encode(authorization, StandardCharsets.UTF_8)
                    + "&date=" + URLEncoder.encode(date, StandardCharsets.UTF_8)
                    + "&host=" + URLEncoder.encode(host, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Xfyun auth url failed: " + e.getMessage());
        }
    }
}
