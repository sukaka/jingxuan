package com.whale.crm.platform.auth;

import com.whale.crm.platform.auth.application.LoginAttemptService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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

            org.mockito.Mockito.verify(loginAttemptService).recordFailure(username, remoteIp);
            String clientIp = jdbcTemplate.queryForObject("SELECT client_ip FROM crm_audit_log "
                    + "WHERE action_code='LOGIN_FAILED' ORDER BY id DESC LIMIT 1", String.class);
            org.assertj.core.api.Assertions.assertThat(clientIp).isEqualTo(remoteIp);
        } finally {
            resetLoginAttempts(username, remoteIp);
        }
    }
}
