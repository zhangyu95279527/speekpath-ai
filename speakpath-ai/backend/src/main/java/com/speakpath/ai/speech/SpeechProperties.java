package com.speakpath.ai.speech;

import com.speakpath.ai.gateway.AiCapability;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 语音能力配置（app.speech.*）：按能力指定 provider（厂商）+ 各厂商的 model（模型/服务名）+ 密钥。
 * 结构：{capability}.provider 选择厂商；{capability}.vendors.{vendor}.model 指定该厂商下的具体模型/服务。
 * 密钥采用「能力前缀 + 厂商」的环境变量命名（ASR_DASHSCOPE_API_KEY 等）。
 */
@ConfigurationProperties(prefix = "app.speech")
public class SpeechProperties {

    private final Map<String, CapabilityConfig> capabilities = new HashMap<>();

    public Map<String, CapabilityConfig> getCapabilities() {
        return capabilities;
    }

    public CapabilityConfig forCapability(AiCapability capability) {
        return capabilities.get(keyOf(capability));
    }

    public static String keyOf(AiCapability capability) {
        return switch (capability) {
            case ASR -> "asr";
            case TTS -> "tts";
            case PRONUNCIATION -> "pronunciation";
            case REALTIME -> "realtime";
            default -> capability.name().toLowerCase();
        };
    }

    /** 单个能力配置：provider 直接指定使用哪个厂商（mock / dashscope / xfyun / volcengine / tencent）。 */
    public static class CapabilityConfig {
        private String provider = "mock";
        private final Map<String, Map<String, String>> vendors = new HashMap<>();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public Map<String, Map<String, String>> getVendors() {
            return vendors;
        }

        public Map<String, String> vendor(String vendorCode) {
            return vendors.getOrDefault(vendorCode.toLowerCase(), Map.of());
        }

        /** 组装运行时 SpeechConfig。 */
        public SpeechConfig configFor(String vendorCode, int defaultTimeoutMs) {
            Map<String, String> v = vendor(vendorCode);
            return new SpeechConfig(
                    vendorCode.toUpperCase(),
                    v.get("base-url"),
                    v.get("api-key"),
                    v.get("app-id"),
                    v.getOrDefault("api-secret", v.get("secret-key")),
                    v,
                    parseInt(v.get("timeout-ms"), defaultTimeoutMs));
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
