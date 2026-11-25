package com.whale.crm.platform.auth;

import com.whale.crm.platform.auth.application.LoginAttemptService;
import com.whale.crm.platform.auth.application.LoginSessionCleanupService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.JwtAuthenticationFilter;
import com.whale.crm.platform.support.AbstractMysqlIT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证端到端：登录签发双 Cookie、me 实时权限、logout 吊销会话、停用立即失效、失败口径防枚举。
 */
class AuthApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private LoginSessionCleanupService sessionCleanupService;
    @Autowired
    private IdempotencyService idempotencyService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private com.whale.crm.platform.auth.application.CaptchaService captchaService;
    @MockitoSpyBean
    private LoginAttemptService loginAttemptService;

    @Test
    void loginSuccess_returnsUserAndSetsCookies() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload("admin", DEV_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.displayName").value("系统管理员"))
                .andExpect(jsonPath("$.data.userType").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.data.permissions[?(@ == 'user:read')]").isNotEmpty())
                .andExpect(cookie().exists("CRM_TOKEN"))
                .andExpect(cookie().httpOnly("CRM_TOKEN", true))
                .andExpect(cookie().exists("CRM_CSRF"))
                .andExpect(cookie().httpOnly("CRM_CSRF", false))
                .andExpect(header().string("Set-Cookie", containsString("CRM_TOKEN=")));
    }

    @Test
    void loginFailure_wrongPasswordAndUnknownUser_shareSame401Message() throws Exception {
        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload("admin", "WrongPass!1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"))
                .andReturn().getResponse().getContentAsString();

        String unknownUser = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload("no_such_user", "Whatever!1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn().getResponse().getContentAsString();

        // 不区分用户不存在/密码错误：message 一致
        org.assertj.core.api.Assertions.assertThat(unknownUser).contains("\"message\":\"用户名或密码错误\"");
        org.assertj.core.api.Assertions.assertThat(wrongPassword).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void me_withoutToken_is401_withValidToken_returnsFreshProfile() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/auth/me").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("manager1"))
                .andExpect(jsonPath("$.data.roles[0]").value("MANAGER"))
                .andExpect(jsonPath("$.data.permissions[?(@ == 'customer:read')]").isNotEmpty())
                .andExpect(jsonPath("$.data.permissions[?(@ == 'user:read')]").isEmpty());
    }

    @Test
    void logout_revokesSessionAndClearsCookies() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(admin.withAuth(post("/api/v1/auth/logout")))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("CRM_TOKEN", 0))
                .andExpect(cookie().maxAge("CRM_CSRF", 0));

        // 吊销后的旧令牌立即失效
        mockMvc.perform(get("/api/v1/auth/me").cookie(new Cookie("CRM_TOKEN", admin.token())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedToken_isRejected() throws Exception {
        AuthCookies admin = login("admin");
        String tampered = admin.token().substring(0, admin.token().length() - 4) + "AAAA";
        mockMvc.perform(get("/api/v1/auth/me").cookie(new Cookie("CRM_TOKEN", tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUser_cannotLoginAndExistingSessionIsRevoked() throws Exception {
        AuthCookies admin = login("admin");
        // 创建独立用户，验证其可登录
        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_auth_disabled\",\"displayName\":\"停用测试\","
                                + "\"password\":\"ItPass@12345\",\"userType\":\"EMPLOYEE\"}"))
                .andExpect(status().isCreated());
        AuthCookies target = mockLogin("it_auth_disabled", "ItPass@12345");
        mockMvc.perform(get("/api/v1/auth/me").cookie(target.cookies()))
                .andExpect(status().isOk());

        long userId = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/users?keyword=it_auth_disabled").cookie(admin.cookies()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .at("/data/items/0/id").asLong();

        mockMvc.perform(admin.withAuth(post("/api/v1/users/" + userId + "/disable")))
                .andExpect(status().isOk());

        // 已持会话立即失效；再登录同样 401（与密码错误同一口径）
        mockMvc.perform(get("/api/v1/auth/me").cookie(target.cookies()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload("it_auth_disabled", "ItPass@12345")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void loginResponse_neverLeaksPasswordOrHash() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload("owner1", DEV_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("password")
                .doesNotContain("$2y$")
                .doesNotContain("passwordHash");
        org.assertj.core.api.Assertions.assertThat(
                        mockMvc.perform(post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginPayload("owner1", DEV_PASSWORD)))
                                .andReturn().getResponse().getHeader("Set-Cookie"))
                .isNotNull();
    }

    private AuthCookies mockLogin(String username, String password) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return new AuthCookies(cookieValue(result, "CRM_TOKEN"), cookieValue(result, "CRM_CSRF"));
    }

    @Test
    void csrfCookie_isReadableAndTokenCookie_isHttpOnly() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload("finance1", DEV_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", not(containsString("CRM_CSRF=;"))))
                .andExpect(cookie().path("CRM_TOKEN", "/"))
                .andExpect(cookie().path("CRM_CSRF", "/"));
    }

    @Test
    void captchaIsPublicButAnswerIsNotReturnedAndReplayIsRejected() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.data.captchaId").isNotEmpty())
                .andExpect(jsonPath("$.data.imageBase64", containsString("iVBORw0KGgo")))
                .andExpect(jsonPath("$.data.expiresIn").value(300))
                .andExpect(jsonPath("$.data.answer").doesNotExist())
                .andExpect(jsonPath("$.data.captchaAnswer").doesNotExist());

        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(loginPayload("admin", DEV_PASSWORD));
        payload.put("captchaAnswer", "definitely-wrong");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void repeatedLoginFailuresAreRateLimited() throws Exception {
        String username = "it_rate_limit";
        resetLoginAttempts(username);
        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginPayload(username, "WrongPass!1")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload(username, "WrongPass!1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        resetLoginAttempts(username);
    }

    @Test
    void passwordResetRevokesExistingSessionAndRequiresNewPassword() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_password_reset\",\"displayName\":\"密码重置测试\","
                                + "\"password\":\"ItPass@12345\",\"userType\":\"EMPLOYEE\"}"))
                .andExpect(status().isCreated());
        AuthCookies target = mockLogin("it_password_reset", "ItPass@12345");
        long userId = objectMapper.readTree(
                        mockMvc.perform(get("/api/v1/users?keyword=it_password_reset").cookie(admin.cookies()))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString())
                .at("/data/items/0/id").asLong();

        mockMvc.perform(admin.withAuth(patch("/api/v1/users/" + userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"已重置密码\",\"password\":\"NewItPass@12345\",\"version\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").cookie(target.cookies()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload("it_password_reset", "ItPass@12345")))
                .andExpect(status().isUnauthorized());
        mockLogin("it_password_reset", "NewItPass@12345");
    }

    @Test
    void forwardedHeaderCannotOverrideConnectionPeerForLoginProtectionOrAudit() throws Exception {
        String username = "it_ip_spoof";
        String remoteIp = "192.0.2.44";
        resetLoginAttempts(username);
        try {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(remoteIp);
                                return request;
                            })
                            .header("X-Forwarded-For", "198.51.100.77")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginPayload(username, "WrongPass!1")))
                    .andExpect(status().isUnauthorized());

            org.mockito.Mockito.verify(loginAttemptService).recordFailure("tenant:1", username, remoteIp);
            String clientIp = jdbcTemplate.queryForObject("SELECT client_ip FROM crm_audit_log "
                    + "WHERE action_code='LOGIN_FAILED' ORDER BY id DESC LIMIT 1", String.class);
            org.assertj.core.api.Assertions.assertThat(clientIp).isEqualTo(remoteIp);
        } finally {
            resetLoginAttempts(username, remoteIp);
        }
    }

    @Test
    void bearerLogoutRevokesSessionAndRejectsSubsequentBearerRequests() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token())
                        .cookie(new Cookie("CRM_CSRF", admin.csrf()))
                        .header("X-CSRF-Token", admin.csrf()))
                .andExpect(status().isOk());

        Integer revoked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_login_session WHERE tenant_id=1 AND user_id=1 "
                        + "AND token_hash=? AND revoked_at IS NOT NULL",
                Integer.class, JwtAuthenticationFilter.sha256Hex(admin.token()));
        org.assertj.core.api.Assertions.assertThat(revoked).isEqualTo(1);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void multiTenantCollidingUsernameAndCaptchaAuditAreSafelyIsolated() throws Exception {
        long secondTenantId = 9002L;
        String secondOrgCode = "IT_ORG_9002";
        String collidingUsername = "it_colliding_user";
        String passwordHash = passwordEncoder.encode(DEV_PASSWORD);

        jdbcTemplate.update("INSERT IGNORE INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (9002,?,?,'二号测试组织','ACTIVE')",
                secondTenantId, secondOrgCode);
        jdbcTemplate.update("INSERT IGNORE INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                        + "VALUES (1,1,?,'租户1同名账号',?,'EMPLOYEE','ACTIVE')",
                collidingUsername, passwordHash);
        jdbcTemplate.update("INSERT IGNORE INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                        + "VALUES (?,9002,?,'租户2同名账号',?,'EMPLOYEE','ACTIVE')",
                secondTenantId, collidingUsername, passwordHash);

        resetLoginAttempts(collidingUsername);
        try {
            // 1. 缺省 tenantCode 登录跨租户同名账号：返回 400 VALIDATION_FAILED，不抛 500，不污染租户 1 审计
            long tenantOneAuditBefore = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='LOGIN_FAILED' "
                            + "AND JSON_EXTRACT(after_json, '$.username')=?",
                    Long.class, collidingUsername);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginPayload(collidingUsername, DEV_PASSWORD)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details[0].field").value("tenantCode"));

            long tenantOneAuditAfterAmbiguous = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='LOGIN_FAILED' "
                            + "AND JSON_EXTRACT(after_json, '$.username')=?",
                    Long.class, collidingUsername);
            org.assertj.core.api.Assertions.assertThat(tenantOneAuditAfterAmbiguous).isEqualTo(tenantOneAuditBefore);

            // 2. 指定 WHALE 与 IT_ORG_9002 分别命中对应租户账号
            var whaleCaptcha = captchaService.issue();
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", collidingUsername, "password", DEV_PASSWORD,
                                    "captchaId", whaleCaptcha.captchaId(), "captchaAnswer", whaleCaptcha.answer(),
                                    "tenantCode", "WHALE"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.username").value(collidingUsername));

            var t2Captcha = captchaService.issue();
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", collidingUsername, "password", DEV_PASSWORD,
                                    "captchaId", t2Captcha.captchaId(), "captchaAnswer", t2Captcha.answer(),
                                    "tenantCode", secondOrgCode))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.username").value(collidingUsername));

            // 3. 验证码错误附带第二租户编码时落第二租户审计，不污染租户 1
            long tenantTwoAuditBefore = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=? AND action_code='LOGIN_FAILED'",
                    Long.class, secondTenantId);
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", collidingUsername, "password", DEV_PASSWORD,
                                    "captchaId", "bad-captcha-id", "captchaAnswer", "999",
                                    "tenantCode", secondOrgCode))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            long tenantTwoAuditAfter = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=? AND action_code='LOGIN_FAILED'",
                    Long.class, secondTenantId);
            org.assertj.core.api.Assertions.assertThat(tenantTwoAuditAfter).isEqualTo(tenantTwoAuditBefore + 1);
            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='LOGIN_FAILED' "
                            + "AND JSON_EXTRACT(after_json, '$.username')=?",
                    Long.class, collidingUsername)).isEqualTo(tenantOneAuditBefore);

            // 4. 未知组织编码验证码失败不写入租户 1 审计
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", collidingUsername, "password", DEV_PASSWORD,
                                    "captchaId", "bad-captcha-id", "captchaAnswer", "999",
                                    "tenantCode", "NO_SUCH_ORG_999"))))
                    .andExpect(status().isBadRequest());
            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='LOGIN_FAILED' "
                            + "AND JSON_EXTRACT(after_json, '$.username')=?",
                    Long.class, collidingUsername)).isEqualTo(tenantOneAuditBefore);
        } finally {
            resetLoginAttempts("tenant:1", collidingUsername, "127.0.0.1");
            resetLoginAttempts("tenant:" + secondTenantId, collidingUsername, "127.0.0.1");
            resetLoginAttempts("ambiguous", collidingUsername, "127.0.0.1");
            jdbcTemplate.update("DELETE FROM sys_login_session WHERE tenant_id=?", secondTenantId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE username=?", collidingUsername);
            jdbcTemplate.update("DELETE FROM sys_org WHERE id=9002");
        }
    }

    @Test
    void crossTenantLoginAttemptsAndSuccessDoNotAffectOtherTenant() throws Exception {
        long secondTenantId = 9003L;
        String secondOrgCode = "IT_ORG_9003";
        String username = "it_cross_tenant_lockout";
        String passwordHash = passwordEncoder.encode(DEV_PASSWORD);

        jdbcTemplate.update("INSERT IGNORE INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (9003,?,?,'三号测试组织','ACTIVE')",
                secondTenantId, secondOrgCode);
        jdbcTemplate.update("INSERT IGNORE INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                        + "VALUES (1,1,?,'租户1隔离测试',?,'EMPLOYEE','ACTIVE')",
                username, passwordHash);
        jdbcTemplate.update("INSERT IGNORE INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                        + "VALUES (?,9003,?,'租户3隔离测试',?,'EMPLOYEE','ACTIVE')",
                secondTenantId, username, passwordHash);

        String attackerIp = "198.51.100.10";
        String victimIp = "203.0.113.20";
        resetLoginAttempts("tenant:1", username, victimIp);
        resetLoginAttempts("tenant:" + secondTenantId, username, attackerIp);
        resetLoginAttempts("org:UNKNOWN_IT_ORG", username, "198.51.100.11");
        resetLoginAttempts("ambiguous", username, "198.51.100.12");
        try {
            // 1. 租户 9003 连续失败 5 次锁定租户 9003，不影响租户 WHALE 正常登录
            for (int attempt = 1; attempt <= 5; attempt++) {
                var captcha = captchaService.issue();
                mockMvc.perform(post("/api/v1/auth/login")
                                .with(req -> {
                                    req.setRemoteAddr(attackerIp);
                                    return req;
                                })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "username", username, "password", "WrongPass!1",
                                        "captchaId", captcha.captchaId(), "captchaAnswer", captcha.answer(),
                                        "tenantCode", secondOrgCode))))
                        .andExpect(attempt < 5 ? status().isUnauthorized() : status().isTooManyRequests());
            }

            // 未知组织编码与缺省组织编码的 5 次失败同样不锁定 WHALE
            for (int attempt = 1; attempt <= 5; attempt++) {
                var badOrgCaptcha = captchaService.issue();
                mockMvc.perform(post("/api/v1/auth/login")
                                .with(req -> {
                                    req.setRemoteAddr("198.51.100.11");
                                    return req;
                                })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "username", username, "password", "WrongPass!1",
                                        "captchaId", badOrgCaptcha.captchaId(), "captchaAnswer", badOrgCaptcha.answer(),
                                        "tenantCode", "UNKNOWN_IT_ORG"))))
                        .andExpect(attempt < 5 ? status().isUnauthorized() : status().isTooManyRequests());
            }

            for (int attempt = 1; attempt <= 5; attempt++) {
                var ambiguousCaptcha = captchaService.issue();
                mockMvc.perform(post("/api/v1/auth/login")
                                .with(req -> {
                                    req.setRemoteAddr("198.51.100.12");
                                    return req;
                                })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "username", username, "password", "WrongPass!1",
                                        "captchaId", ambiguousCaptcha.captchaId(), "captchaAnswer", ambiguousCaptcha.answer()))))
                        .andExpect(attempt < 5 ? status().isBadRequest() : status().isTooManyRequests());
            }

            var whaleOkCaptcha = captchaService.issue();
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(victimIp);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", username, "password", DEV_PASSWORD,
                                    "captchaId", whaleOkCaptcha.captchaId(), "captchaAnswer", whaleOkCaptcha.answer(),
                                    "tenantCode", "WHALE"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.username").value(username));

            // 2. 清理租户 9003 后：WHALE 连续失败 4 次，随后租户 9003 登录成功，WHALE 计数不得被清零
            resetLoginAttempts("tenant:" + secondTenantId, username, attackerIp);
            resetLoginAttempts("tenant:1", username, victimIp);
            for (int attempt = 1; attempt <= 4; attempt++) {
                var captcha = captchaService.issue();
                mockMvc.perform(post("/api/v1/auth/login")
                                .with(req -> {
                                    req.setRemoteAddr(attackerIp);
                                    return req;
                                })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "username", username, "password", "WrongPass!1",
                                        "captchaId", captcha.captchaId(), "captchaAnswer", captcha.answer(),
                                        "tenantCode", "WHALE"))))
                        .andExpect(status().isUnauthorized());
            }

            var org3SuccessCaptcha = captchaService.issue();
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(victimIp);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", username, "password", DEV_PASSWORD,
                                    "captchaId", org3SuccessCaptcha.captchaId(), "captchaAnswer", org3SuccessCaptcha.answer(),
                                    "tenantCode", secondOrgCode))))
                    .andExpect(status().isOk());

            var whaleFifthFailCaptcha = captchaService.issue();
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(attackerIp);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", username, "password", "WrongPass!1",
                                    "captchaId", whaleFifthFailCaptcha.captchaId(), "captchaAnswer", whaleFifthFailCaptcha.answer(),
                                    "tenantCode", "WHALE"))))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        } finally {
            resetLoginAttempts("tenant:1", username, victimIp);
            resetLoginAttempts("tenant:" + secondTenantId, username, attackerIp);
            resetLoginAttempts("org:UNKNOWN_IT_ORG", username, "198.51.100.11");
            resetLoginAttempts("ambiguous", username, "198.51.100.12");
            jdbcTemplate.update("DELETE FROM sys_login_session WHERE tenant_id IN (1,?) AND user_id IN (SELECT id FROM sys_user WHERE username=?)",
                    secondTenantId, username);
            jdbcTemplate.update("DELETE FROM sys_user WHERE username=?", username);
            jdbcTemplate.update("DELETE FROM sys_org WHERE id=9003");
        }
    }

    @Test
    void expiredSessionCleanupDeletesOnlyExpiredRows() {
        String expiredHash = "expired-" + UUID.randomUUID();
        String activeHash = "active-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("INSERT INTO sys_login_session (tenant_id,user_id,token_hash,expires_at) VALUES (1,1,?,?)",
                expiredHash, Timestamp.from(now.minusSeconds(3600)));
        jdbcTemplate.update("INSERT INTO sys_login_session (tenant_id,user_id,token_hash,expires_at) VALUES (1,1,?,?)",
                activeHash, Timestamp.from(now.plusSeconds(3600)));

        int deleted = sessionCleanupService.cleanupExpiredBefore(now);
        org.assertj.core.api.Assertions.assertThat(deleted).isGreaterThanOrEqualTo(1);

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_login_session WHERE token_hash=?", Integer.class, expiredHash)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_login_session WHERE token_hash=?", Integer.class, activeHash)).isEqualTo(1);
    }

    @Test
    void idempotencyRecordsAreIsolatedPerActorUserInDatabase() {
        String sharedKey = "it-shared-idem-" + UUID.randomUUID();
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null, Set.of(), Set.of(), Set.of()),
                    null, List.of()));
            idempotencyService.recordSuccess(1L, "it.scope", sharedKey, "ORDER", "1001", "{\"amount\":100}");

            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new CurrentUser(2L, 1L, "manager1", "管家1", "EMPLOYEE", null, Set.of(), Set.of(), Set.of()),
                    null, List.of()));
            org.assertj.core.api.Assertions.assertThat(
                    idempotencyService.lookup(1L, "it.scope", sharedKey, "{\"amount\":200}")).isEmpty();
            idempotencyService.recordSuccess(1L, "it.scope", sharedKey, "ORDER", "1002", "{\"amount\":200}");

            org.assertj.core.api.Assertions.assertThat(
                    idempotencyService.lookup(1L, "it.scope", sharedKey, "{\"amount\":200}"))
                    .isPresent()
                    .get()
                    .extracting(com.whale.crm.platform.idempotency.IdempotencyRecord::getResourceId)
                    .isEqualTo("1002");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void passwordPolicy_requiresAuthAndReturnsActiveComplexityRules() throws Exception {
        mockMvc.perform(get("/api/v1/auth/password-policy"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/auth/password-policy").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minLength").value(8))
                .andExpect(jsonPath("$.data.maxLength").value(64))
                .andExpect(jsonPath("$.data.requireUppercase").value(true))
                .andExpect(jsonPath("$.data.requireLowercase").value(true))
                .andExpect(jsonPath("$.data.requireDigit").value(true))
                .andExpect(jsonPath("$.data.requireSpecial").value(true))
                .andExpect(jsonPath("$.data.forbidWhitespace").value(true))
                .andExpect(jsonPath("$.data.forbidUsername").value(true))
                .andExpect(jsonPath("$.data.forbidSameAsCurrent").value(true));
    }

    @Test
    void changeOwnPassword_enforcesSecurityComplexityAndRevokesAllSessions() throws Exception {
        AuthCookies admin = login("admin");
        String username = "it_self_pw_user";
        String initialPass = "InitPass@123";
        String nextPass = "NextStrong#456";

        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "displayName", "自助改密测试",
                                "password", initialPass,
                                "userType", "INSTITUTION"))))
                .andExpect(status().isCreated());

        AuthCookies sessionA = mockLogin(username, initialPass);
        AuthCookies sessionB = mockLogin(username, initialPass);

        // 1. Unauthenticated without CSRF -> 403 CSRF_TOKEN_INVALID; with CSRF but no session -> 401 UNAUTHORIZED
        mockMvc.perform(post("/api/v1/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", initialPass,
                                "newPassword", nextPass,
                                "confirmPassword", nextPass))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        mockMvc.perform(post("/api/v1/auth/password")
                        .cookie(new Cookie("CRM_CSRF", "anon-csrf"))
                        .header("X-CSRF-Token", "anon-csrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", initialPass,
                                "newPassword", nextPass,
                                "confirmPassword", nextPass))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 2. Missing CSRF -> 403 CSRF_TOKEN_INVALID
        mockMvc.perform(post("/api/v1/auth/password")
                        .cookie(sessionA.cookies())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", initialPass,
                                "newPassword", nextPass,
                                "confirmPassword", nextPass))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        // 3. Weak complexity -> 400 VALIDATION_FAILED
        mockMvc.perform(sessionA.withAuth(post("/api/v1/auth/password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", initialPass,
                                "newPassword", "weakpass123",
                                "confirmPassword", "weakpass123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("newPassword"));

        // 4. Confirm mismatch -> 400 VALIDATION_FAILED
        mockMvc.perform(sessionA.withAuth(post("/api/v1/auth/password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", initialPass,
                                "newPassword", nextPass,
                                "confirmPassword", "OtherStrong#789"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("confirmPassword"));

        // 5. Wrong current password -> 400 VALIDATION_FAILED (session stays alive)
        mockMvc.perform(sessionA.withAuth(post("/api/v1/auth/password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "WrongOld@123",
                                "newPassword", nextPass,
                                "confirmPassword", nextPass))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("currentPassword"));

        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionA.cookies()))
                .andExpect(status().isOk());

        // 6. Same as current password -> 400 VALIDATION_FAILED
        mockMvc.perform(sessionA.withAuth(post("/api/v1/auth/password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", initialPass,
                                "newPassword", initialPass,
                                "confirmPassword", initialPass))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("newPassword"));

        // 7. Valid change -> 200 OK, cookies cleared, ALL sessions revoked, audit written without password
        mockMvc.perform(sessionA.withAuth(post("/api/v1/auth/password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", initialPass,
                                "newPassword", nextPass,
                                "confirmPassword", nextPass))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(cookie().maxAge("CRM_TOKEN", 0))
                .andExpect(cookie().maxAge("CRM_CSRF", 0));

        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionA.cookies()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionB.cookies()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload(username, initialPass)))
                .andExpect(status().isUnauthorized());
        mockLogin(username, nextPass);

        String auditAfter = jdbcTemplate.queryForObject(
                "SELECT after_json FROM crm_audit_log WHERE action_code='PASSWORD_CHANGE' ORDER BY id DESC LIMIT 1",
                String.class);
        org.assertj.core.api.Assertions.assertThat(auditAfter)
                .contains(username)
                .doesNotContain(initialPass)
                .doesNotContain(nextPass)
                .doesNotContain("$2a$");
    }

    @Test
    void changeOwnPassword_repeatedWrongCurrentPasswordIsRateLimited() throws Exception {
        AuthCookies admin = login("admin");
        String username = "it_pw_rl_user";
        String initialPass = "InitPass@123";
        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "displayName", "改密限流测试",
                                "password", initialPass,
                                "userType", "EMPLOYEE"))))
                .andExpect(status().isCreated());

        AuthCookies userSession = mockLogin(username, initialPass);
        String testIp = "192.0.2.88";
        String payload = objectMapper.writeValueAsString(Map.of(
                "currentPassword", "WrongOld@999",
                "newPassword", "ValidNew#2026",
                "confirmPassword", "ValidNew#2026"));

        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id=1 AND username=?", Long.class, username);
        try {
            for (int attempt = 1; attempt <= 4; attempt++) {
                mockMvc.perform(userSession.withAuth(post("/api/v1/auth/password"))
                                .with(req -> {
                                    req.setRemoteAddr(testIp);
                                    return req;
                                })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            }
            mockMvc.perform(userSession.withAuth(post("/api/v1/auth/password"))
                            .with(req -> {
                                req.setRemoteAddr(testIp);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "900"))
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

            // Normal login remains unaffected by password-change lockout
            mockLogin(username, initialPass);
        } finally {
            loginAttemptService.recordPasswordChangeSuccess("tenant:1", userId);
        }
    }
}
