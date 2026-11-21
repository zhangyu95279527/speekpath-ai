package com.speakpath.ai.speech;

import com.speakpath.ai.gateway.AiCapability;

import java.util.Set;

/**
 * 语音能力供应商 SPI：ASR / TTS / 发音评测 / 实时语音。
 * 每家厂商（DashScope / 讯飞 / 火山引擎 / 腾讯云）实现各自的协议，统一输出 {@link SpeechResult}。
 */
public interface SpeechProvider {

    /** 厂商编码：DASHSCOPE / XFYUN / VOLCENGINE / TENCENT / MOCK。 */
    String vendorCode();

    /** 该厂商支持的语音能力子集。 */
    Set<AiCapability> supportedCapabilities();

    default boolean supports(AiCapability capability) {
        return supportedCapabilities().contains(capability);
    }

    SpeechResult invoke(SpeechRequest request, SpeechConfig config);
}
