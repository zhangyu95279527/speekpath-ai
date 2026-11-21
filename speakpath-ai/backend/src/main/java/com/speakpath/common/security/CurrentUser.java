package com.speakpath.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * 从 SecurityContext 获取当前登录用户；资源归属一律以此为准，不信任请求中的 userId。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AppUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUser user) {
            return user;
        }
        return null;
    }

    public static UUID id() {
        AppUser user = get();
        return user == null ? null : user.id();
    }

    public static AppUser require() {
        AppUser user = get();
        if (user == null) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        return user;
    }
}
