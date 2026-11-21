package com.speakpath.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置（MinIO/S3），与 application(-docker).yml 的 app.storage.* 绑定。
 * endpoint 为服务端内部地址；public-endpoint 用于生成客户端可达的预签名 URL（默认同 endpoint）。
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        boolean pathStyleAccess,
        long presignExpirySeconds,
        String publicEndpoint) {

    public StorageProperties {
        if (presignExpirySeconds <= 0) {
            presignExpirySeconds = 600;
        }
    }
}
