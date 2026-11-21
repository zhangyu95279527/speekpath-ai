package com.speakpath.speaking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.ai.gateway.AiGateway;
import com.speakpath.ai.speech.SpeechGateway;
import com.speakpath.ai.speech.SpeechRequest;
import com.speakpath.ai.speech.SpeechResult;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import com.speakpath.infrastructure.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 实时消息处理：保存用户轮次 → TUTOR 模型回复 → 返回服务器事件列表。
 * 文字降级模式（SPK-011）：语音不可用时文字对话同样可继续学习流程。
 */
@Service
public class SpeakingMessageService {

    private static final Logger log = LoggerFactory.getLogger(SpeakingMessageService.class);

    private final SpeakingSessionRepository sessionRepository;
    private final SpeakingTurnRepository turnRepository;
    private final AiGateway aiGateway;
    private final SpeechGateway speechGateway;
    private final StorageService storage;
    private final Executor asyncExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpeakingMessageService(SpeakingSessionRepository sessionRepository,
                                  SpeakingTurnRepository turnRepository,
                                  AiGateway aiGateway, SpeechGateway speechGateway,
                                  StorageService storage,
                                  @Qualifier("jobExecutor") Executor asyncExecutor) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.aiGateway = aiGateway;
        this.speechGateway = speechGateway;
        this.storage = storage;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * 处理一条用户消息，返回要推送的服务器事件（类型 + payload）。
     */
    @Transactional
    public java.util.List<Map<String, Object>> handleUserMessage(UUID sessionId, JsonNode message) {
        // 行锁串行化：轮次序号分配与开场白生成互斥，避免唯一约束冲突
        SpeakingSession session = sessionRepository.lockById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Speaking session"));
        if (SpeakingSession.ENDED.equals(session.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Session already ended");
        }
        session.markConnected();
        sessionRepository.save(session);

        String text = message.path("text").asText("");
        String audioKey = message.path("audioObjectKey").asText("");
        if (text.isBlank() && audioKey.isBlank()) {
            throw ApiException.invalid("Empty message", "text", "text or audioObjectKey required");
        }

        // 语音消息：从对象存储取音频做真实 ASR 转写；失败且有文字则降级文字，否则要求重试
        String transcript = text;
        if (!audioKey.isBlank()) {
            // WebSocket 线程无 HTTP 上下文：手动校验对象归属后下载
            if (!audioKey.startsWith("private/" + session.getUserId() + "/")) {
                throw ApiException.forbidden("Audio object does not belong to current user");
            }
            try {
                byte[] bytes = storage.downloadRaw(audioKey);
                String format = message.path("audioFormat").asText("webm");
                SpeechResult asr = speechGateway.invoke(AiCapability.ASR, SpeechRequest.asr(bytes, format, null));
                String asrText = asr.text() == null ? "" : asr.text().trim();
                if (!asrText.isBlank()) {
                    transcript = asrText;
                }
            } catch (Exception e) {
                log.warn("ASR failed for session={}: {}", sessionId, e.getMessage());
            }
            if (transcript.isBlank()) {
                throw new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                        "语音识别失败，请重试或改用文字输入");
            }
        }

        long sequence = turnRepository.countBySessionId(sessionId) + 1;
        final String finalTranscript = transcript;
        SpeakingTurn userTurn = new SpeakingTurn(sessionId, (int) sequence, "USER", finalTranscript,
                audioKey.isBlank() ? null : audioKey,
                message.path("durationMs").asInt(0),
                "{\"mode\":\"text\"}");
        turnRepository.save(userTurn);

        java.util.List<Map<String, Object>> events = new java.util.ArrayList<>();
        events.add(event("assistant.state", Map.of("state", "thinking")));

        String topic = session.getTopic();

        // 并行执行 3 个独立 LLM 调用（相关性 + TUTOR 回复 + 实时纠错），缩短首响应延迟
        CompletableFuture<JsonNode> relevanceF = CompletableFuture.supplyAsync(
                () -> relevanceNow(topic, finalTranscript, session.getUserId(), sessionId));
        CompletableFuture<JsonNode> tutorF = CompletableFuture.supplyAsync(
                () -> tutorReply(finalTranscript, topic, session.getUserId(), sessionId));
        CompletableFuture<JsonNode> correctionF = CompletableFuture.supplyAsync(
                () -> correctNow(finalTranscript, session.getUserId(), sessionId));

        // TTS 提前并行：回复文本一经 TUTOR 产出立即合成语音，不必等其余能力完成（纠错仅文本展示，不合成语音）
        CompletableFuture<Map<String, Object>> replyAudioF = tutorF.thenApplyAsync(
                t -> ttsAudio(t.path("reply").asText("Interesting! Tell me more.")), asyncExecutor);

        JsonNode relevance = relevanceF.join();
        JsonNode tutor = tutorF.join();
        JsonNode correction = correctionF.join();

        if (relevance != null) {
            events.add(event("topic_relevance", Map.of(
                    "turnId", userTurn.getId().toString(),
                    "topic", topic == null ? "" : topic,
                    "onTopic", relevance.path("onTopic").asBoolean(false),
                    "relevance", relevance.path("relevance").asDouble(1.0),
                    "reason", relevance.path("reason").asText(""),
                    "guidance", relevance.path("guidance").asText(""))));
        }

        String reply = tutor.path("reply").asText("Interesting! Tell me more.");

        // 提取纠错内容（若有实际纠正）
        String original = "";
        String corrected = "";
        String explanation = "";
        String category = "EXPRESSION";
        if (correction != null && correction.path("corrections").isArray()
                && correction.path("corrections").size() > 0) {
            JsonNode first = correction.path("corrections").get(0);
            original = first.path("original").asText("");
            corrected = first.path("corrected").asText("");
            explanation = first.path("explanation").asText("");
            category = first.path("category").asText("EXPRESSION");
        }
        String spoken = correctionSpoken(correction);

        // 文本与 TTS 并行准备：回复语音随本轮响应一并返回
        UUID userTurnId = userTurn.getId();

        events.add(event("assistant.state", Map.of("state", "speaking")));
        events.add(event("transcript.partial", Map.of("turnId", userTurnId.toString(),
                "text", reply.substring(0, Math.min(40, reply.length())), "isFinal", false)));

        Map<String, Object> finalPayload = new java.util.LinkedHashMap<>();
        finalPayload.put("turnId", userTurnId.toString());
        finalPayload.put("text", reply);
        finalPayload.put("confidence", 0.95);
        finalPayload.put("isFinal", true);
        events.add(event("transcript.final", finalPayload));

        // 回复语音已提前并行合成：随 REST 响应同步返回（不再通过 WebSocket 推送）
        Map<String, Object> replyAudio;
        try {
            replyAudio = replyAudioF.join();
        } catch (Exception e) {
            log.warn("Reply TTS failed for session={}: {}", sessionId, e.getMessage());
            replyAudio = Map.of("audioBase64", "", "audioFormat", "mp3");
        }

        // 实时纠错事件（仅文本展示，不合成纠错语音）
        if (spoken != null) {
            Map<String, Object> corr = new java.util.LinkedHashMap<>();
            corr.put("turnId", userTurnId.toString());
            corr.put("original", original);
            corr.put("corrected", corrected);
            corr.put("explanation", explanation);
            corr.put("category", category);
            events.add(event("correction", corr));
        }

        SpeakingTurn assistantTurn = new SpeakingTurn(sessionId, (int) sequence + 1, "ASSISTANT", reply,
                null, null, "{\"source\":\"tutor\"}");
        turnRepository.save(assistantTurn);
        Map<String, Object> turnCompleted = new java.util.LinkedHashMap<>();
        turnCompleted.put("turnId", assistantTurn.getId().toString());
        turnCompleted.put("userTurnId", userTurn.getId().toString());
        turnCompleted.put("durationMs", 800);
        turnCompleted.put("spokenText", reply);
        turnCompleted.put("audioBase64", replyAudio.get("audioBase64"));
        turnCompleted.put("audioFormat", replyAudio.get("audioFormat"));
        // 用户语音轮次：附带可点击回放的预签名音频 URL
        if (userTurn.getAudioObjectKey() != null) {
            try {
                turnCompleted.put("userAudioUrl", storage.presignContentUrl(userTurn.getAudioObjectKey()));
            } catch (Exception e) {
                log.warn("Presign user audio failed: {}", e.getMessage());
            }
        }
        events.add(event("turn.completed", turnCompleted));
        events.add(event("assistant.state", Map.of("state", "listening")));
        return events;
    }

