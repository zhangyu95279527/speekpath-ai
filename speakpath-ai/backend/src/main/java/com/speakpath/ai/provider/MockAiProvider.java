package com.speakpath.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.config.AiModelConfig;
import com.speakpath.ai.gateway.AiCapability;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Mock 供应商：确定性、可复现、输出结构符合能力 schema。
 * 用于开发/测试与无密钥环境端到端联调；生产由真实 adapter 替换（灰度切换）。
 */
@Component
public class MockAiProvider implements AiProvider {

    private static final Set<AiCapability> CAPABILITIES = Set.of(
            AiCapability.REALTIME, AiCapability.ASR, AiCapability.TTS,
            AiCapability.PRONUNCIATION, AiCapability.ASSESSMENT,
            AiCapability.PLANNING, AiCapability.CORRECTION, AiCapability.TUTOR);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String providerCode() {
        return "MOCK";
    }

    @Override
    public Set<AiCapability> supportedCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean supports(AiModelConfig config) {
        return "MOCK".equalsIgnoreCase(config.getProviderCode());
    }

    @Override
    public AiResult invoke(AiModelConfig config, AiRequest request) {
        long start = System.currentTimeMillis();
        JsonNode output = switch (request.capability()) {
            case ASR -> asr(request);
            case ASSESSMENT -> assess(request);
            case PLANNING -> planning(request);
            case CORRECTION -> correct(request);
            case TUTOR -> tutor(request);
            case PRONUNCIATION -> pronunciation(request);
            case TTS, REALTIME -> mapper.createObjectNode();
        };
        return new AiResult(providerCode(), config.getModelCode(), output,
                System.currentTimeMillis() - start,
                Map.of("inputTokens", 128L, "outputTokens", 256L, "estimatedCost", 0.0, "currency", "USD", "mock", true),
                "stop");
    }

