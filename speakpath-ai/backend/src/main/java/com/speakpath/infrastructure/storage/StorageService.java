package com.speakpath.infrastructure.storage;

import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 私有桶对象存储：预签名上传/下载，object key 强制用户前缀，音频只存对象存储。
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final Pattern SAFE_KEY = Pattern.compile("^[a-zA-Z0-9/._-]{1,400}$");

    private final StorageProperties props;
    private MinioClient client;
    private volatile MinioClient presignClient;

    public StorageService(StorageProperties props) {
        this.props = props;
    }

    /**
     * 生成客户端可达预签名 URL 的客户端：优先 public-endpoint（如宿主机可访问的 localhost），
     * 否则退回内部 endpoint。
     */
    private MinioClient presignClient() {
        if (presignClient == null) {
            synchronized (this) {
                if (presignClient == null) {
                    String publicEndpoint = props.publicEndpoint() == null || props.publicEndpoint().isBlank()
                            ? props.endpoint() : props.publicEndpoint();
                    presignClient = MinioClient.builder()
                            .endpoint(publicEndpoint)
                            .credentials(props.accessKey(), props.secretKey())
                            .region(props.region())
                            .build();
                }
            }
        }
        return presignClient;
    }

    @PostConstruct
    void init() {
        this.client = MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .region(props.region())
                .build();
        // 存储服务可能晚于应用就绪，重试直至可用或超时
        Exception last = null;
        for (int attempt = 1; attempt <= 12; attempt++) {
            try {
                boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(props.bucket()).build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(props.bucket()).build());
                }
                try {
                    // 私有桶策略（S3Mock 等兼容实现可能不支持，尽力而为）
                    client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(props.bucket())
                            .config(privatePolicy()).build());
                } catch (Exception policyError) {
                    log.info("Bucket policy not applied (compatible storage): {}", policyError.getMessage());
                }
                log.info("Storage ready, bucket={}", props.bucket());
                return;
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 存储初始化失败不阻塞启动（上传接口会返回明确错误）
        log.warn("Storage bucket init failed after retries (expected before storage starts): {}",
                last == null ? "unknown" : last.getMessage());
    }

    private String privatePolicy() {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":[\"*\"]},"
                + "\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + props.bucket() + "/*\"]}]}";
    }

    /**
     * 生成用户私有的上传预签名 URL（使用 public-endpoint，客户端可达）。
     */
    public Map<String, Object> presignUpload(UUID userId, String fileName, String contentType) {
        String key = userKey(userId, fileName);
        validateKey(key);
        try {
            String url = presignClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(props.bucket())
                    .object(key)
                    .expiry((int) props.presignExpirySeconds(), TimeUnit.SECONDS)
                    .extraQueryParams(Map.of("X-Amz-Content-Type", contentType == null ? "application/octet-stream" : contentType))
                    .build());
            return Map.of(
                    "objectKey", key,
                    "uploadUrl", url,
                    "expiresInSeconds", props.presignExpirySeconds());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to presign upload");
        }
    }

    public String presignDownload(String objectKey) {
        validateKey(objectKey);
        validateUserPrefix(objectKey);
        try {
            return presignClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(props.bucket())
                    .object(objectKey)
                    .expiry(300, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to presign download");
        }
    }

    /** 服务端直接下载对象内容（走内部 endpoint，供云端 ASR 等需要音频字节的场景）。 */
    public byte[] download(String objectKey) {
        validateKey(objectKey);
        validateUserPrefix(objectKey);
        return downloadRaw(objectKey);
    }

    /** 下载不做用户前缀校验（WebSocket 等无 HTTP 上下文的线程中，由调用方校验归属）。 */
    public byte[] downloadRaw(String objectKey) {
        validateKey(objectKey);
        try (GetObjectResponse resp = client.getObject(GetObjectArgs.builder()
                .bucket(props.bucket())
                .object(objectKey)
                .build())) {
            return resp.readAllBytes();
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to download object");
        }
    }

    /** 用户音频直传（私有前缀，返回 objectKey；短音频走服务端直传避开跨域）。 */
    public String putUserAudio(UUID userId, String fileName, String contentType, byte[] data) {
        String key = userKey(userId, fileName);
        putObject(key, data, contentType);
        return key;
    }

    public boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(props.bucket()).object(objectKey).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 内容音频直传（内部 endpoint，无用户前缀校验；仅限内容预生成音频使用）。 */
    public void putObject(String objectKey, byte[] data, String contentType) {
        validateKey(objectKey);
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .stream(new java.io.ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to upload object");
        }
    }

    /** 内容音频预签名下载 URL（无用户前缀校验；浏览器直接播放）。 */
    public String presignContentUrl(String objectKey) {
        validateKey(objectKey);
        try {
            return presignClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(props.bucket())
                    .object(objectKey)
                    .expiry(3600, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to presign content url");
        }
    }

    public void remove(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(props.bucket()).object(objectKey).build());
        } catch (Exception e) {
            log.warn("Failed to remove object {}: {}", objectKey, e.getMessage());
        }
    }

    /**
     * 校验 object key 属于当前用户私有前缀，防止越权引用他人音频。
     */
    public void validateUserPrefix(String objectKey) {
        UUID userId = com.speakpath.common.security.CurrentUser.id();
        if (userId == null) {
            throw ApiException.forbidden("Authentication required");
        }
        String prefix = "private/" + userId + "/";
        if (!objectKey.startsWith(prefix)) {
            throw ApiException.forbidden("Object key does not belong to current user");
        }
    }

    private String userKey(UUID userId, String fileName) {
        String safe = fileName == null ? "recording.webm" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "private/" + userId + "/" + UUID.randomUUID() + "-" + safe;
    }

    private void validateKey(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            throw ApiException.invalid("Invalid object key", "objectKey", "must match " + SAFE_KEY.pattern());
        }
    }
}
