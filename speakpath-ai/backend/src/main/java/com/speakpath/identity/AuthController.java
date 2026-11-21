package com.speakpath.identity;

import com.speakpath.common.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiEnvelope<Map<String, Object>> register(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request, HttpServletResponse response) {
        return ApiEnvelope.ok(authService.register(body, request, response));
    }

    @PostMapping("/login")
    public ApiEnvelope<Map<String, Object>> login(@RequestBody @Valid LoginRequest loginRequest,
                                                  HttpServletRequest request, HttpServletResponse response) {
        return ApiEnvelope.ok(authService.login(
                Map.of("email", loginRequest.email(), "password", loginRequest.password()), request, response));
    }

    @PostMapping("/refresh")
    public ApiEnvelope<Map<String, Object>> refresh(@RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request, HttpServletResponse response) {
        String token = body == null ? null : (String) body.get("refreshToken");
        return ApiEnvelope.ok(authService.refresh(token, request, response));
    }

    @PostMapping("/logout")
    public ApiEnvelope<Void> logout(@RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request, HttpServletResponse response) {
        String token = body == null ? null : (String) body.get("refreshToken");
        authService.logout(token, request, response);
        return ApiEnvelope.ok(null);
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password) {
    }
}
