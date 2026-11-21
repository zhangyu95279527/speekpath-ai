package com.speakpath.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.config.AiModelConfig;
import com.speakpath.ai.gateway.AiCapability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mock 供应商契约测试：输出结构符合能力 schema。
 */
class MockAiProviderTest {

    private final MockAiProvider provider = new MockAiProvider();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void assessmentOutputConformsToReportSchema() throws Exception {
        ObjectNode rubric = mapper.createObjectNode();
        rubric.put("version", "IELTS_2026_V1");
        rubric.putObject("scoreRange").put("min", 0).put("max", 9);
        rubric.putArray("dimensions")
                .addObject().put("code", "FLUENCY_COHERENCE").put("label", "Fluency & Coherence")
                .put("weight", 0.2);

        AiProvider.AiResult result = provider.invoke(config("ASSESSMENT"), new AiProvider.AiRequest(
                AiCapability.ASSESSMENT, null,
                Map.of("transcripts", List.of("I think travelling is wonderful because it opens your mind.",
                        "Last year I visited three countries and learned a lot about different cultures."),
                        "rubric", rubric),
                null, Duration.ofSeconds(10), "test"));

        JsonNode out = result.output();
        assertTrue(out.hasNonNull("overallEstimate"));
        assertTrue(out.path("overallEstimate").has("low") && out.path("overallEstimate").has("high"));
        assertTrue(out.path("confidence").asDouble() >= 0 && out.path("confidence").asDouble() <= 1);
        assertTrue(out.path("dimensions").isArray() && !out.path("dimensions").isEmpty());
        assertTrue(out.path("dimensions").get(0).hasNonNull("code"));
        assertTrue(out.path("dimensions").get(0).hasNonNull("score"));
    }

    @Test
    void correctionOutputContainsOriginalEvidence() throws Exception {
        AiProvider.AiResult result = provider.invoke(config("CORRECTION"), new AiProvider.AiRequest(
                AiCapability.CORRECTION, null,
                Map.of("sentences", List.of("Yesterday I go to work.")),
                null, Duration.ofSeconds(10), "test"));
        JsonNode out = result.output();
        assertTrue(out.hasNonNull("summary"));
        assertTrue(out.path("corrections").isArray());
        JsonNode first = out.path("corrections").get(0);
        assertTrue(first.hasNonNull("original"));
        assertTrue(first.path("original").asText().contains("Yesterday I go"));
    }

    private AiModelConfig config(String capability) throws Exception {
        Constructor<AiModelConfig> ctor = AiModelConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        AiModelConfig config = ctor.newInstance();
        var providerField = AiModelConfig.class.getDeclaredField("providerCode");
        providerField.setAccessible(true);
        providerField.set(config, "MOCK");
        var modelField = AiModelConfig.class.getDeclaredField("modelCode");
        modelField.setAccessible(true);
        modelField.set(config, "mock-" + capability.toLowerCase() + "-v1");
        return config;
    }
}
