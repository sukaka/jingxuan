package com.whale.crm.platform.security;

import com.whale.crm.platform.support.AbstractMysqlIT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSRF 双提交校验：写方法必须携带与 CRM_CSRF Cookie 一致的 X-CSRF-Token 头。
 */
class CsrfProtectionIT extends AbstractMysqlIT {

    private static final String CREATE_USER_BODY =
            "{\"username\":\"it_csrf_u1\",\"displayName\":\"CSRF测试\",\"password\":\"ItPass@12345\","
                    + "\"userType\":\"EMPLOYEE\"}";

    @Test
    void writeRequest_withoutCsrfHeader_is403CsrfTokenInvalid() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(post("/api/v1/users")
                        .cookie(admin.cookies())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_USER_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void writeRequest_withMismatchedCsrfToken_is403() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(post("/api/v1/users")
                        .cookie(admin.cookies())
                        .header("X-CSRF-Token", "forged-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_USER_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void writeRequest_withCsrfCookieButNoAuth_is401NotCsrf() throws Exception {
        // 未认证 + CSRF 头齐备：应进入认证检查（401），证明 CSRF 通过
        mockMvc.perform(post("/api/v1/users")
                        .cookie(new Cookie("CRM_CSRF", "abc123"))
                        .header("X-CSRF-Token", "abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_USER_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void writeRequest_withMatchingCsrfToken_passesCsrfCheck() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(post("/api/v1/users")
                        .cookie(admin.cookies())
                        .header("X-CSRF-Token", admin.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_USER_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("it_csrf_u1"));
    }

    @Test
    void loginAndGetRequests_doNotRequireCsrf() throws Exception {
        // login 无 CSRF 要求（基座 login() 已验证）；GET 也不需要
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/users").cookie(admin.cookies()))
                .andExpect(status().isOk());
    }
}
