package com.speakpath.ai.speech;

import java.util.Map;

/**
 * 单个厂商在运行时的语音配置（endpoint + 密钥），由配置与密钥解析器组装，不落库。
 */
public record SpeechConfig(
        String vendorCode,
        String baseUrl,
        String apiKey,
        String appId,
        String appSecret,
        Map<String, String> options,
        int timeoutMs) {

    public String opt(String key) {
        return options == null ? null : options.get(key);
    }

    public String opt(String key, String defaultValue) {
        String v = opt(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }
}
