package com.whale.crm.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发/校验（纯单元测试）。
 */
class JwtServiceTest {

    private final JwtService jwtService = new JwtService(new AuthSecurityProperties(
            "unit-test-jwt-secret-0123456789abcdef", 7200, "CRM_TOKEN", "CRM_CSRF", "X-CSRF-Token", false));

    @Test
    void issueAndParse_roundTrips() {
        String token = jwtService.issue(42L, "manager1", "jti-1");
        Claims claims = jwtService.parse(token);
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.getId()).isEqualTo("jti-1");
        assertThat(claims.get("username", String.class)).isEqualTo("manager1");
        assertThat(claims.getExpiration().toInstant()).isAfter(java.time.Instant.now());
    }

    @Test
    void tamperedToken_isRejected() {
        String token = jwtService.issue(42L, "manager1", "jti-1");
        assertThatThrownBy(() -> jwtService.parse(token + "x")).isInstanceOf(JwtException.class);
    }

    @Test
    void differentSecret_isRejected() {
        JwtService other = new JwtService(new AuthSecurityProperties(
                "another-secret-key-0123456789abcdef", 7200, "CRM_TOKEN", "CRM_CSRF", "X-CSRF-Token", false));
        String token = other.issue(42L, "manager1", "jti-1");
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredToken_isRejected() {
        JwtService shortLived = new JwtService(new AuthSecurityProperties(
                "unit-test-jwt-secret-0123456789abcdef", -1, "CRM_TOKEN", "CRM_CSRF", "X-CSRF-Token", false));
        String token = shortLived.issue(42L, "manager1", "jti-1");
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecret_isRejected() {
        assertThatThrownBy(() -> new JwtService(new AuthSecurityProperties(
                "too-short", 7200, "CRM_TOKEN", "CRM_CSRF", "X-CSRF-Token", false)))
                .isInstanceOf(IllegalStateException.class);
    }
}
