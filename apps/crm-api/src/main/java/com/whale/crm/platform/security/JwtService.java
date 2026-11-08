package com.whale.crm.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import org.springframework.stereotype.Component;

/**
 * 访问令牌签发/校验（HS256）。令牌只承载身份（sub=userId、jti、iat/exp），
 * 不内嵌角色权限——权限每次请求从 MySQL 实时解析，保证变更立即生效。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(AuthSecurityProperties properties) {
        byte[] secretBytes = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("app.security.auth.jwt-secret 至少需要 32 字节（HS256）");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.ttlSeconds = properties.jwtTtlSeconds();
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String issue(long userId, String username, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .id(jti)
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验签名与有效期；失败抛 JwtException，由调用方按“未认证”处理。
     */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
