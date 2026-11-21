package com.speakpath.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 密钥引用解析：secretRef 形如 env:openai-api-key，仅在服务端解析，明文不落 DB。
 */
@Component
public class SecretResolver {

    @Value("${app.ai.secrets.openai-api-key:}")
    private String openaiApiKey;

    @Value("${app.ai.secrets.azure-speech-key:}")
    private String azureSpeechKey;

    @Value("${app.ai.secrets.google-api-key:}")
    private String googleApiKey;

    // 国内模型厂商（均为 OpenAI 兼容协议）
    @Value("${app.ai.secrets.deepseek-api-key:}")
    private String deepseekApiKey;

    @Value("${app.ai.secrets.dashscope-api-key:}")
    private String dashscopeApiKey;

    @Value("${app.ai.secrets.zhipu-api-key:}")
    private String zhipuApiKey;

    @Value("${app.ai.secrets.moonshot-api-key:}")
    private String moonshotApiKey;

    @Value("${app.ai.secrets.ark-api-key:}")
    private String arkApiKey;

    public String resolve(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return null;
        }
        return switch (secretRef) {
            case "env:openai-api-key" -> openaiApiKey;
            case "env:azure-speech-key" -> azureSpeechKey;
            case "env:google-api-key" -> googleApiKey;
            case "env:deepseek-api-key" -> deepseekApiKey;
            case "env:dashscope-api-key" -> dashscopeApiKey;
            case "env:zhipu-api-key" -> zhipuApiKey;
            case "env:moonshot-api-key" -> moonshotApiKey;
            case "env:ark-api-key" -> arkApiKey;
            default -> null;
        };
    }

    public boolean hasSecret(String secretRef) {
        String value = resolve(secretRef);
        return value != null && !value.isBlank();
    }
}
