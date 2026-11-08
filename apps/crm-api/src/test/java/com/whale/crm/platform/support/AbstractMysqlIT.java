package com.whale.crm.platform.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试基座：全测试类共享一个 MySQL 8.4 Testcontainers 实例（含 Flyway 迁移与开发种子）。
 * 采用单例容器模式（静态启动、JVM 结束由 Ryuk 回收），避免 @Testcontainers 按类停容器导致共享上下文断库。
 * 注意数据跨类共享：测试自建数据一律使用独立前缀的用户名/编码，避免相互污染。
 */
@SpringBootTest(properties = "app.exports.worker.enabled=false")
@AutoConfigureMockMvc
public abstract class AbstractMysqlIT {

    protected static final String DEV_PASSWORD = "Dev@12345";

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("medical_crm")
            .withUrlParam("connectionTimeZone", "UTC")
            .withCommand("--log-bin-trust-function-creators=1");

    static {
        MYSQL.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    private com.whale.crm.platform.auth.application.CaptchaService captchaService;
    @Autowired
    private com.whale.crm.platform.auth.application.LoginAttemptService loginAttemptService;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Value("${app.security.auth.login-attempt.key-prefix:crm:auth:login-attempt:}")
    private String loginAttemptKeyPrefix;

    /**
     * 真实登录，返回令牌与 CSRF 串。
     */
    protected AuthCookies login(String username) throws Exception {
        resetLoginAttempts(username);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload(username, DEV_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return new AuthCookies(cookieValue(result, "CRM_TOKEN"), cookieValue(result, "CRM_CSRF"));
    }

    protected void resetLoginAttempts(String username) {
        resetLoginAttempts(username, "127.0.0.1");
    }

    protected void resetLoginAttempts(String username, String clientIp) {
        loginAttemptService.recordSuccess(username);
        redis.delete(loginAttemptKeyPrefix + "ip:"
                + com.whale.crm.platform.security.JwtAuthenticationFilter.sha256Hex(clientIp));
    }

    protected String loginPayload(String username, String password) throws Exception {
        var captcha = captchaService.issue();
        return objectMapper.writeValueAsString(java.util.Map.of("username", username, "password", password,
                "captchaId", captcha.captchaId(), "captchaAnswer", captcha.answer()));
    }

    protected static String cookieValue(MvcResult result, String name) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(name + "=")) {
                String value = header.substring((name + "=").length());
                int semicolon = value.indexOf(';');
                return semicolon >= 0 ? value.substring(0, semicolon) : value;
            }
        }
        throw new AssertionError("响应缺少 Cookie: " + name);
    }

    public record AuthCookies(String token, String csrf) {

        public Cookie[] cookies() {
            return new Cookie[]{new Cookie("CRM_TOKEN", token), new Cookie("CRM_CSRF", csrf)};
        }

        /**
         * 挂载认证 Cookie 与合法 CSRF 头（写请求用）。
         */
        public org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withAuth(
                org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
            return builder.cookie(cookies()).header("X-CSRF-Token", csrf);
        }
    }
}
