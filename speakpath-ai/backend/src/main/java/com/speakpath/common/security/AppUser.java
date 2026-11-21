package com.speakpath.common.security;

import java.util.List;
import java.util.UUID;

/**
 * 认证主体：从 JWT 还原，不含敏感信息。
 */
public record AppUser(UUID id, String email, List<String> roles) {

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole("AI_ADMIN") || hasRole("SYS_ADMIN");
    }
}
