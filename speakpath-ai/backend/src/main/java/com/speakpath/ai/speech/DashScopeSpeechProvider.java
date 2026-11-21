package com.speakpath.ai.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * 阿里云百炼 DashScope 语音：ASR（Qwen-Audio 短音频同步识别）、TTS（Qwen-Audio-TTS）、
 * 发音评测（智能语音交互）、实时语音（Qwen-Audio-Realtime 接入凭证）。
 */
@Component
public class DashScopeSpeechProvider implements SpeechProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeSpeechProvider.class);

    private static final Set<AiCapability> CAPABILITIES =
            Set.of(AiCapability.ASR, AiCapability.TTS, AiCapability.PRONUNCIATION, AiCapability.REALTIME);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String vendorCode() {
        return "DASHSCOPE";
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
        String base = base(config, "https://dashscope.aliyuncs.com/api/v1");
        String model = config.opt("model", "qwen-audio-3.1-asr-flash");
        String format = request.audioFormat() == null || request.audioFormat().isBlank()
                ? "wav" : request.audioFormat().toLowerCase();

        // 优先字节直传（base64 data URL）：云端服务无法访问本机 localhost 的预签名 URL
        String data;
        if (request.audio() != null && request.audio().length > 0) {
            data = "data:audio/" + format + ";base64,"
                    + Base64.getEncoder().encodeToString(request.audio());
        } else {
            String fileUrl = request.opt("fileUrl");
            if (fileUrl == null || fileUrl.isBlank()) {
                throw new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                        "DashScope ASR requires audio bytes or a publicly reachable fileUrl");
            }
            data = fileUrl;
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ObjectNode message = body.putObject("input").putArray("messages").addObject();
        message.put("role", "user");
        ObjectNode content = message.putArray("content").addObject();
        content.put("type", "input_audio");
        content.putObject("input_audio").put("data", data);
        ObjectNode params = body.putObject("parameters");
        params.put("format", format);

        JsonNode resp = post(base + "/services/aigc/multimodal-generation/generation",
                body, config.apiKey(), config, true);
        // 响应结构因 SSE 开关而异：优先 output.text，回退顶层 text / output.sentence.text
        String text = resp.path("output").path("text").asText("");
        if (text.isBlank()) {
            text = resp.path("text").asText("");
        }
        if (text.isBlank()) {
            text = resp.path("output").path("sentence").path("text").asText("");
        }
        double confidence = 0.9;
        return SpeechResult.text(text, confidence, "DASHSCOPE", model, false);
    }

    private SpeechResult tts(SpeechRequest request, SpeechConfig config) {
        String base = base(config, "https://dashscope.aliyuncs.com/api/v1");
        String model = config.opt("model", "qwen-audio-3.1-tts-next");
        String voice = request.voice() == null ? config.opt("voice", "longxiaochun") : request.voice();

        // 兼容新旧两代模型：flash 读取 input.text/input.voice，next 读取 input.text_prompt/parameters.voice
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ObjectNode input = body.putObject("input");
        input.put("text", request.text() == null ? "" : request.text());
        input.put("text_prompt", request.text() == null ? "" : request.text());
        input.put("voice", voice);
        ObjectNode params = body.putObject("parameters");
        params.put("voice", voice);
        params.put("format", "mp3");
        String rateOpt = request.opt("rate");
        if (rateOpt != null) {
            try {
                params.put("rate", Double.parseDouble(rateOpt));
            } catch (NumberFormatException ignored) {
                // 非法语速忽略，使用默认
            }
        }

        // SSE 流式响应内嵌 base64 音频，避免二次下载 OSS URL
        String sse = postSse(base + "/services/audio/tts/SpeechSynthesizer", body, config.apiKey(), config);
        byte[] audio = decodeSseAudio(sse, config);
        return SpeechResult.audio(audio, "mp3", "DASHSCOPE", model, false);
    }

    /** 发送 SSE 请求并返回原始响应文本。 */
    private String postSse(String url, ObjectNode body, String apiKey, SpeechConfig config) {
        try {
            // 长文本的 SSE 响应（base64 音频）可超过默认 256KB 缓冲上限，放宽到 8MB
            return WebClient.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                    .build()
                    .post().uri(url)
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-SSE", "enable")
                    .header("Accept", "text/event-stream")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(config.timeoutMs()));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "DashScope request failed: " + e.getMessage());
        }
    }

    /**
     * 解析 TTS SSE 响应为音频字节。
     * flash：每个 data: 行是完整 JSON 事件，output.audio.data 为独立 base64 编码的 mp3 小段，
     * 段尾带有零字节填充——必须逐片解码、去掉段尾零填充后按序拼接字节；
     * 若整体拼接解码，段间填充会破坏 mp3 帧同步，播放器只播第一段。
     * next：单个 JSON 跨多行 data:，回退为整体拼接解析（含 output.audio.url 下载）。
     */
    private byte[] decodeSseAudio(String sse, SpeechConfig config) {
        java.util.List<String> chunkList = new java.util.ArrayList<>();
        for (String line : sse.split("\\r?\\n")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            try {
                JsonNode evt = mapper.readTree(line.substring(5));
                String data = evt.path("output").path("audio").path("data").asText("");
                if (!data.isBlank()) {
                    chunkList.add(data);
                }
            } catch (Exception ignored) {
                // 跨行 JSON 行（next 旧格式），交给下方整体拼接回退
            }
        }
        boolean independentChunks = !chunkList.isEmpty() && chunkList.stream()
                .allMatch(c -> c.contains("=") || c.trim().length() % 4 == 0);
        if (independentChunks) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (String c : chunkList) {
                String clean = c.replace("=", "").trim();
                clean += "=".repeat((4 - clean.length() % 4) % 4);
                try {
                    byte[] seg = Base64.getDecoder().decode(clean);
                    int end = seg.length;
                    while (end > 0 && seg[end - 1] == 0) {
                        end--;
                    }
                    out.write(seg, 0, end);
                } catch (IllegalArgumentException e) {
                    log.warn("TTS SSE chunk decode failed, falling back: {}", e.getMessage());
                    return fallbackSseJson(sse, config);
                }
            }
            return out.toByteArray();
        }
        return fallbackSseJson(sse, config);
    }

    /** 回退：把 data: 行整体拼接为单个 JSON 解析（next 旧格式 / 非流式）。 */
    private byte[] fallbackSseJson(String sse, SpeechConfig config) {
        StringBuilder json = new StringBuilder();
        for (String line : sse.split("\\r?\\n")) {
            if (line.startsWith("data:")) {
                json.append(line.substring(5));
            }
        }
        try {
            return decodeAudio(mapper.readTree(json.toString()));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "DashScope TTS returned no audio");
        }
    }

    private byte[] decodeAudio(JsonNode resp) {
        String data = resp.path("output").path("audio").path("data").asText("");
        if (!data.isBlank()) {
            return Base64.getDecoder().decode(data);
        }
        String url = resp.path("output").path("audio").path("url").asText("");
        if (!url.isBlank()) {
            try {
                return WebClient.create().get().uri(url).retrieve()
                        .bodyToMono(byte[].class).block(Duration.ofSeconds(20));
            } catch (Exception e) {
                log.warn("DashScope TTS audio download failed: {}", e.getMessage());
            }
        }
        throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "DashScope TTS returned no audio");
    }

    private SpeechResult pronunciation(SpeechRequest request, SpeechConfig config) {
        // 阿里云发音评测（智能科教内容生成平台）：服务端授权拿 warrant_id，客户端直连评测服务器做流式评测。
        String appId = config.appId();
        String appSecret = config.appSecret();
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                    "DashScope pronunciation requires app-id + api-secret");
        }
        String userId = request.opt("userId");
        if (userId == null || userId.isBlank()) {
            userId = request.sessionId() == null ? "unknown" : request.sessionId();
        }
        String clientIp = request.opt("clientIp");
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = config.opt("user-client-ip", "127.0.0.1");
        }
        String refText = request.referenceText() == null ? "" : request.referenceText();
        String warrantId = authorize(appId, appSecret, userId, clientIp, config);

        ObjectNode p = mapper.createObjectNode();
        p.put("transport", "WEBSOCKET");
        p.put("provider", "DASHSCOPE");
        p.put("product", "智能科教内容生成平台-发音评测");
        p.put("warrantId", warrantId);
        p.put("appid", appId);
        p.put("userId", userId);
        p.put("coreType", config.opt("core-type", "en.sent.score"));
        p.put("refText", refText);
        p.put("note", "客户端使用 warrantId 直连评测服务器进行 WebSocket 流式评测");
        return SpeechResult.payload(p, "DASHSCOPE", config.opt("model", "pronunciation"), false);
    }

    /** 服务端授权：appid + app_secret MD5 签名 → warrant_id。 */
    private String authorize(String appId, String appSecret, String userId, String clientIp, SpeechConfig config) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String sign = md5Sign(appId, appSecret, timestamp, userId, clientIp);
        String url = base(config, "https://api.cloud.ssapi.cn/auth/authorize");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("appid", appId);
        form.add("timestamp", timestamp);
        form.add("user_id", userId);
        form.add("user_client_ip", clientIp);
        form.add("request_sign", sign);
        form.add("warrant_available", "7200");
        try {
            JsonNode resp = WebClient.create()
                    .post().uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(config.timeoutMs()));
            if (resp.path("code").asInt(-1) != 0) {
                throw new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                        "DashScope authorize failed: " + resp.path("msg").asText("unknown"));
            }
            return resp.path("data").path("warrant_id").asText("");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "DashScope authorize failed: " + e.getMessage());
        }
    }

    /** MD5 签名：按 key 排序（app_secret, appid, timestamp, user_client_ip, user_id）。 */
    private String md5Sign(String appId, String appSecret, String timestamp, String userId, String clientIp) {
        String s = "app_secret=" + appSecret
                + "&appid=" + appId
                + "&timestamp=" + timestamp
                + "&user_client_ip=" + clientIp
                + "&user_id=" + userId;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "MD5 sign failed: " + e.getMessage());
        }
    }

    private SpeechResult realtime(SpeechRequest request, SpeechConfig config) {
        ObjectNode p = mapper.createObjectNode();
        p.put("transport", "WEBSOCKET");
        p.put("provider", "DASHSCOPE");
        p.put("model", config.opt("model", "qwen-audio-3.1-realtime-flash"));
        p.put("endpoint", base(config, "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"));
        p.put("sessionId", request.sessionId() == null ? "" : request.sessionId());
        return SpeechResult.payload(p, "DASHSCOPE", config.opt("model", "qwen-audio-3.1-realtime-flash"), false);
    }

    private JsonNode post(String url, ObjectNode body, String apiKey, SpeechConfig config) {
        return post(url, body, apiKey, config, false);
    }

    private JsonNode post(String url, ObjectNode body, String apiKey, SpeechConfig config, boolean disableSse) {
        try {
            WebClient.RequestBodySpec spec = WebClient.create()
                    .post().uri(url)
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .header("Content-Type", "application/json");
            if (disableSse) {
                spec.header("X-DashScope-SSE", "disable");
            }
            return spec.bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(config.timeoutMs()));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "DashScope request failed: " + e.getMessage());
        }
    }

    private String base(SpeechConfig config, String defaultValue) {
        return config.baseUrl() == null || config.baseUrl().isBlank() ? defaultValue : config.baseUrl();
    }
}
