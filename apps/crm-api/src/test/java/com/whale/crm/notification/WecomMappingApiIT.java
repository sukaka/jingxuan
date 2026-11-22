package com.whale.crm.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WecomMappingApiIT extends AbstractMysqlIT {
    @Autowired NotificationRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired com.whale.crm.platform.auth.application.CaptchaService captchaService;

    @Test
    void adminMaintainsNotificationMappingWithIdempotencyAuditAndIndependentEnablement() throws Exception {
        AuthCookies admin = login("admin");
        String corpId = "ww-mapping-" + UUID.randomUUID();
        String wecomUserid = "synthetic-lead-" + UUID.randomUUID();
        String createKey = "mapping-create-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid", wecomUserid,
                "staffUserId", 4, "messageEnabled", true, "reason", "合成通知映射验收"));

        mockMvc.perform(post("/api/v1/notification-settings/wecom-mappings").cookie(admin.cookies())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        var createRequest = admin.withAuth(post("/api/v1/notification-settings/wecom-mappings")
                .header("Idempotency-Key", createKey).contentType(MediaType.APPLICATION_JSON).content(body));
        JsonNode created = objectMapper.readTree(mockMvc.perform(createRequest)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString());
        long id = created.at("/data/id").asLong();
        JsonNode replay = objectMapper.readTree(mockMvc.perform(createRequest)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(replay.at("/data/id").asLong()).isEqualTo(id);
        assertThat(repository.findWecomUserIds(1, 4, corpId)).containsExactly(wecomUserid);

        String updateBody = objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid", wecomUserid,
                "staffUserId", 4, "messageEnabled", false, "version", 0, "reason", "验证独立停用通知"));
        mockMvc.perform(admin.withAuth(put("/api/v1/notification-settings/wecom-mappings/" + id))
                        .header("Idempotency-Key", "mapping-update-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.messageEnabled").value(false))
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(repository.findWecomUserIds(1, 4, corpId)).isEmpty();

        mockMvc.perform(admin.withAuth(get("/api/v1/notification-settings/wecom-mappings")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id == " + id + ")].corpId")
                        .value(corpId));
        String duplicateBody = objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid",
                "another-" + UUID.randomUUID(), "staffUserId", 4, "messageEnabled", true,
                "reason", "验证员工映射唯一"));
        mockMvc.perform(admin.withAuth(post("/api/v1/notification-settings/wecom-mappings"))
                        .header("Idempotency-Key", "mapping-duplicate-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(duplicateBody))
                .andExpect(status().isConflict());

        AuthCookies manager = login("manager1");
        mockMvc.perform(manager.withAuth(post("/api/v1/notification-settings/wecom-mappings"))
                        .header("Idempotency-Key", "mapping-denied-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void enforcesManagePermissionEmployeeTypeEnabledIdentityImmutabilityAndTenantIsolation() throws Exception {
        AuthCookies admin = login("admin");
        String corpId = "ww-hsec01-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Rejects INSTITUTION user (id=8, inst1) even though status=ACTIVE
        String instBody = objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid", "inst-wecom",
                "staffUserId", 8, "messageEnabled", true, "reason", "机构用户禁止绑定"));
        mockMvc.perform(admin.withAuth(post("/api/v1/notification-settings/wecom-mappings"))
                        .header("Idempotency-Key", "mapping-inst-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(instBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 2. Enabled archive mapping (enabled=1) blocks corpId/wecomUserid/staffUserId changes (409) but allows messageEnabled toggle
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled,message_enabled) "
                + "VALUES (1,?,'staff-fixed',2,1,1)", corpId);
        Long enabledId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_wecom_staff_account WHERE tenant_id=1 AND corp_id=? AND wecom_userid='staff-fixed'",
                Long.class, corpId);
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled,message_enabled) "
                + "VALUES (2,?,'t2-staff',2,0,1)", corpId);
        Long tenant2Id = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_wecom_staff_account WHERE tenant_id=2 AND corp_id=? AND wecom_userid='t2-staff'",
                Long.class, corpId);

        // 3. User with notification:delivery:retry + notification:delivery:read (without wecom:mapping:manage) gets 403 on POST/PUT
        String retryUser = "retry_only_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                + "VALUES (1,1,?,'重试角色',?,'EMPLOYEE','ACTIVE')", retryUser, passwordEncoder.encode(DEV_PASSWORD));
        Long retryUserId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE tenant_id=1 AND username=?",
                Long.class, retryUser);
        jdbcTemplate.update("INSERT INTO sys_role (tenant_id,role_code,role_name,status) VALUES (1,'RETRY_ONLY_IT','仅重试','ACTIVE')");
        Long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE tenant_id=1 AND role_code='RETRY_ONLY_IT'",
                Long.class);
        jdbcTemplate.update("INSERT INTO sys_role_permission (tenant_id,role_id,permission_id) "
                + "SELECT 1,?,id FROM sys_permission WHERE tenant_id=1 AND permission_code IN ('notification:delivery:read','notification:delivery:retry')",
                roleId);
        jdbcTemplate.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (1,?,?)", retryUserId, roleId);

        try {
            String hijackBody = objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid", "staff-fixed",
                    "staffUserId", 1, "messageEnabled", true, "version", 0, "reason", "越权改绑管理员"));
            mockMvc.perform(admin.withAuth(put("/api/v1/notification-settings/wecom-mappings/" + enabledId))
                            .header("Idempotency-Key", "mapping-hijack-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(hijackBody))
                    .andExpect(status().isConflict());

            String toggleBody = objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid", "staff-fixed",
                    "staffUserId", 2, "messageEnabled", false, "version", 0, "reason", "仅关闭消息通知"));
            mockMvc.perform(admin.withAuth(put("/api/v1/notification-settings/wecom-mappings/" + enabledId))
                            .header("Idempotency-Key", "mapping-toggle-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(toggleBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.messageEnabled").value(false))
                    .andExpect(jsonPath("$.data.version").value(1));

            // Cross-tenant PUT returns 404
            mockMvc.perform(admin.withAuth(put("/api/v1/notification-settings/wecom-mappings/" + tenant2Id))
                            .header("Idempotency-Key", "mapping-xtenant-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(toggleBody))
                    .andExpect(status().isNotFound());

            AuthCookies retryCookies = login(retryUser);
            mockMvc.perform(get("/api/v1/notification-settings/wecom-mappings").cookie(retryCookies.cookies()))
                    .andExpect(status().isOk());
            mockMvc.perform(retryCookies.withAuth(post("/api/v1/notification-settings/wecom-mappings"))
                            .header("Idempotency-Key", "mapping-retry-post-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid", "u-new",
                                    "staffUserId", 4, "messageEnabled", true, "reason", "越权创建"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(retryCookies.withAuth(put("/api/v1/notification-settings/wecom-mappings/" + enabledId))
                            .header("Idempotency-Key", "mapping-retry-put-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("corpId", corpId, "wecomUserid", "staff-fixed",
                                    "staffUserId", 2, "messageEnabled", true, "version", 1, "reason", "越权修改"))))
                    .andExpect(status().isForbidden());
        } finally {
            jdbcTemplate.update("DELETE FROM crm_wecom_staff_account WHERE id IN (?,?)", enabledId, tenant2Id);
            jdbcTemplate.update("DELETE FROM sys_login_session WHERE tenant_id=1 AND user_id=?", retryUserId);
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE tenant_id=1 AND user_id=?", retryUserId);
            jdbcTemplate.update("DELETE FROM sys_role_permission WHERE tenant_id=1 AND role_id=?", roleId);
            jdbcTemplate.update("DELETE FROM sys_role WHERE tenant_id=1 AND id=?", roleId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE tenant_id=1 AND id=?", retryUserId);
        }
    }
}
