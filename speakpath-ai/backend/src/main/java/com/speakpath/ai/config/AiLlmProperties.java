package com.speakpath.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文本 LLM 配置（app.ai.llm.*）：通过配置直接指定 provider + model + base-url，
 * 覆盖 DB 中 ai_model_configs 的自动选择（优先级/灰度机制）。
 * provider 留空时回退到 DB 配置（原有行为）。
 */
@ConfigurationProperties(prefix = "app.ai.llm")
public class AiLlmProperties {

    private String provider = "";
    private String model = "";
    private String baseUrl = "";

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isConfigured() {
        return provider != null && !provider.isBlank();
    }
}
