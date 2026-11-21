package com.speakpath.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 短期 access token 签发与校验（HS256）。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long accessTtlSeconds;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-ttl-seconds:900}") long accessTtlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = accessTtlSeconds;
    }

    public String issueAccessToken(UUID userId, String email, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * @return 校验通过的主体；失败返回 null
     */
    public AppUser parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);
            return new AppUser(UUID.fromString(claims.getSubject()), claims.get("email", String.class), roles);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }
}
