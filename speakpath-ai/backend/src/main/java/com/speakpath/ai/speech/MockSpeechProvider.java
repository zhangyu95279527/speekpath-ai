package com.speakpath.ai.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.gateway.AiCapability;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Mock 语音供应商：确定性输出，无密钥环境端到端可运行。
 * 生产通过 enable + provider 切换到真实厂商（DashScope / 讯飞 / 火山 / 腾讯）。
 */
@Component
public class MockSpeechProvider implements SpeechProvider {

    private static final Set<AiCapability> CAPABILITIES =
            Set.of(AiCapability.ASR, AiCapability.TTS, AiCapability.PRONUNCIATION, AiCapability.REALTIME);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String vendorCode() {
        return "MOCK";
    }

    @Override
    public Set<AiCapability> supportedCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public SpeechResult invoke(SpeechRequest request, SpeechConfig config) {
        return switch (request.capability()) {
            case ASR -> asr(request);
            case TTS -> tts(request);
            case PRONUNCIATION -> pronunciation(request);
            case REALTIME -> realtime(request);
            default -> SpeechResult.payload(mapper.createObjectNode().put("mock", true), "MOCK", "mock", true);
        };
    }

    private SpeechResult asr(SpeechRequest request) {
        String text = request.text();
        if (text == null || text.isBlank()) {
            return SpeechResult.text("", 0.1, "MOCK", "mock-asr", true);
        }
        return SpeechResult.text(text, 0.98, "MOCK", "mock-asr", true);
    }

    private SpeechResult tts(SpeechRequest request) {
        ObjectNode p = mapper.createObjectNode();
        p.put("mock", true);
        p.put("fallback", "browser-tts");
        p.put("note", "无真实 TTS 密钥，客户端可使用浏览器/系统语音合成朗读。");
        return SpeechResult.payload(p, "MOCK", "mock-tts", true);
    }

    private SpeechResult pronunciation(SpeechRequest request) {
        ObjectNode p = mapper.createObjectNode();
        p.put("available", true);
        p.put("confidence", 0.55);
        p.put("intelligibility", 0.8);
        p.put("accuracy", 0.72);
        p.put("fluency", 0.7);
        p.put("completeness", 0.85);
        p.put("mock", true);
        p.put("note", "Mock 发音评分用于开发演示，正式音素级评分请配置真实发音评测供应商。");
        return SpeechResult.payload(p, "MOCK", "mock-pronunciation", true);
    }

    private SpeechResult realtime(SpeechRequest request) {
        ObjectNode p = mapper.createObjectNode();
        p.put("transport", "WEBSOCKET");
        p.put("provider", "MOCK");
        p.put("sessionId", request.sessionId() == null ? "" : request.sessionId());
        p.put("mock", true);
        return SpeechResult.payload(p, "MOCK", "mock-realtime", true);
    }
}
