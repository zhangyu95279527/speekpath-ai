package com.speakpath.correction;

import java.util.List;
import java.util.Locale;

/**
 * 各考试口语评分维度：纠错分类与错误分布按用户当前计划的考试标准归一。
 * 维度 code 与 exam_templates.rubric_json 保持一致。
 */
public final class ExamSpeakingCriteria {

    public record Criterion(String code, String label) {
    }

    /** IELTS 口语评分维度（发音由引擎单独评测，不作文本纠错分类）。 */
    private static final List<Criterion> IELTS = List.of(
            new Criterion("FLUENCY_COHERENCE", "流利与连贯"),
            new Criterion("LEXICAL_RESOURCE", "词汇资源"),
            new Criterion("GRAMMAR_RANGE_ACCURACY", "语法多样性与准确性"),
            new Criterion("TASK_ACHIEVEMENT", "话题完成度"));

    /** TOEFL 口语评分维度。 */
    private static final List<Criterion> TOEFL = List.of(
            new Criterion("DELIVERY", "表达流畅度"),
            new Criterion("LANGUAGE_USE", "语言运用"),
            new Criterion("TOPIC_DEVELOPMENT", "话题展开"));

    private ExamSpeakingCriteria() {
    }

    public static boolean isToefl(String examCode) {
        return examCode != null && examCode.toUpperCase(Locale.ROOT).startsWith("TOEFL");
    }

    public static List<Criterion> of(String examCode) {
        return isToefl(examCode) ? TOEFL : IELTS;
    }

    public static String examName(String examCode) {
        return isToefl(examCode) ? "TOEFL" : "IELTS";
    }

    public static String codes(String examCode) {
        return of(examCode).stream().map(Criterion::code).reduce((a, b) -> a + "/" + b).orElse("");
    }

    public static String descriptions(String examCode) {
        return of(examCode).stream().map(c -> c.code() + " (" + c.label() + ")")
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    public static String label(String examCode, String code) {
        return of(examCode).stream().filter(c -> c.code().equals(code)).map(Criterion::label)
                .findFirst().orElse(code);
    }

    /** 旧版通用纠错类别（GRAMMAR/TENSE/...）→ 当前考试维度；已是维度 code 的原样返回。 */
    public static String normalize(String examCode, String category) {
        String code = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        boolean toefl = isToefl(examCode);
        return switch (code) {
            case "FLUENCY_COHERENCE", "LEXICAL_RESOURCE", "GRAMMAR_RANGE_ACCURACY", "TASK_ACHIEVEMENT",
                 "DELIVERY", "LANGUAGE_USE", "TOPIC_DEVELOPMENT", "PRONUNCIATION" -> code;
            case "VOCABULARY", "COLLOCATION" -> toefl ? "LANGUAGE_USE" : "LEXICAL_RESOURCE";
            case "STRUCTURE" -> toefl ? "TOPIC_DEVELOPMENT" : "FLUENCY_COHERENCE";
            case "EXPRESSION" -> toefl ? "TOPIC_DEVELOPMENT" : "TASK_ACHIEVEMENT";
            default -> toefl ? "LANGUAGE_USE" : "GRAMMAR_RANGE_ACCURACY";
        };
    }
}
