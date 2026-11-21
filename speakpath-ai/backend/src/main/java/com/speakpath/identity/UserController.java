package com.speakpath.identity;

import com.speakpath.common.api.ApiEnvelope;
import com.speakpath.common.api.ApiException;
import com.speakpath.common.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 当前用户资料、偏好、设备管理、导出与删除申请。
 */
@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ApiEnvelope<Map<String, Object>> me() {
        return ApiEnvelope.ok(userService.me());
    }

    @PatchMapping("/preferences")
    public ApiEnvelope<Map<String, Object>> updatePreferences(@RequestBody Map<String, Object> body) {
        return ApiEnvelope.ok(userService.updatePreferences(body));
    }

    @GetMapping("/devices")
    public ApiEnvelope<List<Map<String, Object>>> devices() {
        return ApiEnvelope.ok(userService.devices());
    }

    @PostMapping("/devices/{id}/revoke")
    public ApiEnvelope<Void> revokeDevice(@PathVariable UUID id) {
        userService.revokeDevice(id);
        return ApiEnvelope.ok(null);
    }

    @GetMapping("/export")
    public ApiEnvelope<Map<String, Object>> export() {
        return ApiEnvelope.ok(userService.exportData());
    }

    @PostMapping("/delete-request")
    public ApiEnvelope<Map<String, Object>> deleteRequest() {
        return ApiEnvelope.ok(userService.requestDeletion());
    }

    public record PreferencesRequest(
            Integer dailyMinutes,
            List<Integer> restDays,
            String targetExam,
            BigDecimal targetScore,
            LocalDate examDate) {
    }
}
