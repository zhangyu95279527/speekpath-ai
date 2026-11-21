package com.speakpath.exam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ExamCatalogService {

    private final ExamTemplateRepository templateRepository;
    private final QuestionBankRepository questionRepository;
    private final ObjectMapper objectMapper;

    public ExamCatalogService(ExamTemplateRepository templateRepository,
                              QuestionBankRepository questionRepository,
                              ObjectMapper objectMapper) {
        this.templateRepository = templateRepository;
        this.questionRepository = questionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 考试目录：只返回 published 模板。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String examCode : templateRepository.findPublishedExamCodes()) {
            ExamTemplate latest = templateRepository.findPublishedByExamCode(examCode).get(0);
            JsonNode config = read(latest.getConfigJson());
            List<String> sections = new ArrayList<>();
            config.path("sections").forEach(s -> sections.add(s.path("code").asText()));
            result.add(Map.of(
                    "examCode", latest.getExamCode(),
                    "name", latest.getDisplayName(),
                    "sections", sections,
                    "publishedVersion", latest.getVersion(),
                    "minTurns", config.path("minTurns").asInt(10),
                    "defaultTurns", config.path("defaultTurns").asInt(12),
                    "maxTurns", config.path("maxTurns").asInt(15),
                    "scoreRange", Map.of(
                            "min", config.path("scoreRange").path("min").asDouble(0),
                            "max", config.path("scoreRange").path("max").asDouble(9))));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ExamTemplate publishedTemplate(String examCode) {
        List<ExamTemplate> templates = templateRepository.findPublishedByExamCode(examCode);
        if (templates.isEmpty()) {
            throw ApiException.invalid("Unknown exam code", "examCode", "no published template for " + examCode);
        }
        return templates.get(0);
    }

    @Transactional(readOnly = true)
    public List<QuestionBankItem> questions(String examCode) {
        List<QuestionBankItem> items = questionRepository.findByExamCodeAndStatusOrderBySectionCodeAscDifficultyAsc(examCode, "PUBLISHED");
        if (items.isEmpty()) {
            throw ApiException.invalid("No published questions for exam", "examCode", examCode);
        }
        return items;
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid exam template JSON", e);
        }
    }
}
