package com.speakpath.ai.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Set;

/**
 * 腾讯云语音：TTS（TextToVoice，TC3-HMAC-SHA256 签名）、ASR / 发音评测 / 实时语音返回接入凭证。
 */
@Component
public class TencentSpeechProvider implements SpeechProvider {

    private static final Set<AiCapability> CAPABILITIES =
            Set.of(AiCapability.ASR, AiCapability.TTS, AiCapability.PRONUNCIATION, AiCapability.REALTIME);

    private static final String HOST = "tts.tencentcloudapi.com";
    private static final String SERVICE = "tts";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String vendorCode() {
        return "TENCENT";
    }

    @Override
    public Set<AiCapability> supportedCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public SpeechResult invoke(SpeechRequest request, SpeechConfig config) {
        if (config.appId() == null || config.appId().isBlank() || config.appSecret() == null) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                    "Tencent requires secret-id (app-id) + secret-key");
        }
        return switch (request.capability()) {
            case TTS -> tts(request, config);
            case ASR -> asr(request, config);
            case PRONUNCIATION -> pronunciation(request, config);
            case REALTIME -> realtime(request, config);
            default -> throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Unsupported capability");
        };
    }

    private SpeechResult tts(SpeechRequest request, SpeechConfig config) {
        ObjectNode body = mapper.createObjectNode();
        body.put("Text", request.text() == null ? "" : request.text());
        body.put("SessionId", request.sessionId() == null ? "" : request.sessionId());
        body.put("VoiceType", parseInt(config.opt("voice-type"), 101001));
        body.put("Codec", "mp3");
        String payload = body.toString();
        String region = config.opt("region", "ap-guangzhou");
        String baseUrl = config.baseUrl() == null || config.baseUrl().isBlank()
                ? "https://" + HOST : config.baseUrl();

        try {
            long ts = Instant.now().getEpochSecond();
            String date = Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.BASIC_ISO_DATE);
            String authorization = tc3Authorization(config.appId(), config.appSecret(),
                    date, ts, payload, region);

            JsonNode resp = WebClient.create()
                    .post().uri(baseUrl)
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Host", HOST)
                    .header("X-TC-Action", "TextToVoice")
                    .header("X-TC-Version", "2019-08-23")
                    .header("X-TC-Timestamp", String.valueOf(ts))
                    .header("X-TC-Region", region)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(config.timeoutMs()));
            String audio = resp.path("Response").path("Audio").asText("");
            if (audio.isBlank()) {
                throw new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                        "Tencent TTS error: " + resp.path("Response").path("Error").path("Message").asText("no audio"));
            }
            return SpeechResult.audio(Base64.getDecoder().decode(audio), "mp3", "TENCENT",
                    config.opt("model", "tts"), false);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Tencent TTS failed: " + e.getMessage());
        }
    }

    private SpeechResult asr(SpeechRequest request, SpeechConfig config) {
        ObjectNode p = mapper.createObjectNode();
        p.put("transport", "HTTP");
        p.put("provider", "TENCENT");
        p.put("product", "录音文件识别/实时语音识别");
        p.put("note", "腾讯云 ASR 需 TC3 签名 + 异步任务，配置 secret-id/secret-key 后接入。");
        return SpeechResult.payload(p, "TENCENT", config.opt("model", "asr"), false);
    }

    private SpeechResult pronunciation(SpeechRequest request, SpeechConfig config) {
        ObjectNode p = mapper.createObjectNode();
        p.put("transport", "HTTP");
        p.put("provider", "TENCENT");
        p.put("product", "口语评测");
        p.put("referenceText", request.referenceText() == null ? "" : request.referenceText());
        return SpeechResult.payload(p, "TENCENT", config.opt("model", "pronunciation"), false);
    }

    private SpeechResult realtime(SpeechRequest request, SpeechConfig config) {
        ObjectNode p = mapper.createObjectNode();
        p.put("transport", "WEBSOCKET");
        p.put("provider", "TENCENT");
        p.put("product", "TRTC AI 实时对话");
        p.put("sessionId", request.sessionId() == null ? "" : request.sessionId());
        return SpeechResult.payload(p, "TENCENT", config.opt("model", "trtc-ai"), false);
    }

    /** 腾讯云 TC3-HMAC-SHA256 签名。 */
    static String tc3Authorization(String secretId, String secretKey, String date, long ts,
                                   String payload, String region) throws Exception {
        String algorithm = "TC3-HMAC-SHA256";
        String canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:" + HOST + "\n";
        String signedHeaders = "content-type;host";
        String hashedPayload = sha256Hex(payload);
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + hashedPayload;

        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = algorithm + "\n" + ts + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);

        byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, SERVICE);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = hex(hmac(secretSigning, stringToSign));

        return algorithm + " Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
