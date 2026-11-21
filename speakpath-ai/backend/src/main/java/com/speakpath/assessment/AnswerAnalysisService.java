package com.speakpath.assessment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 回答预分析：音频质量检测（时长/静音启发式）与文本特征，低质量不直接扣分。
 */
@Service
public class AnswerAnalysisService {

    private final ObjectMapper mapper = new ObjectMapper();

    public record Analysis(Map<String, Object> quality, Map<String, Object> features) {
    }

    public Analysis analyze(Integer durationMs, String audioObjectKey, String answerText) {
        boolean hasAudio = audioObjectKey != null && !audioObjectKey.isBlank();
        Map<String, Object> quality = quality(durationMs, hasAudio, answerText);
        Map<String, Object> features = textFeatures(answerText);
        return new Analysis(quality, features);
    }

    private Map<String, Object> quality(Integer durationMs, boolean hasAudio, String answerText) {
        Map<String, Object> q = new java.util.LinkedHashMap<>();
        q.put("hasAudio", hasAudio);
        q.put("textFallback", answerText != null && !answerText.isBlank());
        if (durationMs == null) {
            q.put("durationKnown", false);
            q.put("sufficient", !hasAudio);
        } else {
            q.put("durationMs", durationMs);
            boolean tooShort = durationMs < 2000;
            boolean tooLong = durationMs > 300_000;
            q.put("tooShort", tooShort);
            q.put("tooLong", tooLong);
            q.put("sufficient", !tooShort && !tooLong);
        }
        q.put("qualityScore", q.get("sufficient").equals(Boolean.TRUE) ? "GOOD" : "POOR");
        return q;
    }

    private Map<String, Object> textFeatures(String text) {
        Map<String, Object> f = new java.util.LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            f.put("wordCount", 0);
            f.put("hasContent", false);
            return f;
        }
        String[] words = text.split("\\s+");
        f.put("wordCount", words.length);
        f.put("hasContent", true);
        f.put("uniqueWordRatio", words.length == 0 ? 0 : Math.round(
                (double) java.util.Arrays.stream(words).map(String::toLowerCase).distinct().count() / words.length * 100) / 100.0);
        f.put("sentenceCount", Math.max(1, text.split("[.!?]+").length));
        return f;
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
