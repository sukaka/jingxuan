package com.whale.crm.platform.audit;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计日志：登录/用户管理写留痕、分页与筛选、脱敏（不含密码哈希）、audit:read 授权边界。
 */
class AuditLogApiIT extends AbstractMysqlIT {

    @Test
    void loginAndUserWrites_areAuditedWithMaskedSnapshots() throws Exception {
        AuthCookies admin = login("admin"); // 产生 LOGIN 审计
        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_audit_target\",\"displayName\":\"审计对象\","
                                + "\"password\":\"ItPass@12345\",\"userType\":\"EMPLOYEE\"}"))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(get("/api/v1/audit-logs?actionCode=USER_CREATE").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        var items = objectMapper.readTree(body).at("/data/items");
        boolean found = false;
        for (var item : items) {
            if ("it_audit_target".equals(item.at("/after/username").asText())) {
                found = true;
                org.assertj.core.api.Assertions.assertThat(item.get("traceId").asText()).isNotBlank();
                org.assertj.core.api.Assertions.assertThat(item.get("actorUsername").asText()).isEqualTo("admin");
                // 脱敏：快照不得包含密码或哈希
                org.assertj.core.api.Assertions.assertThat(item.at("/after").toString())
                        .doesNotContain("password").doesNotContain("$2");
            }
        }
        org.assertj.core.api.Assertions.assertThat(found).as("USER_CREATE 审计应包含新用户").isTrue();

        // LOGIN 审计可查，filter 生效
        mockMvc.perform(get("/api/v1/audit-logs?actionCode=LOGIN&targetType=USER&page=1&size=10")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.actionCode == 'LOGIN')]").isNotEmpty())
                .andExpect(jsonPath("$.data.page").value(1));
    }

    @Test
    void financeHasAuditRead_managerHasNot() throws Exception {
        AuthCookies finance = login("finance1");
        mockMvc.perform(get("/api/v1/audit-logs").cookie(finance.cookies()))
                .andExpect(status().isOk());

        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/audit-logs").cookie(manager.cookies()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void auditLog_isNotWritableViaApi() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(admin.withAuth(post("/api/v1/audit-logs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound()); // 不存在该写接口（方法不允许按 404 处理，防枚举）
    }
}
