package com.speakpath.ai.speech;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 语音能力统一结果。
 */
public record SpeechResult(
        String text,            // ASR 转写文本
        double confidence,      // ASR / 发音评测置信度
        byte[] audio,           // TTS 输出音频
        String audioFormat,     // TTS 音频格式
        JsonNode payload,       // 发音评测 / 实时语音结构化结果
        String vendorCode,
        String modelCode,
        boolean mock) {

    public static SpeechResult text(String text, double confidence, String vendor, String model, boolean mock) {
        return new SpeechResult(text, confidence, null, null, null, vendor, model, mock);
    }

    public static SpeechResult audio(byte[] audio, String format, String vendor, String model, boolean mock) {
        return new SpeechResult(null, 0, audio, format, null, vendor, model, mock);
    }

    public static SpeechResult payload(JsonNode payload, String vendor, String model, boolean mock) {
        return new SpeechResult(null, 0, null, null, payload, vendor, model, mock);
    }
}
