package com.speakpath.infrastructure.rate;

import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 固定窗口限流（Redis）：登录、注册、验证码、实时凭证、上传等分别限流。
 * Redis 不可用时降级放行（fail-open），仅记录告警日志。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final StringRedisTemplate redis;

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @param bucket 限流桶名（如 auth-login）
     * @param key    维度（如 IP）
     * @param limit  窗口内最大次数
     */
    public void enforce(String bucket, String key, int limit, Duration window) {
        String redisKey = "rl:" + bucket + ":" + key;
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1) {
                redis.expire(redisKey, window);
            }
            if (count != null && count > limit) {
                throw new ApiException(ErrorCode.RATE_LIMITED, "Too many requests, please retry later");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Rate limit backend unavailable, fail-open: {}", e.getMessage());
        }
    }

    public String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
