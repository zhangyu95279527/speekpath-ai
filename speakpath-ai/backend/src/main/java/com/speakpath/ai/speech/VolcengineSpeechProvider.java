package com.speakpath.ai.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * 火山引擎豆包语音（方舟）：TTS（audio/speech）、ASR（audio/transcriptions，OpenAI 兼容）、
 * 口语评测与实时语音返回接入凭证。
 */
@Component
public class VolcengineSpeechProvider implements SpeechProvider {

    private static final Set<AiCapability> CAPABILITIES =
            Set.of(AiCapability.ASR, AiCapability.TTS, AiCapability.PRONUNCIATION, AiCapability.REALTIME);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String vendorCode() {
        return "VOLCENGINE";
    }

    @Override
    public Set<AiCapability> supportedCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public SpeechResult invoke(SpeechRequest request, SpeechConfig config) {
        return switch (request.capability()) {
            case ASR -> asr(request, config);
            case TTS -> tts(request, config);
            case PRONUNCIATION -> pronunciation(request, config);
            case REALTIME -> realtime(request, config);
            default -> throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Unsupported capability");
        };
    }

    private SpeechResult asr(SpeechRequest request, SpeechConfig config) {
        if (request.audio() == null || request.audio().length == 0) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Volcengine ASR requires audio bytes");
        }
        String base = base(config, "https://ark.cn-beijing.volces.com/api/v3");
        String model = config.opt("model", "doubao-seed-asr-2.0");

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("model", model);
        form.add("file", new ByteArrayResource(request.audio()) {
            @Override
            public String getFilename() {
                return "audio." + (request.audioFormat() == null ? "wav" : request.audioFormat());
            }
        });
        try {
            JsonNode resp = WebClient.create()
                    .post().uri(base + "/audio/transcriptions")
                    .header("Authorization", "Bearer " + (config.apiKey() == null ? "" : config.apiKey()))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(form))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(config.timeoutMs()));
            String text = resp.path("text").asText("");
            return SpeechResult.text(text, 0.9, "VOLCENGINE", model, false);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Volcengine ASR failed: " + e.getMessage());
        }
    }

    private SpeechResult tts(SpeechRequest request, SpeechConfig config) {
        String base = base(config, "https://ark.cn-beijing.volces.com/api/v3");
        String model = config.opt("model", "doubao-seed-tts");
        String voice = request.voice() == null ? config.opt("voice", "zh_female_wanwan") : request.voice();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", request.text() == null ? "" : request.text());
        body.put("voice", voice);
        try {
            byte[] raw = WebClient.create()
                    .post().uri(base + "/audio/speech")
                    .header("Authorization", "Bearer " + (config.apiKey() == null ? "" : config.apiKey()))
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofMillis(config.timeoutMs()));
            byte[] audio = maybeDecode(raw);
            return SpeechResult.audio(audio, "mp3", "VOLCENGINE", model, false);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Volcengine TTS failed: " + e.getMessage());
        }
    }

    private byte[] maybeDecode(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Volcengine TTS returned no audio");
        }
        if (raw[0] == '{') {
            try {
                JsonNode json = mapper.readTree(raw);
                String data = json.path("data").asText("");
                if (!data.isBlank()) {
                    return Base64.getDecoder().decode(data);
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return raw;
    }

    private SpeechResult pronunciation(SpeechRequest request, SpeechConfig config) {
        ObjectNode p = mapper.createObjectNode();
        p.put("transport", "HTTP");
        p.put("provider", "VOLCENGINE");
        p.put("product", "豆包口语评测");
        p.put("referenceText", request.referenceText() == null ? "" : request.referenceText());
        p.put("note", "豆包口语评测需在火山控制台开通语音服务后接入。");
        return SpeechResult.payload(p, "VOLCENGINE", "doubao-pronunciation", false);
    }

    private SpeechResult realtime(SpeechRequest request, SpeechConfig config) {
        ObjectNode p = mapper.createObjectNode();
        p.put("transport", "WEBSOCKET");
        p.put("provider", "VOLCENGINE");
        p.put("model", config.opt("model", "doubao-seed-asr-2.0"));
        p.put("sessionId", request.sessionId() == null ? "" : request.sessionId());
        return SpeechResult.payload(p, "VOLCENGINE", config.opt("model", "doubao-realtime"), false);
    }

    private String base(SpeechConfig config, String defaultValue) {
        return config.baseUrl() == null || config.baseUrl().isBlank() ? defaultValue : config.baseUrl();
    }
}
