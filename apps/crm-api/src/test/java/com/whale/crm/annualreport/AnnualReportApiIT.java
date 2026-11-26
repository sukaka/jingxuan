package com.whale.crm.annualreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnnualReportApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aggregatesRealCrmDataTracksTelemetryCreatesButlerTaskAndEnforcesGuards() throws Exception {
        AuthCookies admin = login("admin");
        Fixture fx = seedCustomerWithOrdersAndRedemption(2L, 1L);

        String createKey = key("annual-create");
        Map<String, Object> createBody = Map.of(
                "customerId", fx.customerId(),
                "reportYear", 2026,
                "toneCode", "AUTO",
                "genderHint", "FEMALE",
                "customButlerNote", "愿你新岁从容发光，联系 13912345678 预约",
                "disablePreviousActive", false
        );

        JsonNode created = data(admin.withAuth(post("/api/v1/annual-reports"))
                .header("Idempotency-Key", createKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(createBody)));
        long reportId = created.at("/id").asLong();
        String shareKey = created.at("/shareKey").asText();
        assertThat(shareKey).hasSize(43);
        assertThat(created.at("/displayName").asText()).contains("138****8000").doesNotContain("13800138000");
        assertThat(created.at("/orderCount").asInt()).isEqualTo(1);
        assertThat(created.at("/netSpendFen").asLong()).isEqualTo(488000L);
        assertThat(created.at("/redeemCount").asInt()).isEqualTo(1);
        assertThat(created.at("/remainingEntitlementCount").asInt()).isEqualTo(2);
        assertThat(created.at("/status").asText()).isEqualTo("ACTIVE");

        // 幂等重放与同键异体 409
        JsonNode replay = data(admin.withAuth(post("/api/v1/annual-reports"))
                .header("Idempotency-Key", createKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(createBody)));
        assertThat(replay.at("/id").asLong()).isEqualTo(reportId);
        Map<String, Object> conflictBody = new LinkedHashMap<>(createBody);
        conflictBody.put("toneCode", "MINIMAL");
        mockMvc.perform(admin.withAuth(post("/api/v1/annual-reports"))
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(conflictBody)))
                .andExpect(status().isConflict());

        // C 端公开匿名读取快照，严禁暴露内部 ID 与明文手机号
        String publicRaw = mockMvc.perform(get("/api/v1/public/annual-reports/" + shareKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode publicData = objectMapper.readTree(publicRaw).path("data");
        assertThat(publicData.at("/orderCount").asInt()).isEqualTo(1);
        assertThat(publicData.at("/netSpendFen").asLong()).isEqualTo(488000L);
        assertThat(publicData.at("/orders").size()).isEqualTo(1);
        assertThat(publicData.at("/remainingEntitlements/totalCount").asInt()).isEqualTo(2);
        assertThat(publicData.at("/copy/personaQuote").asText()).contains("139****5678").doesNotContain("13912345678");
        assertThat(publicRaw).doesNotContain("customerId", "tenantId", "ownerStaffId", "13800138000", "13912345678");

        // C 端匿名埋点上报：停留时长封顶校验（单次 55000ms -> 封顶 30000ms）+ 跨幕页数追踪
        String sessionKey = "ses_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        JsonNode t1 = data(post("/api/v1/public/annual-reports/" + shareKey + "/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "sessionKey", sessionKey, "eventType", "SESSION_OPEN",
                        "sceneIndex", 1, "deltaDurationMs", 2000, "clientDevice", "MOBILE"))));
        assertThat(t1.at("/visibleDurationMs").asLong()).isEqualTo(2000L);
        assertThat(t1.at("/viewedSceneCount").asInt()).isEqualTo(1);

        JsonNode t2 = data(post("/api/v1/public/annual-reports/" + shareKey + "/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "sessionKey", sessionKey, "eventType", "HEARTBEAT",
                        "sceneIndex", 6, "deltaDurationMs", 55000, "clientDevice", "WECHAT"))));
        assertThat(t2.at("/visibleDurationMs").asLong()).isEqualTo(32000L);
        assertThat(t2.at("/viewedSceneCount").asInt()).isEqualTo(2);
        assertThat(t2.at("/maxSceneReached").asInt()).isEqualTo(6);

        data(post("/api/v1/public/annual-reports/" + shareKey + "/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "sessionKey", sessionKey, "eventType", "GENERATE_POSTER",
                        "sceneIndex", 6, "deltaDurationMs", 1000, "clientDevice", "WECHAT"))));

        // C 端预约意向：幂等创建唯一一条 FOLLOWUP 任务给专属管家，绝不自动建预约/核销
        JsonNode book1 = data(post("/api/v1/public/annual-reports/" + shareKey + "/booking-intent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("sessionKey", sessionKey, "note", "希望本周末预约超光子"))));
        assertThat(book1.at("/submitted").asBoolean()).isTrue();
        assertThat(book1.at("/alreadyRequested").asBoolean()).isFalse();

        JsonNode book2 = data(post("/api/v1/public/annual-reports/" + shareKey + "/booking-intent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("sessionKey", sessionKey, "note", "重复点击"))));
        assertThat(book2.at("/alreadyRequested").asBoolean()).isTrue();
        assertThat(queryLong("SELECT COUNT(*) FROM crm_task WHERE tenant_id=1 AND source_type='ANNUAL_REPORT' AND source_ref=?",
                "ANNUAL_REPORT:" + reportId)).isEqualTo(1L);

        // B 端列表、漏斗统计与会话明细查询
        JsonNode list = data(get("/api/v1/annual-reports")
                .param("reportYear", "2026")
                .param("customerId", Long.toString(fx.customerId()))
                .cookie(admin.cookies()));
        assertThat(list.at("/items").size()).isEqualTo(1);
        assertThat(list.at("/items/0/visitCount").asLong()).isEqualTo(1L);
        assertThat(list.at("/items/0/totalVisibleDurationMs").asLong()).isEqualTo(33000L);
        assertThat(list.at("/items/0/generatedPoster").asBoolean()).isTrue();
        assertThat(list.at("/items/0/clickedBookButler").asBoolean()).isTrue();

        JsonNode summary = data(get("/api/v1/annual-reports/summary")
                .param("reportYear", "2026")
                .cookie(admin.cookies()));
        assertThat(summary.at("/openedReports").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(summary.at("/completedReports").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(summary.at("/sceneFunnel").size()).isEqualTo(6);

        JsonNode sessions = data(get("/api/v1/annual-reports/" + reportId + "/sessions").cookie(admin.cookies()));
        assertThat(sessions.size()).isEqualTo(1);
        assertThat(sessions.at("/0/maxSceneReached").asInt()).isEqualTo(6);

        // 权限与数据范围校验：缺 CSRF 403、机构账号 403、跨管家不可见 404
        mockMvc.perform(post("/api/v1/annual-reports")
                        .cookie(admin.cookies())
                        .header("Idempotency-Key", key("no-csrf"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        mockMvc.perform(login("inst1").withAuth(post("/api/v1/annual-reports"))
                        .header("Idempotency-Key", key("inst-forbidden"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createBody)))
                .andExpect(status().isForbidden());

        AuthCookies manager2 = login("manager2");
        mockMvc.perform(get("/api/v1/annual-reports/" + reportId + "/sessions").cookie(manager2.cookies()))
                .andExpect(status().isNotFound());

        // 批量生成（含一条不存在客户ID）
        JsonNode batch = data(admin.withAuth(post("/api/v1/annual-reports/batch-generate"))
                .header("Idempotency-Key", key("batch"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "customerIds", List.of(fx.customerId(), 9999999L),
                        "reportYear", 2026,
                        "toneCode", "MINIMAL",
                        "disablePreviousActive", false))));
        assertThat(batch.at("/createdCount").asInt()).isEqualTo(1);
        assertThat(batch.at("/skippedCount").asInt()).isEqualTo(1);

        // 停用链接后公开读取与上报均返回 404
        JsonNode disabled = data(admin.withAuth(post("/api/v1/annual-reports/" + reportId + "/disable"))
                .header("Idempotency-Key", key("disable"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0, "reason", "测试停用旧版链接"))));
        assertThat(disabled.at("/status").asText()).isEqualTo("DISABLED");
        assertThat(disabled.at("/version").asLong()).isEqualTo(1L);

        mockMvc.perform(get("/api/v1/public/annual-reports/" + shareKey)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/public/annual-reports/" + shareKey + "/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sessionKey", sessionKey, "eventType", "HEARTBEAT",
                                "sceneIndex", 1, "deltaDurationMs", 1000))))
                .andExpect(status().isNotFound());

        // 触发器保护不可覆盖快照与只追加事件
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE crm_annual_report SET order_count=99 WHERE id=?", reportId))
                .hasMessageContaining("only allows ACTIVE to DISABLED");
        Long anyEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_annual_report_visit_event WHERE report_id=? LIMIT 1", Long.class, reportId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE crm_annual_report_visit_event SET delta_duration_ms=999 WHERE id=?", anyEventId))
                .hasMessageContaining("append only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM crm_annual_report_visit_event WHERE id=?", anyEventId))
                .hasMessageContaining("append only");
    }

    private Fixture seedCustomerWithOrdersAndRedemption(long ownerStaffId, long teamId) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO crm_customer (tenant_id, owner_staff_id, team_id, customer_no, display_name, nickname, "
                        + "tier_code, first_added_at, deleted) VALUES (1, ?, ?, ?, ?, ?, 'S', '2024-05-12 04:00:00', 0)",
                ownerStaffId, teamId, "CUS-AR-" + suffix, "林清晚 13800138000", "林清晚 13800138000");
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                "CUS-AR-" + suffix);

        jdbcTemplate.update(
                "INSERT INTO crm_institution (tenant_id, institution_no, full_name, short_name, city_code, "
                        + "business_type_code, ownership_code, grade_code, status, settlement_type) "
                        + "VALUES (1, ?, '静安臻选美学中心', '静安臻选', 'SH', 'AESTHETIC', 'PRIVATE', 'S', 'ACTIVE', 'MONTHLY')",
                "INST-AR-" + suffix);
        long instId = queryLong("SELECT id FROM crm_institution WHERE tenant_id=1 AND institution_no=?",
                "INST-AR-" + suffix);

        jdbcTemplate.update(
                "INSERT INTO crm_project (tenant_id, project_code, project_name, category_code, list_price_fen, status) "
                        + "VALUES (1, ?, 'M22 黄金超光子', 'OPTICAL', 198000, 'ACTIVE')",
                "PRJ-AR-" + suffix);
        long projectId = queryLong("SELECT id FROM crm_project WHERE tenant_id=1 AND project_code=?",
                "PRJ-AR-" + suffix);

        jdbcTemplate.update(
                "INSERT INTO crm_package (tenant_id, package_code, package_name, status) VALUES (1, ?, '超光子季卡', 'ACTIVE')",
                "PKG-AR-" + suffix);
        long packageId = queryLong("SELECT id FROM crm_package WHERE tenant_id=1 AND package_code=?",
                "PKG-AR-" + suffix);

        jdbcTemplate.update(
                "INSERT INTO crm_package_version (tenant_id, package_id, version_no, selection_mode, choice_count, status) "
                        + "VALUES (1, ?, 1, 'FIXED', 1, 'ACTIVE')",
                packageId);
        long pkgVersionId = queryLong("SELECT id FROM crm_package_version WHERE tenant_id=1 AND package_id=?",
                packageId);

        jdbcTemplate.update(
                "INSERT INTO crm_sku (tenant_id, sku_code, package_version_id, spec_name, sale_price_fen, status) "
                        + "VALUES (1, ?, ?, '超光子3次季卡', 588000, 'ACTIVE')",
                "SKU-AR-" + suffix, pkgVersionId);
        long skuId = queryLong("SELECT id FROM crm_sku WHERE tenant_id=1 AND sku_code=?",
                "SKU-AR-" + suffix);

        jdbcTemplate.update(
                "INSERT INTO crm_order (tenant_id, order_no, customer_id, sold_by_user_id, owner_at_sale_id, "
                        + "external_order_no, source_system, external_shop_id, order_type_code, status, receiver_type, "
                        + "list_amount_fen, payable_fen, paid_fen, refunded_fen, ordered_at, paid_at) "
                        + "VALUES (1, ?, ?, ?, ?, ?, 'YOUZAN', 'SHOP-1', 'REGULAR', 'PARTIALLY_REDEEMED', 'PLATFORM', "
                        + "588000, 588000, 588000, 100000, '2026-07-18 15:47:00', '2026-07-18 15:47:00')",
                "ORD-AR-" + suffix, customerId, ownerStaffId, ownerStaffId, "E2026071899998421" + suffix);
        long orderId = queryLong("SELECT id FROM crm_order WHERE tenant_id=1 AND order_no=?",
                "ORD-AR-" + suffix);

        jdbcTemplate.update(
                "INSERT INTO crm_order_item (tenant_id, order_id, sku_id, package_version_id, sku_name_snapshot, "
                        + "purchase_quantity, list_amount_fen, paid_allocated_fen) "
                        + "VALUES (1, ?, ?, ?, '夏日超光子季卡', 1, 588000, 588000)",
                orderId, skuId, pkgVersionId);
        long orderItemId = queryLong("SELECT id FROM crm_order_item WHERE tenant_id=1 AND order_id=?", orderId);

        jdbcTemplate.update(
                "INSERT INTO crm_entitlement (tenant_id, customer_id, order_item_id, project_id, granted_count, "
                        + "available_count, valid_to, status) VALUES (1, ?, ?, ?, 3, 2, '2027-06-30', 'AVAILABLE')",
                customerId, orderItemId, projectId);
        long entitlementId = queryLong("SELECT id FROM crm_entitlement WHERE tenant_id=1 AND order_item_id=?",
                orderItemId);

        jdbcTemplate.update(
                "INSERT INTO crm_appointment (tenant_id, appointment_no, customer_id, institution_id, owner_staff_id, "
                        + "team_id, scheduled_at, status, created_by) "
                        + "VALUES (1, ?, ?, ?, ?, ?, '2026-08-01 06:00:00', 'REDEEMED', ?)",
                "APT-AR-" + suffix, customerId, instId, ownerStaffId, teamId, ownerStaffId);
        long aptId = queryLong("SELECT id FROM crm_appointment WHERE tenant_id=1 AND appointment_no=?",
                "APT-AR-" + suffix);

        jdbcTemplate.update(
                "INSERT INTO crm_appointment_item (tenant_id, appointment_id, entitlement_id, project_id, planned_count) "
                        + "VALUES (1, ?, ?, ?, 1)",
                aptId, entitlementId, projectId);
        long aptItemId = queryLong("SELECT id FROM crm_appointment_item WHERE tenant_id=1 AND appointment_id=?", aptId);

        jdbcTemplate.update(
                "INSERT INTO crm_redemption (tenant_id, appointment_id, customer_id, institution_id, owner_staff_id, "
                        + "team_id, service_at, evidence_ref, status, idempotency_key, posted_by, posted_at) "
                        + "VALUES (1, ?, ?, ?, ?, ?, '2026-08-01 07:00:00', 'EVD-1', 'POSTED', ?, 1, '2026-08-01 07:05:00')",
                aptId, customerId, instId, ownerStaffId, teamId, "RED-AR-" + suffix);
        long redemptionId = queryLong("SELECT id FROM crm_redemption WHERE tenant_id=1 AND appointment_id=?", aptId);

        jdbcTemplate.update(
                "INSERT INTO crm_redemption_line (tenant_id, redemption_id, appointment_item_id, entitlement_id, project_id, count) "
                        + "VALUES (1, ?, ?, ?, ?, 1)",
                redemptionId, aptItemId, entitlementId, projectId);

        return new Fixture(customerId);
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        String content = mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(content).path("data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private record Fixture(long customerId) {
    }
}
