package com.whale.crm.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.auth.application.CaptchaService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wave E E2E 公共测试基类：复用 AbstractMysqlIT 单例 MySQL 8.4 容器，提供多租户登录、Bearer+CSRF 会话、
 * HTTP 文件上传闭环、JSON 断言及 UUID 夹具生成能力。
 */
public abstract class AbstractE2EIT extends AbstractMysqlIT {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected CaptchaService e2eCaptchaService;

    protected static String shortUuid(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected static String shortCode(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    protected static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    protected BearerSession bearerSession(AuthCookies cookies) {
        return new BearerSession(cookies.token(), cookies.csrf());
    }

    protected AuthCookies loginWithTenantCode(String username, String password, String tenantCode, long tenantId)
            throws Exception {
        resetLoginAttempts("tenant:" + tenantId, username, "127.0.0.1");
        var captcha = e2eCaptchaService.issue();
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("captchaId", captcha.captchaId());
        body.put("captchaAnswer", captcha.answer());
        if (tenantCode != null) {
            body.put("tenantCode", tenantCode);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return new AuthCookies(cookieValue(result, "CRM_TOKEN"), cookieValue(result, "CRM_CSRF"));
    }

    protected String loginPayloadWithTenant(String username, String password, String tenantCode) throws Exception {
        var captcha = e2eCaptchaService.issue();
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("captchaId", captcha.captchaId());
        body.put("captchaAnswer", captcha.answer());
        if (tenantCode != null) {
            body.put("tenantCode", tenantCode);
        }
        return json(body);
    }

    protected void configureMockStorage(PrivateObjectStorage storage, ClamAvScanner scanner,
                                        Map<String, byte[]> objectStore) throws Exception {
        objectStore.clear();
        when(storage.presignPut(anyString(), anyInt())).thenAnswer(invocation ->
                "https://storage.invalid/upload?object=" + invocation.getArgument(0)
                        + "&X-Amz-Expires=" + invocation.getArgument(1) + "&X-Amz-Signature=synthetic");
        when(storage.presignGet(anyString(), anyInt())).thenAnswer(invocation ->
                "https://storage.invalid/download?object=" + invocation.getArgument(0)
                        + "&X-Amz-Expires=" + invocation.getArgument(1) + "&X-Amz-Signature=synthetic");
        when(storage.stat(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            byte[] content = objectStore.get(key);
            if (content == null) {
                throw new IllegalStateException("Synthetic object missing for key: " + key);
            }
            return new PrivateObjectStorage.ObjectStat(md5Hex(content), content.length, "application/octet-stream");
        });
        when(storage.getObject(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            byte[] content = objectStore.get(key);
            if (content == null) {
                throw new IllegalStateException("Synthetic object missing for key: " + key);
            }
            return new ByteArrayInputStream(content);
        });
        doAnswer(invocation -> {
            objectStore.put(invocation.getArgument(0),
                    invocation.getArgument(1, InputStream.class).readAllBytes());
            return null;
        }).when(storage).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
        doAnswer(invocation -> {
            objectStore.put(invocation.getArgument(0), invocation.getArgument(1, byte[].class));
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
        doAnswer(invocation -> {
            objectStore.remove(invocation.getArgument(0));
            return null;
        }).when(storage).deleteObject(anyString());
        when(scanner.scan(any())).thenAnswer(invocation -> {
            Files.readAllBytes(invocation.getArgument(0));
            return ClamAvScanner.ScanResult.CLEAN;
        });
    }

    protected long uploadCleanFileViaHttp(AuthCookies actor, Map<String, byte[]> objectStore,
                                          String originalName, String mimeType, byte[] content,
                                          String classification, Long institutionId) throws Exception {
        Map<String, Object> initBody = new HashMap<>();
        initBody.put("originalName", originalName);
        initBody.put("mimeType", mimeType);
        initBody.put("sizeBytes", (long) content.length);
        initBody.put("sha256", sha256Hex(content));
        initBody.put("classification", classification);
        if (institutionId != null) {
            initBody.put("institutionId", institutionId);
        }
        JsonNode initData = data(actor.withAuth(post("/api/v1/files/upload-init"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(initBody)));
        long fileId = initData.at("/fileId").asLong();
        String objectKey = initData.at("/objectKey").asText();
        assertThat(fileId).isPositive();
        assertThat(objectKey).isNotBlank();

        objectStore.put(objectKey, content);

        JsonNode completed = data(actor.withAuth(post("/api/v1/files/upload-complete"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("fileId", fileId, "etag", md5Hex(content)))));
        assertThat(completed.at("/scanStatus").asText()).isEqualTo("CLEAN");
        return fileId;
    }

    protected JsonNode root(MockHttpServletRequestBuilder builder) throws Exception {
        String body = mockMvc.perform(builder)
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    protected JsonNode data(MockHttpServletRequestBuilder builder) throws Exception {
        return root(builder).at("/data");
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    protected static String md5Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    protected static byte[] cleanPngBytes() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    }

    protected static byte[] cleanPdfBytes() {
        return ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
                + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>\nendobj\n"
                + "xref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n"
                + "0000000058 00000 n \n0000000115 00000 n \n"
                + "trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n186\n%%EOF\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    protected long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }

    protected int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    protected String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    public record BearerSession(String token, String csrf) {

        public MockHttpServletRequestBuilder withBearer(MockHttpServletRequestBuilder builder) {
            return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .cookie(new Cookie("CRM_CSRF", csrf))
                    .header("X-CSRF-Token", csrf);
        }

        public MockHttpServletRequestBuilder withBearerGet(MockHttpServletRequestBuilder builder) {
            return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
    }
}
