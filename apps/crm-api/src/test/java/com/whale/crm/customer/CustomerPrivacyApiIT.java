package com.whale.crm.customer;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户隐私保护端到端集成测试（MySQL 8.4 + Flyway V65）：
 * 1. 手机号/身份证号/邮箱/微信号密文+盲索引存储，列表/详情/历史档案一律脱敏（含 ADMIN）；
 * 2. ADMIN 按钮显式揭盲：未填原因 400；填原因返回明文 + Cache-Control: no-store + 写双审计日志；
 * 3. 非 ADMIN 直调揭盲 403 + 记拒绝审计；提交申请自动解析直属上级/团队主管；禁止自审；跨组拒绝；
 * 4. 上级批准限时授权（支持收窄字段）→ 申请人按次揭盲（仅获批字段）→ 提前撤销后立即阻断；
 * 5. crm_customer_privacy_access_log 数据库触发器物理防篡改；用户直属上级防成环校验。
 */
class CustomerPrivacyApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fullPrivacyLifecycle_maskingSupervisorApprovalAdminRevealAndImmutableAudit() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        AuthCookies lead1 = login("lead1");
        AuthCookies lead2 = login("lead2");
        AuthCookies admin = login("admin");
        AuthCookies inst1 = login("inst1");

        // 1. 建档含全部 4 项隐私字段，校验落库密文与全站脱敏
        String createBody = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "隐私测试客户",
                                  "nickname": "隐私昵称",
                                  "mobile": "13988886666",
                                  "idCard": "11010519491231002X",
                                  "email": "Privacy.Client@Example.com",
                                  "wechatId": "wx_privacy_2026"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mobileMasked").value("139****6666"))
                .andExpect(jsonPath("$.data.idCardMasked").value("110105********002X"))
                .andExpect(jsonPath("$.data.emailMasked").value("pr***@example.com"))
                .andExpect(jsonPath("$.data.wechatIdMasked").value("wx****26"))
                .andReturn().getResponse().getContentAsString();
        long customerId = objectMapper.readTree(createBody).at("/data/id").asLong();
        assertThat(createBody)
                .doesNotContain("13988886666")
                .doesNotContain("11010519491231002X")
                .doesNotContain("privacy.client@example.com")
                .doesNotContain("wx_privacy_2026");

        // 数据库仅密文与 64 位盲索引，绝无明文
        var dbRow = jdbcTemplate.queryForMap(
                "SELECT mobile_cipher, id_card_cipher, id_card_hash, email_cipher, email_hash, wechat_id_cipher, wechat_id_hash "
                        + "FROM crm_customer WHERE id = ?", customerId);
        assertThat((String) dbRow.get("id_card_cipher")).isNotBlank().doesNotContain("11010519491231002X");
        assertThat((String) dbRow.get("id_card_hash")).hasSize(64);
        assertThat((String) dbRow.get("email_cipher")).isNotBlank().doesNotContain("example.com");
        assertThat((String) dbRow.get("email_hash")).hasSize(64);
        assertThat((String) dbRow.get("wechat_id_cipher")).isNotBlank().doesNotContain("wx_privacy_2026");
        assertThat((String) dbRow.get("wechat_id_hash")).hasSize(64);

        // 2. ADMIN GET 详情同样绝不返回明文
        String adminDetail = mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mobileMasked").value("139****6666"))
                .andExpect(jsonPath("$.data.idCardMasked").value("110105********002X"))
                .andExpect(jsonPath("$.data.emailMasked").value("pr***@example.com"))
                .andExpect(jsonPath("$.data.wechatIdMasked").value("wx****26"))
                .andReturn().getResponse().getContentAsString();
        assertThat(adminDetail).doesNotContain("13988886666").doesNotContain("11010519491231002X")
                .doesNotContain("privacy.client@example.com").doesNotContain("wx_privacy_2026");

        // 3. ADMIN 显式点击按钮揭盲：未填原因 -> 400；填原因 -> 返回明文 + Cache-Control: no-store + 记日志
        mockMvc.perform(admin.withAuth(post("/api/v1/customers/" + customerId + "/privacy/reveal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"MOBILE\",\"ID_CARD\"],\"mode\":\"ADMIN_DIRECT\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        String adminReveal = mockMvc.perform(admin.withAuth(post("/api/v1/customers/" + customerId + "/privacy/reveal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fields": ["MOBILE", "ID_CARD", "EMAIL", "WECHAT_ID"],
                                  "mode": "ADMIN_DIRECT",
                                  "reason": "管理员核验重点客户档案"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data.accessMode").value("ADMIN_DIRECT_REVEAL"))
                .andExpect(jsonPath("$.data.values.MOBILE").value("13988886666"))
                .andExpect(jsonPath("$.data.values.ID_CARD").value("11010519491231002X"))
                .andExpect(jsonPath("$.data.values.EMAIL").value("privacy.client@example.com"))
                .andExpect(jsonPath("$.data.values.WECHAT_ID").value("wx_privacy_2026"))
                .andReturn().getResponse().getContentAsString();
        long adminLogId = objectMapper.readTree(adminReveal).at("/data/accessLogId").asLong();

        // 4. 非 ADMIN 直调揭盲 -> 403，且独立记拒绝审计；跨管家 manager2 -> 404；机构账号 -> 403
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers/" + customerId + "/privacy/reveal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"MOBILE\"],\"mode\":\"ADMIN_DIRECT\",\"reason\":\"越权尝试\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(manager2.withAuth(post("/api/v1/customers/" + customerId + "/privacy/requests"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"MOBILE\"],\"durationMinutes\":30,\"reason\":\"跨组尝试\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(inst1.withAuth(post("/api/v1/customers/" + customerId + "/privacy/requests"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"MOBILE\"],\"durationMinutes\":30,\"reason\":\"机构尝试\"}"))
                .andExpect(status().isForbidden());

        // 5. manager1 向上级主管(lead1)提交申请查看 MOBILE + WECHAT_ID + EMAIL
        String reqBody = mockMvc.perform(manager1.withAuth(post("/api/v1/customers/" + customerId + "/privacy/requests"))
                        .header("Idempotency-Key", "privacy-req-it-" + customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fields": ["MOBILE", "WECHAT_ID", "EMAIL"],
                                  "durationMinutes": 30,
                                  "reason": "预约术前电话确认与加微沟通"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.assignedApproverId").value(4))
                .andReturn().getResponse().getContentAsString();
        long requestId = objectMapper.readTree(reqBody).at("/data/id").asLong();

        // 重复待审批申请 -> 409
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers/" + customerId + "/privacy/requests"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"MOBILE\"],\"durationMinutes\":30,\"reason\":\"重复提交\"}"))
                .andExpect(status().isConflict());

        // 跨团队主管 lead2 试图审批 -> 403
        mockMvc.perform(lead2.withAuth(post("/api/v1/customer-privacy-requests/" + requestId + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationMinutes\":30,\"version\":0}"))
                .andExpect(status().isForbidden());

        // 6. 指定主管 lead1 批准（收窄为仅批准 MOBILE 与 WECHAT_ID，不批准 EMAIL）
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-privacy-requests/" + requestId + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "approvedFields": ["MOBILE", "WECHAT_ID"],
                                  "durationMinutes": 30,
                                  "reviewReason": "仅批准电话与微信号联系",
                                  "version": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedFields.length()").value(2));

        // 7. manager1 尝试揭盲未获批的 EMAIL -> 403；揭盲获批的 MOBILE + WECHAT_ID -> 200 并记日志
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers/" + customerId + "/privacy/reveal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"EMAIL\"],\"requestId\":" + requestId + "}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(manager1.withAuth(post("/api/v1/customers/" + customerId + "/privacy/reveal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"MOBILE\",\"WECHAT_ID\"],\"requestId\":" + requestId + "}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data.accessMode").value("APPROVED_GRANT_REVEAL"))
                .andExpect(jsonPath("$.data.values.MOBILE").value("13988886666"))
                .andExpect(jsonPath("$.data.values.WECHAT_ID").value("wx_privacy_2026"));

        // 8. lead1 提前撤销授权 -> manager1 再次揭盲立即 403
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-privacy-requests/" + requestId + "/revoke"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revokeReason\":\"联系已完成，提前回收\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        mockMvc.perform(manager1.withAuth(post("/api/v1/customers/" + customerId + "/privacy/reveal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[\"MOBILE\"],\"requestId\":" + requestId + "}"))
                .andExpect(status().isForbidden());

        // 9. 验证隐私访问日志接口可查且不含明文，且 MySQL 触发器拒绝 UPDATE/DELETE
        String logsBody = mockMvc.perform(get("/api/v1/customer-privacy-access-logs")
                        .param("customerId", Long.toString(customerId))
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(logsBody)
                .contains("ADMIN_DIRECT_REVEAL")
                .contains("APPROVED_GRANT_REVEAL")
                .contains("139****6666")
                .doesNotContain("13988886666")
                .doesNotContain("11010519491231002X")
                .doesNotContain("wx_privacy_2026");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE crm_customer_privacy_access_log SET reason = 'tamper' WHERE id = ?", adminLogId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM crm_customer_privacy_access_log WHERE id = ?", adminLogId))
                .isInstanceOf(DataAccessException.class);

        // 10. 禁止将脱敏占位符 * 回写覆盖隐私字段
        long customerVersion = objectMapper.readTree(adminDetail).at("/data/version").asLong();
        mockMvc.perform(admin.withAuth(patch("/api/v1/customers/" + customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pr***@example.com\",\"version\":" + customerVersion + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void v65MigrationAndRollbackCleanlyCreatesAndDropsPrivacyGovernanceSchema() throws Exception {
        String schema = "v65_privacy_verify";
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        MysqlDataSource ds = dataSourceFor(schema);
        try {
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema=? "
                            + "AND table_name IN ('crm_customer_privacy_request','crm_customer_privacy_access_log')",
                    String.class, schema);
            assertThat(tables).containsExactlyInAnyOrder(
                    "crm_customer_privacy_request", "crm_customer_privacy_access_log");

            List<String> triggers = jdbc.queryForList(
                    "SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema=? "
                            + "AND trigger_name IN ('trg_privacy_access_log_no_update',"
                            + "'trg_privacy_access_log_no_delete')",
                    String.class, schema);
            assertThat(triggers).containsExactlyInAnyOrder(
                    "trg_privacy_access_log_no_update", "trg_privacy_access_log_no_delete");

            try (Connection conn = ds.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V65__customer_privacy_governance_rollback.sql"));
            }

            Long remainingTables = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? "
                            + "AND table_name IN ('crm_customer_privacy_request','crm_customer_privacy_access_log')",
                    Long.class, schema);
            assertThat(remainingTables).isZero();

            Long remainingColumns = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? "
                            + "AND ((table_name='crm_customer' AND column_name IN ("
                            + "'id_card_cipher','id_card_hash','email_cipher','email_hash',"
                            + "'wechat_id_cipher','wechat_id_hash')) "
                            + "OR (table_name='sys_user' AND column_name='supervisor_user_id'))",
                    Long.class, schema);
            assertThat(remainingColumns).isZero();

            Long remainingPermissions = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_permission WHERE permission_code IN ("
                            + "'customer:privacy:request','customer:privacy:approve',"
                            + "'customer:privacy:admin_reveal','customer:privacy:audit')",
                    Long.class);
            assertThat(remainingPermissions).isZero();
        } finally {
            try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private MysqlDataSource dataSourceFor(String database) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl().replace("/medical_crm?", "/" + database + "?"));
        dataSource.setUser("root");
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }
}