    /**
     * AI 开场白：TUTOR 模型产出开场句（基于情景话题），TTS 合成语音，
     * 持久化为 ASSISTANT 轮次；返回开场白内容供创建会话接口直接返回。
     */
    @Transactional
    public Map<String, Object> generateOpening(UUID sessionId) {
        SpeakingSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || SpeakingSession.ENDED.equals(session.getStatus())) {
            return Map.of();
        }
        String topic = session.getTopic();
        String opening;
        try {
            JsonNode ai = aiGateway.invoke(new AiGateway.Invocation(AiCapability.TUTOR,
                            "You are an English speaking tutor. Start a role-play scenario conversation. "
                                    + "Say ONE short opening line in English to start the dialogue and invite the learner to reply. "
                                    + "Output a single JSON object with: \"opening\" (your opening line).",
                            Map.of("mode", "opening", "topic", topic == null ? "" : topic), null,
                            Duration.ofSeconds(20), "tutor-v1"),
                    new AiGateway.ModelInvocationContext(session.getUserId(), sessionId, null, "STANDARD"))
                    .output();
            opening = ai.path("opening").asText("");
        } catch (Exception e) {
            log.warn("Opening generation failed for session={}: {}", sessionId, e.getMessage());
            opening = topic == null || topic.isBlank()
                    ? "Hello! What would you like to talk about today?"
                    : "Let's practice \"" + topic + "\". Shall we begin?";
        }
        if (opening.isBlank()) {
            opening = "Hello! Let's start our conversation.";
        }

