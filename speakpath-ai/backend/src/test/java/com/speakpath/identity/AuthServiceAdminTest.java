package com.speakpath.identity;

import com.speakpath.common.security.JwtService;
import com.speakpath.infrastructure.rate.RateLimitService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceAdminTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final JwtService jwt = mock(JwtService.class);
    private final AuthService auth = new AuthService(users, mock(UserDeviceRepository.class),
            mock(UserPreferencesRepository.class), mock(ConsentRecordRepository.class),
            passwords, jwt, mock(RateLimitService.class));

    @BeforeEach
    void configureAdmin() {
        ReflectionTestUtils.setField(auth, "adminEmail", "operator@example.test");
        ReflectionTestUtils.setField(auth, "adminPassword", "test-admin-password");
        when(jwt.issueAccessToken(any(), any(), any())).thenReturn("test-access-token");
    }

    @Test
    void adminEmailPrefixDoesNotGrantPrivileges() {
        assertEquals(List.of("USER"), loginWith("admin-other@example.test", "test-user-password"));
    }

    @Test
    void existingAccountWithDifferentPasswordDoesNotGainPrivileges() {
        assertEquals(List.of("USER"), loginWith("operator@example.test", "test-user-password"));
    }

    @Test
    void configuredAccountWithMatchingPasswordGetsAdminRoles() {
        assertEquals(List.of("USER", "AI_ADMIN", "SYS_ADMIN"),
                loginWith("operator@example.test", "test-admin-password"));
    }

    private Object loginWith(String email, String password) {
        User user = new User(email, "test-password-hash", "Test", "zh-CN", "Asia/Shanghai");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwords.matches(password, user.getPasswordHash())).thenReturn(true);

        Map<String, Object> session = auth.login(Map.of("email", email, "password", password),
                new MockHttpServletRequest(), new MockHttpServletResponse());
        return ((Map<?, ?>) session.get("user")).get("roles");
    }
}
