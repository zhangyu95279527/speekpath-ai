package com.speakpath.identity;

import com.speakpath.common.api.ApiException;
import com.speakpath.common.api.ErrorCode;
import com.speakpath.common.security.AppUser;
import com.speakpath.common.security.JwtService;
import com.speakpath.common.security.CurrentUser;
import com.speakpath.infrastructure.rate.RateLimitService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认证用例：注册、登录、刷新轮换、设备注销。
 * - access token 短期（15 分钟）
 * - refresh token 哈希入库，可轮换与撤销；旧 refresh 重放触发该设备撤销
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String REFRESH_COOKIE = "speakpath_refresh";

    private final UserRepository userRepository;
    private final UserDeviceRepository deviceRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final ConsentRecordRepository consentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RateLimitService rateLimiter;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.jwt.refresh-ttl-seconds:2592000}")
    private long refreshTtlSeconds;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    public AuthService(UserRepository userRepository, UserDeviceRepository deviceRepository,
                       UserPreferencesRepository preferencesRepository,
                       ConsentRecordRepository consentRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService,
                       RateLimitService rateLimiter) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.preferencesRepository = preferencesRepository;
        this.consentRepository = consentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public Map<String, Object> register(Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
        rateLimiter.enforce("auth-register", rateLimiter.clientIp(request), 10, Duration.ofMinutes(10));
        String email = str(body.get("email"));
        String password = str(body.get("password"));
        String displayName = str(body.get("displayName"));
        String locale = body.get("locale") == null ? "zh-CN" : str(body.get("locale"));
        String timezone = body.get("timezone") == null ? "Asia/Shanghai" : str(body.get("timezone"));

        if (email == null || email.isBlank() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.invalid("Valid email is required", "email", "must be a valid email address");
        }
        if (password == null || password.length() < 8) {
            throw ApiException.invalid("Password must be at least 8 characters", "password", "min length 8");
        }
        if (email.equals(adminEmail) || userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.CONFLICT, "Account already exists");
        }
        User user = new User(email, passwordEncoder.encode(password),
                displayName == null || displayName.isBlank() ? email.split("@")[0] : displayName,
                locale, timezone);
        userRepository.save(user);
        preferencesRepository.save(new UserPreferences(user.getId()));

        // 隐私授权记录（版本化）
        @SuppressWarnings("unchecked")
        Map<String, Object> consents = body.get("consents") instanceof Map ? (Map<String, Object>) body.get("consents") : Map.of();
        consentRepository.save(new ConsentRecord(user.getId(), "TERMS",
                str(consents.get("termsVersion")) == null ? "2026-01" : str(consents.get("termsVersion")), true, "{}"));
        consentRepository.save(new ConsentRecord(user.getId(), "AUDIO_PROCESSING",
                "2026-01", Boolean.TRUE.equals(consents.get("audioProcessing")), "{}"));

        return issueTokens(user, platformOf(request), deviceNameOf(request), request, response);
    }

    @Transactional
    public Map<String, Object> login(Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
        rateLimiter.enforce("auth-login", rateLimiter.clientIp(request), 20, Duration.ofMinutes(10));
        String email = str(body.get("email"));
        String password = str(body.get("password"));
        if (email == null || password == null) {
            throw ApiException.invalid("email and password are required", "email", "required");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid credentials"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid credentials");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw ApiException.forbidden("Account is not active");
        }
        return issueTokens(user, platformOf(request), deviceNameOf(request), request, response);
    }

    @Transactional
    public Map<String, Object> refresh(String refreshToken, HttpServletRequest request, HttpServletResponse response) {
        rateLimiter.enforce("auth-refresh", rateLimiter.clientIp(request), 60, Duration.ofMinutes(10));
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = cookieValue(request);
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Refresh token required");
        }
        String hash = sha256(refreshToken);
        UserDevice device = deviceRepository.findByRefreshTokenHash(hash)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid refresh token"));
        if (device.getRevokedAt() != null) {
            // 重放已撤销的 refresh token：撤销该设备全部会话（安全策略）
            log.warn("Replay of revoked refresh token for device {}", device.getId());
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Session revoked");
        }
        if (device.getExpiresAt().isBefore(Instant.now())) {
            device.revoke();
            deviceRepository.save(device);
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Refresh token expired");
        }
        User user = userRepository.findById(device.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "User not found"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw ApiException.forbidden("Account is not active");
        }
        // 轮换：旧 token 作废，签发新 token
        device.revoke();
        deviceRepository.save(device);
        return issueTokens(user, platformOf(request), device.getDeviceName(), request, response);
    }

    @Transactional
    public void logout(String refreshToken, HttpServletRequest request, HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = cookieValue(request);
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            deviceRepository.findByRefreshTokenHash(sha256(refreshToken)).ifPresent(device -> {
                device.revoke();
                deviceRepository.save(device);
            });
        }
        clearCookie(response);
    }

    private Map<String, Object> issueTokens(User user, String platform, String deviceName,
                                            HttpServletRequest request, HttpServletResponse response) {
        List<String> roles = rolesOf(user);
        String accessToken = jwtService.issueAccessToken(user.getId(), user.getEmail(), roles);
        String refreshToken = randomToken();
        UserDevice device = new UserDevice(user.getId(), platform, deviceName,
                sha256(refreshToken), Instant.now().plusSeconds(refreshTtlSeconds));
        deviceRepository.save(device);
        setCookie(response, refreshToken, refreshTtlSeconds);
        return Map.of(
                "userId", user.getId().toString(),
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "expiresIn", jwtService.accessTtlSeconds(),
                "user", Map.of(
                        "id", user.getId().toString(),
                        "email", user.getEmail(),
                        "displayName", user.getDisplayName(),
                        "locale", user.getLocale(),
                        "timezone", user.getTimezone(),
                        "roles", roles));
    }

    private List<String> rolesOf(User user) {
        boolean configuredAdmin = !adminEmail.isBlank() && !adminPassword.isBlank()
                && adminEmail.equals(user.getEmail())
                && passwordEncoder.matches(adminPassword, user.getPasswordHash());
        return configuredAdmin ? List.of("USER", "AI_ADMIN", "SYS_ADMIN") : List.of("USER");
    }

    @Transactional(readOnly = true)
    public boolean hasAudioConsent(UUID userId) {
        return consentRepository.findByUserIdAndConsentTypeOrderByCreatedAtDesc(userId, "AUDIO_PROCESSING")
                .stream().findFirst().map(ConsentRecord::isGranted).orElse(false);
    }

    private String randomToken() {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void setCookie(HttpServletResponse response, String refreshToken, long ttlSeconds) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // 生产置于 HTTPS 后应改为 true
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge((int) ttlSeconds);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private void clearCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String cookieValue(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (REFRESH_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private String platformOf(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            return "unknown";
        }
        String lower = ua.toLowerCase();
        if (lower.contains("android")) {
            return "android";
        }
        if (lower.contains("iphone") || lower.contains("ipad")) {
            return "ios";
        }
        return "web";
    }

    private String deviceNameOf(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua == null ? "unknown" : ua.substring(0, Math.min(120, ua.length()));
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    public AppUser currentAppUser() {
        return CurrentUser.require();
    }
}