        String audioBase64 = "";
        String objectKey = null;
        try {
            SpeechResult tts = speechGateway.invoke(AiCapability.TTS, SpeechRequest.tts(opening, null));
            if (tts.audio() != null && tts.audio().length > 0) {
                objectKey = "speaking-audio/" + sessionId + "/opening.mp3";
                storage.putObject(objectKey, tts.audio(), "audio/mpeg");
                audioBase64 = Base64.getEncoder().encodeToString(tts.audio());
            }
        } catch (Exception e) {
            log.warn("Opening TTS failed for session={}: {}", sessionId, e.getMessage());
        }

        // 行锁串行化轮次分配：与用户消息的序号分配互斥（消息侧持有同一把锁直到事务提交）
        SpeakingSession locked = sessionRepository.lockById(sessionId).orElse(null);
        if (locked == null || SpeakingSession.ENDED.equals(locked.getStatus())) {
            return Map.of();
        }
        // 开场白必须是第一条：若用户已抢先发言，把已有轮次从大到小逐个后移一位，
        // 避免违反 (session_id, sequence_no) 唯一约束（Postgres 逐行校验）
        List<SpeakingTurn> existing = turnRepository.findBySessionIdOrderBySequenceNoAsc(sessionId);
        for (int i = existing.size() - 1; i >= 0; i--) {
            SpeakingTurn t = existing.get(i);
            t.setSequenceNo(t.getSequenceNo() + 1);
            turnRepository.saveAndFlush(t);
        }
        SpeakingTurn openingTurn = new SpeakingTurn(sessionId, 1, "ASSISTANT", opening,
                objectKey, null, "{\"source\":\"opening\"}");
        turnRepository.save(openingTurn);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("turnId", openingTurn.getId().toString());
        payload.put("text", opening);
        payload.put("audioBase64", audioBase64);
        payload.put("audioFormat", "mp3");
        String audioUrl = "";
        if (objectKey != null) {
            try {
                audioUrl = storage.presignContentUrl(objectKey);
            } catch (Exception e) {
                log.warn("Presign opening audio failed: {}", e.getMessage());
            }
        }
        payload.put("audioUrl", audioUrl);
        return payload;
    }

    /** TUTOR 回复：携带话题，若离题则引导回题；失败返回兜底回复。 */
    private JsonNode tutorReply(String transcript, String topic, UUID userId, UUID sessionId) {
        try {
            return aiGateway.invoke(new AiGateway.Invocation(AiCapability.TUTOR,
                            "You are a friendly English speaking tutor. Keep the conversation on the given topic; if the user goes off-topic, gently bring them back and ask a follow-up question on the topic. "
                                    + "Output a single JSON object with: \"reply\" (your short conversational reply) and \"correction\" (a correction object or null).",
                            Map.of("mode", "conversation", "userText", transcript,
                                    "topic", topic == null ? "" : topic), null,
                            Duration.ofSeconds(5), "tutor-v1"),
                    new AiGateway.ModelInvocationContext(userId, sessionId, null, "STANDARD"))
                    .output();
        } catch (Exception e) {
            log.warn("TUTOR model failed: {}", e.getMessage());
            return mapper.createObjectNode().put("reply",
                    "Sorry, I had trouble understanding. Could you say that again?");
        }
    }

    /** 话题相关性判断：输入话题 + 用户回答，返回 onTopic/relevance/reason/guidance；无话题或失败返回 null。 */
    private JsonNode relevanceNow(String topic, String answer, UUID userId, UUID sessionId) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        try {
            return aiGateway.invoke(new AiGateway.Invocation(AiCapability.TUTOR,
                            "Evaluate topic relevance. Given the topic and the user's answer, judge whether the answer is on-topic. Reply with JSON only: {\"onTopic\":boolean,\"relevance\":number between 0 and 1,\"reason\":\"string\",\"guidance\":\"string to bring the user back on topic\"}.",
                            Map.of("mode", "relevance", "topic", topic, "userAnswer", answer), null,
                            Duration.ofSeconds(5), "relevance-v1"),
                    new AiGateway.ModelInvocationContext(userId, sessionId, null, "STANDARD"))
                    .output();
        } catch (Exception e) {
            log.warn("topic relevance check failed: {}", e.getMessage());
            return null;
        }
    }

    /** 对单句做实时纠错，返回 CORRECTION 能力输出；失败返回空对象。 */
    private JsonNode correctNow(String sentence, UUID userId, UUID sessionId) {
        try {
            return aiGateway.invoke(new AiGateway.Invocation(AiCapability.CORRECTION,
                            "You are an English coach. Find errors in the user's sentence and always quote the original text as evidence. "
                                    + "Output a single JSON object with: \"corrections\" (array of objects each with "
                                    + "\"category\" (one of GRAMMAR/TENSE/VOCABULARY/COLLOCATION/STRUCTURE), \"original\" (the user's text), "
                                    + "\"corrected\" (the corrected text), and \"explanation\" (string)).",
                            Map.of("sentences", List.of(sentence)), null,
                            Duration.ofSeconds(5), "correction-live-v1"),
                    new AiGateway.ModelInvocationContext(userId, sessionId, null, "STANDARD"))
                    .output();
        } catch (Exception e) {
            log.warn("live correction failed: {}", e.getMessage());
            return mapper.createObjectNode();
        }
    }

    /**
     * 处理客户端回传的发音评测结果（阿里云发音评测引擎打完分后回传）：
     * 生成发音反馈事件（总分/准确度/完整度/流利度 + 低分词 + LLM 建议），
     * 并把分数合并进对应 USER 轮次的 metadata 供课后反馈使用。
     */
    @Transactional
    public java.util.List<Map<String, Object>> handlePronunciation(UUID sessionId, JsonNode message) {
        SpeakingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Speaking session"));
        if (SpeakingSession.ENDED.equals(session.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Session already ended");
        }
        JsonNode result = message.path("result");
        if (result.isMissingNode() || result.isNull() || !result.isObject()) {
            throw ApiException.invalid("Missing pronunciation result", "result", "required");
        }
        UUID turnId = null;
        String turnIdText = message.path("turnId").asText("");
        if (!turnIdText.isBlank()) {
            try {
                turnId = UUID.fromString(turnIdText);
            } catch (IllegalArgumentException ignored) {
                turnId = null;
            }
        }

        // 引擎结果兼容两种结构：分数可能在顶层，也可能嵌套在 result 节点下
        int overall = scoreOf(result, "overall");
        int accuracy = scoreOf(result, "accuracy", "pron");
        int integrity = scoreOf(result, "integrity");
        int fluency = scoreOf(result, "fluency");

        // 低分词（<60 分的单词，最多 5 个）
        java.util.List<Map<String, Object>> lowWords = new java.util.ArrayList<>();
        JsonNode details = result.has("details") ? result.path("details") : result.path("result").path("details");
        if (details.isArray()) {
            for (JsonNode d : details) {
                String word = d.path("char").asText("").trim();
                int score = d.path("score").asInt(100);
                if (!word.isBlank() && score < 60) {
                    lowWords.add(Map.of("word", word, "score", score));
                    if (lowWords.size() >= 5) {
                        break;
                    }
                }
            }
        }

        // 建议（LLM 生成，失败回退规则建议）
        java.util.List<String> suggestions = pronunciationSuggestions(result, session.getUserId(), sessionId);

        // 正确整句：优先取用户本轮转写文本（USER 轮次 transcript），其次取引擎结果中的参考文本
        final String[] sentenceHolder = {""};
        if (turnId != null) {
            UUID sentenceTurnId = turnId;
            turnRepository.findById(sentenceTurnId).ifPresent(t ->
                    sentenceHolder[0] = t.getTranscript() == null ? "" : t.getTranscript().trim());
        }
        String sentenceText = sentenceHolder[0];
        if (sentenceText.isBlank()) {
            sentenceText = firstNonBlank(result, "text", "sentence", "refText");
        }
        final String finalSentenceText = sentenceText;

        // 完整反馈合并进 USER 轮次 metadata（续接旧会话时据此恢复发音反馈气泡）
        if (turnId != null) {
            UUID finalTurnId = turnId;
            turnRepository.findById(finalTurnId).ifPresent(t -> {
                if (!sessionId.equals(t.getSessionId()) || !"USER".equals(t.getRole())
                        || t.getMetadataJson() == null) {
                    return;
                }
                try {
                    JsonNode meta = mapper.readTree(t.getMetadataJson());
                    ((com.fasterxml.jackson.databind.node.ObjectNode) meta).set("pronunciation",
                            mapper.valueToTree(Map.of(
                                    "overall", overall, "accuracy", accuracy,
                                    "integrity", integrity, "fluency", fluency,
                                    "lowScoreWords", lowWords,
                                    "sentenceText", finalSentenceText,
                                    "suggestions", suggestions)));
                    t.setMetadataJson(mapper.writeValueAsString(meta));
                    turnRepository.save(t);
                } catch (Exception ignored) {
                    // metadata 合并失败不影响反馈推送
                }
            });
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("turnId", turnId == null ? "" : turnId.toString());
        payload.put("overall", overall);
        payload.put("accuracy", accuracy);
        payload.put("integrity", integrity);
        payload.put("fluency", fluency);
        payload.put("lowScoreWords", lowWords);
        payload.put("suggestions", suggestions);
        payload.put("sentenceText", finalSentenceText);
        // 正确整句语音：默认英文音色合成，点击"播放建议"时先播正确整句，再播建议
        String sentenceAudioBase64 = "";
        if (!finalSentenceText.isBlank()) {
            try {
                SpeechResult sentenceTts = speechGateway.invoke(AiCapability.TTS,
                        SpeechRequest.tts(finalSentenceText, null));
                if (sentenceTts.audio() != null && sentenceTts.audio().length > 0) {
                    sentenceAudioBase64 = Base64.getEncoder().encodeToString(sentenceTts.audio());
                }
            } catch (Exception e) {
                log.warn("Sentence TTS failed: {}", e.getMessage());
            }
        }
        payload.put("sentenceAudioBase64", sentenceAudioBase64);
        // 建议语音：点击播放，只合成"建议"内容（中英混合用 loongbella_v3.1 音色，语速 0.8 便于听清）
        String suggestionsText = String.join("。", suggestions);
        String suggestionsAudioBase64 = "";
        try {
            SpeechResult tts = speechGateway.invoke(AiCapability.TTS,
                    SpeechRequest.tts(suggestionsText, "loongbella_v3.1", 0.8));
            if (tts.audio() != null && tts.audio().length > 0) {
                suggestionsAudioBase64 = Base64.getEncoder().encodeToString(tts.audio());
            }
        } catch (Exception e) {
            log.warn("Suggestions TTS failed: {}", e.getMessage());
        }
        payload.put("suggestionsAudioBase64", suggestionsAudioBase64);
        return java.util.List.of(event("pronunciation", payload));
    }

    /** 从顶层或嵌套的 result 节点取分数（0-100），取不到返回 0。 */
    private static int scoreOf(JsonNode root, String... names) {
        for (String name : names) {
            if (root.has(name)) {
                return root.path(name).asInt(0);
            }
            JsonNode nested = root.path("result");
            if (nested.isObject() && nested.has(name)) {
                return nested.path(name).asInt(0);
            }
        }
        return 0;
    }

    /** 从顶层或嵌套 result 节点取第一个非空字符串。 */
    private static String firstNonBlank(JsonNode root, String... names) {
        for (String name : names) {
            String value = root.path(name).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
            JsonNode nested = root.path("result");
            String nestedValue = nested.isObject() ? nested.path(name).asText("").trim() : "";
            if (!nestedValue.isBlank()) {
                return nestedValue;
            }
        }
        return "";
    }

    /** 发音评测结果 → 改进建议：LLM 生成，失败回退规则建议。 */
    private java.util.List<String> pronunciationSuggestions(JsonNode result, UUID userId, UUID sessionId) {
        try {
            JsonNode ai = aiGateway.invoke(new AiGateway.Invocation(AiCapability.TUTOR,
                            "You are a pronunciation coach. Given the learner's pronunciation scores "
                                    + "(overall/accuracy/integrity/fluency, each 0-100) and word-level details, "
                                    + "give concise actionable improvement tips in Chinese. "
                                    + "Reply with JSON only: {\"suggestions\": [\"tip1\", \"tip2\", ...]}.",
                            Map.of("mode", "pronunciation-feedback", "result", result), null,
                            Duration.ofSeconds(15), "pronunciation-feedback-v1"),
                    new AiGateway.ModelInvocationContext(userId, sessionId, null, "STANDARD"))
                    .output();
            java.util.List<String> suggestions = new java.util.ArrayList<>();
            ai.path("suggestions").forEach(s -> suggestions.add(s.asText("")));
            suggestions.removeIf(String::isBlank);
            if (!suggestions.isEmpty()) {
                return suggestions;
            }
        } catch (Exception e) {
            log.warn("Pronunciation feedback LLM failed, using rule-based fallback: {}", e.getMessage());
        }
        return ruleBasedPronunciationSuggestions(result);
    }

    private java.util.List<String> ruleBasedPronunciationSuggestions(JsonNode result) {
        java.util.List<String> tips = new java.util.ArrayList<>();
        int overall = result.path("overall").asInt(0);
        int accuracy = result.path("accuracy").asInt(0);
        int fluency = result.path("fluency").asInt(0);
        int integrity = result.path("integrity").asInt(0);
        if (overall >= 85) {
            tips.add("发音很棒！继续保持语调和节奏。");
        } else if (overall >= 60) {
            tips.add("整体不错，注意低分单词的发音再练一次。");
        } else {
            tips.add("建议放慢语速，逐词模仿示范发音。");
        }
        if (accuracy < 60) {
            tips.add("准确度偏低：注意单词重音与元音发音。");
        }
        if (fluency < 60) {
            tips.add("流利度偏低：按意群划分句子，减少停顿和重复。");
        }
        if (integrity < 60) {
            tips.add("完整度偏低：尽量把整句读完，不要漏词。");
        }
        return tips;
    }

    /** TTS 合成文本语音，返回 audioBase64 + audioFormat；失败返回空串。 */
    private Map<String, Object> ttsAudio(String text) {
        try {
            SpeechResult r = speechGateway.invoke(AiCapability.TTS, SpeechRequest.tts(text, null));
            if (r.audio() != null && r.audio().length > 0) {
                return Map.of("audioBase64", Base64.getEncoder().encodeToString(r.audio()),
                        "audioFormat", r.audioFormat() == null ? "mp3" : r.audioFormat());
            }
        } catch (Exception e) {
            log.warn("TTS synthesis failed: {}", e.getMessage());
        }
        return Map.of("audioBase64", "", "audioFormat", "mp3");
    }

    /** 提取纠错提示句（无纠正返回 null）。 */
    private String correctionSpoken(JsonNode correction) {
        if (correction == null || !correction.path("corrections").isArray()
                || correction.path("corrections").size() == 0) {
            return null;
        }
        JsonNode first = correction.path("corrections").get(0);
        String original = first.path("original").asText("");
        String corrected = first.path("corrected").asText("");
        return corrected.isBlank() ? null : "You said \"" + original + "\". Try \"" + corrected + "\".";
    }

    public Map<String, Object> event(String type, Map<String, Object> payload) {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("schemaVersion", "1.0");
        envelope.put("type", type);
        envelope.put("occurredAt", java.time.Instant.now().toString());
        envelope.put("payload", payload);
        return envelope;
    }
}