    // ------------------------------------------------------------------
    // ASR：优先使用客户端随上传提交的文本，否则给出占位转写并标记低置信
    // ------------------------------------------------------------------
    private JsonNode asr(AiRequest request) {
        ObjectNode out = mapper.createObjectNode();
        String text = request.input().get("answerText") instanceof String s && !s.isBlank()
                ? s : request.input().get("transcript") instanceof String s && !s.isBlank() ? s : null;
        if (text != null) {
            out.put("text", text);
            out.put("confidence", 0.98);
            out.put("language", "en");
            out.put("mock", true);
        } else {
            out.put("text", "");
            out.put("confidence", 0.1);
            out.put("lowConfidence", true);
            out.put("language", "en");
            out.put("mock", true);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // ASSESSMENT：基于转写文本特征的确定性评分 + 证据引用
    // ------------------------------------------------------------------
    private JsonNode assess(AiRequest request) {
        @SuppressWarnings("unchecked")
        List<String> transcripts = request.input().get("transcripts") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        List<JsonNode> rubricDims = new ArrayList<>();
        JsonNode rubric = request.input().get("rubric") instanceof JsonNode node ? node : null;
        if (rubric != null) {
            rubric.path("dimensions").forEach(rubricDims::add);
        }
        double scoreMax = rubric == null ? 9 : rubric.path("scoreRange").path("max").asDouble(9);
        String rubricVersion = rubric == null ? "GENERIC_V1" : rubric.path("version").asText("GENERIC_V1");

        ObjectNode out = mapper.createObjectNode();
        out.put("rubricVersion", rubricVersion);
        ArrayNode dimensions = out.putArray("dimensions");
        Map<String, Double> dimScores = new LinkedHashMap<>();

        for (JsonNode dim : rubricDims) {
            String code = dim.path("code").asText();
            String label = dim.path("label").asText(code);
            double raw = features(transcripts, code);
            double score = clamp(round1(scale(raw, scoreMax)), 1, scoreMax);
            dimScores.put(code, score);
            ObjectNode d = dimensions.addObject();
            d.put("code", code);
            d.put("label", label);
            d.put("score", score);
            d.put("confidence", round3(0.55 + Math.min(0.35, seed(transcripts, code) * 0.35)));
            ArrayNode evidence = d.putArray("evidence");
            String sample = transcripts.stream().filter(t -> t.length() > 20).findFirst().orElse("");
            if (!sample.isBlank()) {
                ObjectNode ev = evidence.addObject();
                ev.put("quote", sample.substring(0, Math.min(120, sample.length())));
                ev.put("note", evidenceNote(code, raw));
            }
            d.putArray("recommendations").add(recommendation(code, score, scoreMax));
        }

        if (dimensions.isEmpty()) {
            ObjectNode d = dimensions.addObject();
            d.put("code", "OVERALL");
            d.put("label", "Overall");
            double score = clamp(round1(scale(features(transcripts, "OVERALL"), scoreMax)), 1, scoreMax);
            d.put("score", score);
            d.put("confidence", 0.6);
            d.putArray("evidence");
            d.putArray("recommendations").add("Keep practicing with evidence-based feedback.");
        }

        double avg = dimScores.values().stream().mapToDouble(Double::doubleValue).average().orElse(scoreMax / 2);
        double spread = Math.min(0.5, Math.max(0.1, avg * 0.08));
        ObjectNode estimate = out.putObject("overallEstimate");
        estimate.put("low", round1(Math.max(0, avg - spread)));
        estimate.put("high", round1(Math.min(scoreMax, avg + spread)));
        out.put("confidence", round3(0.5 + Math.min(0.4, seed(transcripts, "conf") * 0.4)));
        ArrayNode limitations = out.putArray("limitations");
        limitations.add("AI 估算仅用于学习参考，不是官方考试成绩。");
        limitations.add("Mock 评分引擎用于开发演示，正式评分请配置真实评估模型。");
        return out;
    }

    private double features(List<String> transcripts, String code) {
        if (transcripts.isEmpty()) {
            return 0;
        }
        String joined = String.join(" ", transcripts);
        int words = joined.split("\\s+").length;
        double wordLen = words == 0 ? 0 : (double) joined.length() / words;
        Set<String> unique = new HashSet<>(Arrays.asList(joined.toLowerCase().split("\\s+")));
        double diversity = words == 0 ? 0 : (double) unique.size() / words;
        double fillers = countOccurrences(joined, "um") + countOccurrences(joined, "uh")
                + countOccurrences(joined, "like") + countOccurrences(joined, "you know");
        double longWords = countLongWords(joined);
        double sentences = Math.max(1, joined.split("[.!?]+").length);
        double wordsPerSentence = words / sentences;
        double detail = Math.min(1, words / 120.0);
        double base = 0.5 + 0.08 * Math.log10(Math.max(2, words)) + (diversity - 0.55) * 0.8
                - Math.min(0.35, fillers * 0.06) + Math.min(0.3, longWords * 0.12);
        return switch (code) {
            case "FLUENCY_COHERENCE" -> clamp01(base - Math.min(0.3, fillers * 0.05) + Math.min(0.15, (wordsPerSentence - 8) / 40));
            case "LEXICAL_RESOURCE" -> clamp01(base + (diversity - 0.5) * 1.2 + Math.min(0.2, longWords * 0.15));
            case "GRAMMAR_RANGE_ACCURACY" -> clamp01(base + Math.min(0.2, (wordsPerSentence - 10) / 50) + (longWords > 2 ? 0.08 : 0));
            case "PRONUNCIATION" -> clamp01(0.55 + Math.min(0.3, seed(transcripts, code) * 0.3));
            case "DELIVERY" -> clamp01(base - Math.min(0.25, fillers * 0.04));
            case "LANGUAGE_USE" -> clamp01(base + (diversity - 0.5) * 1.1);
            case "TOPIC_DEVELOPMENT" -> clamp01(0.4 + detail * 0.5 + Math.min(0.1, (wordsPerSentence - 6) / 40));
            default -> clamp01(base);
        };
    }

    private double scale(double raw, double scoreMax) {
        // 将 0..1 特征映射到 0..scoreMax
        return raw * scoreMax;
    }

    private String evidenceNote(String code, double raw) {
        if (raw < 0.45) {
            return switch (code) {
                case "FLUENCY_COHERENCE" -> "重复与停顿较多，连贯性可提升";
                case "LEXICAL_RESOURCE" -> "词汇多样性不足，重复用词较多";
                default -> "该维度表现有提升空间";
            };
        }
        return switch (code) {
            case "LEXICAL_RESOURCE" -> "用词较丰富，搭配基本准确";
            case "GRAMMAR_RANGE_ACCURACY" -> "句型有一定复杂度，错误率可控";
            default -> "表现稳定";
        };
    }

    private String recommendation(String code, double score, double scoreMax) {
        if (score < scoreMax * 0.5) {
            return switch (code) {
                case "FLUENCY_COHERENCE" -> "使用观点-理由-例子的组织结构，减少停顿与重复";
                case "LEXICAL_RESOURCE" -> "练习同义替换和固定搭配，扩大话题词汇";
                case "GRAMMAR_RANGE_ACCURACY" -> "先保证时态与主谓一致，再逐步升级从句";
                case "PRONUNCIATION" -> "做影子跟读，注意重音与语调";
                default -> "围绕题目展开并给出具体例子";
            };
        }
        return switch (code) {
            case "LEXICAL_RESOURCE" -> "挑战更高级搭配与习语，注意语域";
            case "GRAMMAR_RANGE_ACCURACY" -> "尝试复杂句与虚拟语气，提升语法广度";
            default -> "保持当前水平并挑战更高难度话题";
        };
    }

    // ------------------------------------------------------------------
    // PLANNING：返回生成理由与结构说明（任务装箱由约束求解器保证）
    // ------------------------------------------------------------------
    private JsonNode planning(AiRequest request) {
        ObjectNode out = mapper.createObjectNode();
        out.put("summary", "基于能力画像与目标差距生成的每日训练计划。");
        out.put("structure", "warmup 10-15% / skill 25-30% / scenario 30-40% / correction 15-20% / quiz 5-10%");
        out.put("mock", true);
        return out;
    }

    // ------------------------------------------------------------------
    // CORRECTION：规则化错误检测（时态/主谓一致/搭配），输出证据与改写
    // ------------------------------------------------------------------
    private JsonNode correct(AiRequest request) {
        @SuppressWarnings("unchecked")
        List<String> sentences = request.input().get("sentences") instanceof List<?> list
                ? list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList() : List.of();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode corrections = out.putArray("corrections");

        List<Pattern> rules = List.of(
                Pattern.compile("(?i)\\byesterday\\s+\\w+\\s+(go|come|eat|see|do)\\b"),
                Pattern.compile("(?i)\\blast\\s+\\w+\\s+(go|come|eat|see|do)\\b"),
                Pattern.compile("(?i)\\b(he|she|it)\\s+(go|do|have|come|eat)\\b"),
                Pattern.compile("(?i)\\b(i|we|they)\\s+(goes|does|has|comes)\\b"),
                Pattern.compile("(?i)\\b(didn't|did not)\\s+(went|came|ate|saw|did)\\b"),
                Pattern.compile("(?i)\\b(more better|more easier|most best)\\b"),
                Pattern.compile("(?i)\\b(i am agree|i am interesting in)\\b"),
                Pattern.compile("(?i)\\b(make a party|do a mistake|make homework)\\b"));

        for (String sentence : sentences) {
            boolean matched = false;
            for (Pattern rule : rules) {
                var matcher = rule.matcher(sentence);
                if (matcher.find()) {
                    String bad = matcher.group(0);
                    String good = fix(bad);
                    ObjectNode c = corrections.addObject();
                    c.put("category", categoryOf(bad));
                    c.put("severity", "MEDIUM");
                    c.put("original", sentence);
                    c.put("corrected", sentence.replace(bad, good));
                    c.put("explanation", explain(bad));
                    c.put("confidence", 0.92);
                    c.put("mock", true);
                    matched = true;
                    break;
                }
            }
            if (!matched && corrections.size() < 3) {
                ObjectNode c = corrections.addObject();
                c.put("category", "EXPRESSION");
                c.put("severity", "LOW");
                c.put("original", sentence);
                c.put("corrected", sentence);
                c.put("explanation", "表达通顺，可尝试更地道的说法（如使用更具体的动词与搭配）。");
                c.put("confidence", 0.6);
                c.put("mock", true);
            }
        }
        out.put("summary", sentences.isEmpty() ? "本轮无有效语音输入，建议重录或使用文本模式。"
                : "共检查 " + sentences.size() + " 句，发现 " + corrections.size() + " 处可改进点。");
        return out;
    }

    private String categoryOf(String bad) {
        String lower = bad.toLowerCase();
        if (lower.contains("yesterday") || lower.contains("last ") || lower.contains("didn't")) {
            return "TENSE";
        }
        if (lower.contains("more better") || lower.contains("most best")) {
            return "GRAMMAR";
        }
        if (lower.contains("goes") || lower.contains("does") || lower.contains("has") || lower.contains("go ")) {
            return "SUBJECT_VERB";
        }
        if (lower.contains("make a party") || lower.contains("do a mistake") || lower.contains("make homework")) {
            return "COLLOCATION";
        }
        return "EXPRESSION";
    }

    private String fix(String bad) {
        String lower = bad.toLowerCase();
        if (lower.startsWith("yesterday") || lower.startsWith("last ")) {
            return Pattern.compile("(?i)(go|come|eat|see|do)$").matcher(bad).replaceFirst(m -> {
                return switch (m.group(1).toLowerCase()) {
                    case "go" -> "went";
                    case "come" -> "came";
                    case "eat" -> "ate";
                    case "see" -> "saw";
                    case "do" -> "did";
                    default -> m.group(1);
                };
            });
        }
        if (lower.startsWith("didn't") || lower.startsWith("did not")) {
            return Pattern.compile("(?i)(went|came|ate|saw|did)$").matcher(bad).replaceFirst(m -> {
                return switch (m.group(1).toLowerCase()) {
                    case "went" -> "go";
                    case "came" -> "come";
                    case "ate" -> "eat";
                    case "saw" -> "see";
                    case "did" -> "do";
                    default -> m.group(1);
                };
            });
        }
        if (lower.contains("goes")) {
            return bad.replaceFirst("(?i)goes", "go");
        }
        if (lower.contains("does")) {
            return bad.replaceFirst("(?i)does", "do");
        }
        if (lower.contains("has")) {
            return bad.replaceFirst("(?i)has", "have");
        }
        if (lower.matches("(?i).*\\bgo\\b")) {
            return bad.replaceFirst("(?i)\\bgo\\b", "goes");
        }
        if (lower.contains("more better")) {
            return bad.replaceFirst("(?i)more better", "much better");
        }
        if (lower.contains("more easier")) {
            return bad.replaceFirst("(?i)more easier", "much easier");
        }
        if (lower.contains("most best")) {
            return bad.replaceFirst("(?i)most best", "best");
        }
        if (lower.contains("i am agree")) {
            return bad.replaceFirst("(?i)i am agree", "I agree");
        }
        if (lower.contains("i am interesting in")) {
            return bad.replaceFirst("(?i)i am interesting in", "I am interested in");
        }
        if (lower.contains("make a party")) {
            return bad.replaceFirst("(?i)make a party", "throw a party");
        }
        if (lower.contains("do a mistake")) {
            return bad.replaceFirst("(?i)do a mistake", "make a mistake");
        }
        if (lower.contains("make homework")) {
            return bad.replaceFirst("(?i)make homework", "do homework");
        }
        return bad;
    }

    private String explain(String bad) {
        String lower = bad.toLowerCase();
        if (lower.contains("yesterday") || lower.contains("last ")) {
            return "过去时间状语要求使用一般过去时。";
        }
        if (lower.contains("didn't")) {
            return "did/didn't 后应接动词原形。";
        }
        if (lower.contains("goes") || lower.contains("does") || lower.contains("has")) {
            return "主谓一致：复数主语后动词不加 -s。";
        }
        if (lower.contains("go ") && lower.matches("(?i)(he|she|it).*")) {
            return "主谓一致：第三人称单数主语后动词应加 -s。";
        }
        if (lower.contains("more better") || lower.contains("more easier") || lower.contains("most best")) {
            return "比较级/最高级形式重复，better/best 本身已含比较含义。";
        }
        if (lower.contains("i am agree")) {
            return "agree 是动词，直接说 I agree。";
        }
        if (lower.contains("i am interesting in")) {
            return "表示“感兴趣”用 interested，interesting 形容事物。";
        }
        if (lower.contains("make a party") || lower.contains("do a mistake") || lower.contains("make homework")) {
            return "固定搭配：throw a party / make a mistake / do homework。";
        }
        return "表达可以更地道。";
    }

    // ------------------------------------------------------------------
    // TUTOR：对话陪练，简短纠错 + 追问
    // ------------------------------------------------------------------
    private JsonNode tutor(AiRequest request) {
        String mode = request.input().get("mode") instanceof String s ? s : "conversation";
        if ("relevance".equals(mode)) {
            return relevanceCheck(request);
        }
        String userText = request.input().get("userText") instanceof String s ? s : "";
        ObjectNode out = mapper.createObjectNode();
        String reply = buildTutorReply(userText, request.input());
        out.put("reply", reply);
        ObjectNode correction = out.putObject("correction");
        if (!userText.isBlank() && userText.toLowerCase().contains("yesterday") && userText.toLowerCase().matches(".*\\b(go|come|eat|see)\\b.*")) {
            correction.put("original", userText);
            correction.put("suggestion", userText.replaceFirst("(?i)\\b(go|come|eat|see)\\b", "went"));
            correction.put("note", "过去时间状语应使用过去时。");
        } else {
            correction.putNull("original");
        }
        return out;
    }

    /** mock 话题相关性：基于话题与回答的关键词重叠度。 */
    private JsonNode relevanceCheck(AiRequest request) {
        String topic = request.input().get("topic") instanceof String s ? s : "";
        String answer = request.input().get("userAnswer") instanceof String s ? s : "";
        ObjectNode out = mapper.createObjectNode();
        double rel = keywordOverlap(topic, answer);
        out.put("onTopic", rel >= 0.5);
        out.put("relevance", Math.round(rel * 100.0) / 100.0);
        out.put("reason", rel >= 0.5 ? "" : "回答偏离了话题「" + topic + "」");
        out.put("guidance", rel >= 0.5 ? "" : "Let's come back to \"" + topic + "\".");
        out.put("mock", true);
        return out;
    }

    private double keywordOverlap(String topic, String answer) {
        String[] topicWords = topic.toLowerCase().split("\\W+");
        String[] answerWords = answer.toLowerCase().split("\\W+");
        Set<String> tset = new HashSet<>(Arrays.asList(topicWords));
        Set<String> aset = new HashSet<>(Arrays.asList(answerWords));
        tset.removeIf(String::isBlank);
        if (tset.isEmpty()) {
            return 1.0;
        }
        int overlap = 0;
        for (String w : tset) {
            if (aset.contains(w)) {
                overlap++;
            }
        }
        return (double) overlap / tset.size();
    }

    private String buildTutorReply(String userText, Map<String, Object> input) {
        String topic = input.get("topic") instanceof String s ? s : "";
        if (userText.isBlank()) {
            return "Hello! I'm your speaking tutor. " + (topic.isBlank()
                    ? "What would you like to talk about today?" : "Let's practice: " + topic + ". Go ahead!");
        }
        String[] followUps = {
                "Interesting! Can you tell me more about that?",
                "I see. What happened next?",
                "That's a good point. Why do you think so?",
                "Nice! How did that make you feel?",
                "Got it. Would you recommend that to others? Why?",
                "Thanks for sharing! What would you do differently next time?"
        };
        int idx = Math.floorMod(userText.hashCode(), followUps.length);
        return followUps[idx] + " (Try to use a complex sentence this time.)";
    }

    private JsonNode pronunciation(AiRequest request) {
        ObjectNode out = mapper.createObjectNode();
        boolean hasAudio = request.input().get("hasAudio") == Boolean.TRUE;
        if (!hasAudio) {
            out.put("available", false);
            out.put("note", "无音频证据，不生成音素级评分（规格书 SCORE-002）。");
            return out;
        }
        out.put("available", true);
        out.put("confidence", 0.55);
        out.put("intelligibility", 0.8);
        out.put("mock", true);
        return out;
    }

    // ------------------------------------------------------------------
    private double seed(List<String> transcripts, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(code.getBytes(StandardCharsets.UTF_8));
            md.update(String.join("|", transcripts).getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest();
            int value = (hash[0] & 0xFF) << 8 | (hash[1] & 0xFF);
            return value / 65536.0;
        } catch (Exception e) {
            return 0.5;
        }
    }

    private int countOccurrences(String text, String word) {
        String lower = text.toLowerCase();
        int count = 0;
        int idx = 0;
        while ((idx = lower.indexOf(word, idx)) >= 0) {
            count++;
            idx += word.length();
        }
        return count;
    }

    private int countLongWords(String text) {
        int count = 0;
        for (String w : text.split("\\s+")) {
            if (w.replaceAll("[^a-zA-Z]", "").length() >= 8) {
                count++;
            }
        }
        return count;
    }

    private double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
