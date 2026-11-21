package com.speakpath.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.speakpath.ai.config.AiModelConfig;
import com.speakpath.ai.config.SecretResolver;
import com.speakpath.ai.gateway.AiCapability;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * OpenAI-compatible Chat Completions adapter（文本能力）。
 * 明确不宣称支持 Realtime / 音素级评估（规格书 AIGW-005）。
 */
@Component
public class OpenAiCompatibleProvider implements AiProvider {

    private static final Set<AiCapability> CAPABILITIES = Set.of(
            AiCapability.ASSESSMENT, AiCapability.PLANNING, AiCapability.CORRECTION, AiCapability.TUTOR);

    /**
     * 走 OpenAI 兼容协议（/chat/completions）的厂商编码。
     * 国内厂商默认均兼容该协议，无需为每家单独实现 adapter。
     */
    private static final Set<String> COMPATIBLE_PROVIDERS = Set.of(
            "OPENAI_COMPATIBLE", "DEEPSEEK", "DASHSCOPE", "ZHIPU", "MOONSHOT", "ARK", "MINIMAX");

    private final SecretResolver secretResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleProvider(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    @Override
    public String providerCode() {
        return "OPENAI_COMPATIBLE";
    }

    @Override
    public Set<AiCapability> supportedCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean supports(AiModelConfig config) {
        return config.getProviderCode() != null
                && COMPATIBLE_PROVIDERS.contains(config.getProviderCode().toUpperCase());
    }

    @Override
    public AiResult invoke(AiModelConfig config, AiRequest request) {
        String apiKey = secretResolver.resolve(config.getSecretRef());
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Provider secret is not configured");
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Provider baseUrl is not configured");
        }
        long start = System.currentTimeMillis();
        JsonNode params = parse(config.getParametersJson());
        String responseFormat = params.path("responseFormat").asText("json_object");
        boolean wantJson = !"none".equalsIgnoreCase(responseFormat);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModelCode());
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        String systemPrompt = request.systemPrompt() == null ? "You are a helpful assistant." : request.systemPrompt();
        if (wantJson && !"json_schema".equalsIgnoreCase(responseFormat)) {
            // 文本能力默认要求 JSON 输出；国内厂商的 json_object 模式通常要求提示词中出现 JSON 字样
            systemPrompt = systemPrompt + " Respond with a single valid JSON object only, no markdown or extra text.";
        }
        sys.put("content", systemPrompt);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", mapper.valueToTree(request.input()).toString());
        if (params.hasNonNull("temperature")) {
            body.put("temperature", params.path("temperature").asDouble());
        }
        if (params.hasNonNull("maxTokens")) {
            body.put("max_tokens", params.path("maxTokens").asInt());
        }
        if (wantJson) {
            ObjectNode fmt = body.putObject("response_format");
            if ("json_schema".equalsIgnoreCase(responseFormat) && request.outputSchema() != null) {
                fmt.put("type", "json_schema");
                fmt.set("json_schema", request.outputSchema());
            } else {
                // DeepSeek / 通义千问 / 智谱 / Kimi 均支持 json_object，兼容性最好
                fmt.put("type", "json_object");
            }
        }

        try {
            String raw = WebClient.create(config.getBaseUrl())
                    .post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            resp -> resp.bodyToMono(String.class)
                                    .flatMap(err -> Mono.error(new ApiException(ErrorCode.AI_PROVIDER_ERROR,
                                            "Provider error " + resp.statusCode().value()))))
                    .bodyToMono(String.class)
                    .block(request.timeout());
            JsonNode json = mapper.readTree(raw);
            String content = json.path("choices").path(0).path("message").path("content").asText("");
            JsonNode output = extractJson(content);
            return new AiResult(providerCode(), config.getModelCode(), output,
                    System.currentTimeMillis() - start,
                    Map.of("inputTokens", json.path("usage").path("prompt_tokens").asLong(0),
                            "outputTokens", json.path("usage").path("completion_tokens").asLong(0),
                            "estimatedCost", 0, "currency", "USD"),
                    json.path("choices").path(0).path("finish_reason").asText("stop"));
        } catch (WebClientResponseException e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_ERROR, "Provider request failed: " + e.getStatusCode());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_PROVIDER_TIMEOUT, "Provider request failed");
        }
    }

    private JsonNode extractJson(String content) {
        try {
            return mapper.readTree(content);
        } catch (Exception e) {
            String trimmed = content.trim();
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return mapper.readTree(trimmed.substring(start, end + 1));
                } catch (Exception ignored) {
                    // fall through
                }
            }
            throw new ApiException(ErrorCode.AI_OUTPUT_INVALID, "Provider output is not valid JSON");
        }
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
