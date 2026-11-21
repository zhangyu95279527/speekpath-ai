package com.speakpath.ai.speech;

import com.speakpath.ai.gateway.AiCapability;

import java.util.Map;

/**
 * 语音能力统一请求：按 capability 携带不同的输入。
 * <ul>
 *   <li>ASR：audio + audioFormat</li>
 *   <li>TTS：text + voice</li>
 *   <li>PRONUNCIATION：audio + referenceText</li>
 *   <li>REALTIME：sessionId + text（客户端文本降级）</li>
 * </ul>
 */
public record SpeechRequest(
        AiCapability capability,
        byte[] audio,
        String audioFormat,
        String text,
        String referenceText,
        String voice,
        String sessionId,
        Map<String, Object> extra) {

    public static SpeechRequest asr(byte[] audio, String format, Map<String, Object> extra) {
        return new SpeechRequest(AiCapability.ASR, audio, format, null, null, null, null, extra);
    }

    public static SpeechRequest tts(String text, String voice) {
        return new SpeechRequest(AiCapability.TTS, null, null, text, null, voice, null, Map.of());
    }

    /** TTS 带语速（rate 0.5~2.0，默认 1.0；学习场景建议 0.8）。 */
    public static SpeechRequest tts(String text, String voice, double rate) {
        return new SpeechRequest(AiCapability.TTS, null, null, text, null, voice, null, Map.of("rate", rate));
    }

    public static SpeechRequest pronunciation(byte[] audio, String format, String referenceText) {
        return new SpeechRequest(AiCapability.PRONUNCIATION, audio, format, null, referenceText, null, null, Map.of());
    }

    public static SpeechRequest realtime(String sessionId, String text) {
        return new SpeechRequest(AiCapability.REALTIME, null, null, text, null, null, sessionId, Map.of());
    }

    public String opt(String key) {
        return extra == null || extra.get(key) == null ? null : String.valueOf(extra.get(key));
    }
}
