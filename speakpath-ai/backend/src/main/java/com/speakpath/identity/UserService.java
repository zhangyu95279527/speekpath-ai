package com.speakpath.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.security.AppUser;
import com.speakpath.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final UserDeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    public UserService(UserRepository userRepository, UserPreferencesRepository preferencesRepository,
                       UserDeviceRepository deviceRepository, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.preferencesRepository = preferencesRepository;
        this.deviceRepository = deviceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> me() {
        AppUser current = CurrentUser.require();
        User user = userRepository.findById(current.id())
                .orElseThrow(() -> ApiException.notFound("User"));
        UserPreferences prefs = preferencesRepository.findByUserId(current.id())
                .orElseGet(() -> new UserPreferences(current.id()));
        Map<String, Object> prefMap = new java.util.LinkedHashMap<>();
        prefMap.put("dailyMinutes", prefs.getDailyMinutes());
        prefMap.put("restDays", safeJson(prefs.getRestDays()));
        prefMap.put("targetExam", prefs.getTargetExam() == null ? "" : prefs.getTargetExam());
        prefMap.put("targetScore", prefs.getTargetScore());
        prefMap.put("examDate", prefs.getExamDate() == null ? "" : prefs.getExamDate().toString());
        prefMap.put("preferenceJson", safeJson(prefs.getPreferenceJson()));

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", user.getId().toString());
        result.put("email", user.getEmail() == null ? "" : user.getEmail());
        result.put("displayName", user.getDisplayName() == null ? "" : user.getDisplayName());
        result.put("locale", user.getLocale());
        result.put("timezone", user.getTimezone());
        result.put("status", user.getStatus());
        result.put("roles", current.roles());
        result.put("createdAt", user.getCreatedAt().toString());
        result.put("preferences", prefMap);
        return result;
    }

    @Transactional
    public Map<String, Object> updatePreferences(Map<String, Object> body) {
        UUID userId = CurrentUser.id();
        UserPreferences prefs = preferencesRepository.findByUserId(userId)
                .orElseGet(() -> new UserPreferences(userId));
        if (body.containsKey("dailyMinutes")) {
            int minutes = ((Number) body.get("dailyMinutes")).intValue();
            if (minutes < 5 || minutes > 300) {
                throw ApiException.invalid("dailyMinutes must be between 5 and 300", "dailyMinutes", "must be >= 5 and <= 300");
            }
            prefs.setDailyMinutes(minutes);
        }
        if (body.containsKey("restDays")) {
            try {
                prefs.setRestDays(objectMapper.writeValueAsString(body.get("restDays")));
            } catch (Exception e) {
                throw ApiException.invalid("restDays must be an array of integers", "restDays", "invalid");
            }
        }
        if (body.containsKey("targetExam")) {
            prefs.setTargetExam((String) body.get("targetExam"));
        }
        if (body.containsKey("targetScore")) {
            prefs.setTargetScore(body.get("targetScore") == null ? null
                    : new java.math.BigDecimal(body.get("targetScore").toString()));
        }
        if (body.containsKey("examDate")) {
            String date = (String) body.get("examDate");
            prefs.setExamDate(date == null || date.isBlank() ? null : java.time.LocalDate.parse(date));
        }
        if (body.containsKey("preferenceJson")) {
            try {
                prefs.setPreferenceJson(objectMapper.writeValueAsString(body.get("preferenceJson")));
            } catch (Exception e) {
                throw ApiException.invalid("preferenceJson invalid", "preferenceJson", "invalid");
            }
        }
        preferencesRepository.save(prefs);
        return me();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> devices() {
        UUID userId = CurrentUser.id();
        return deviceRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(d -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", d.getId().toString());
                    m.put("platform", d.getPlatform());
                    m.put("deviceName", d.getDeviceName() == null ? "" : d.getDeviceName());
                    m.put("createdAt", d.getCreatedAt().toString());
                    m.put("expiresAt", d.getExpiresAt().toString());
                    m.put("revoked", d.getRevokedAt() != null);
                    return m;
                })
                .toList();
    }

    @Transactional
    public void revokeDevice(UUID deviceId) {
        UUID userId = CurrentUser.id();
        UserDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("Device"));
        if (!device.getUserId().equals(userId)) {
            throw ApiException.forbidden("Device does not belong to current user");
        }
        device.revoke();
        deviceRepository.save(device);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportData() {
        // 导出范围：资料、偏好与同意记录（按隐私策略最小化）
        Map<String, Object> me = me();
        return Map.of(
                "exportedAt", Instant.now().toString(),
                "profile", me,
                "note", "Full learning-record export is generated asynchronously by the privacy team in production.");
    }

    @Transactional
    public Map<String, Object> requestDeletion() {
        UUID userId = CurrentUser.id();
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        user.setStatus("PENDING_DELETE");
        userRepository.save(user);
        log.info("Deletion requested for user {}", userId);
        return Map.of("status", "PENDING_DELETE", "message", "Deletion requested");
    }

    private Object safeJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
