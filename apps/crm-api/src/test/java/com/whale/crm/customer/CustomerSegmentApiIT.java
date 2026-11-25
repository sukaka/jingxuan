package com.whale.crm.customer;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerSegmentApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void attentionProjectsUseProjectIdsWithoutGuessingLegacyText() throws Exception {
        AuthCookies admin = login("admin");
        String created = mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .header("Idempotency-Key", key("attention-create"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "V33-legacy-attention", "ownerStaffId", 2,
                                "attentionProjects", "无法匹配的旧项目名", "projectIds", List.of(101)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attentionProjects").value("无法匹配的旧项目名"))
                .andExpect(jsonPath("$.data.attentionProjectIds[0]").value(101))
                .andReturn().getResponse().getContentAsString();
        long customerId = objectMapper.readTree(created).at("/data/id").asLong();

        mockMvc.perform(admin.withAuth(patch("/api/v1/customers/{id}", customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("projectIds", List.of(102, 101), "version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attentionProjects").value("无法匹配的旧项目名"))
                .andExpect(jsonPath("$.data.attentionProjectIds.length()").value(2));
        assertThat(jdbcTemplate.queryForObject("SELECT attention_projects FROM crm_customer WHERE id=?",
                String.class, customerId)).isEqualTo("无法匹配的旧项目名");
        assertThat(jdbcTemplate.queryForList("SELECT project_id FROM crm_customer_attention_project "
                + "WHERE customer_id=? ORDER BY project_id", Long.class, customerId)).containsExactly(101L, 102L);

        mockMvc.perform(admin.withAuth(patch("/api/v1/customers/{id}", customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("attentionProjects", "按旧文本猜一个项目", "version", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(admin.withAuth(patch("/api/v1/customers/{id}", customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("projectIds", List.of(999999), "version", 1))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void segmentCombinesScopeGrantAndApprovedNetRedemptionWithoutPreviewSnapshots() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager2 = login("manager2");
        long customerId = customer(admin, "V33-segment-member");
        jdbcTemplate.update("UPDATE crm_customer SET city_code='BJ',tier_code='A' WHERE id=?", customerId);
        replaceAttention(admin, customerId, 0, 101);
        long entitlementId = entitlementFixture(customerId, 101);
        ledger(entitlementId, "GRANT", 3, "ORDER_PAYMENT", "v33-grant-" + customerId);
        ledger(entitlementId, "REDEEM", -2, "REDEMPTION", "v33-redeem-" + customerId);
        ledger(entitlementId, "CORRECTION_IN", 1, "REDEMPTION_REVERSAL", "v33-approved-" + customerId);
        ledger(entitlementId, "CORRECTION_IN", 1, "REDEMPTION_REVERSAL_REQUEST", "v33-pending-" + customerId);
        ledger(entitlementId, "REFUND_REVOKE", -3, "REFUND", "v33-revoke-" + customerId);

        Map<String, Object> filters = filters(1);
        String segment = createSegment(admin, "V33-net-redemption", filters, "v33-segment-create")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();
        long segmentId = objectMapper.readTree(segment).at("/data/id").asLong();

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-segments/preview"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("filters", filters, "page", 1, "size", 20))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(customerId));
        mockMvc.perform(get("/api/v1/customer-segments/{id}/members", segmentId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/customer-segments/{id}/members", segmentId).cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_segment_batch",
                Long.class)).isZero();

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-segments/preview"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("filters", filters(2), "page", 1, "size", 20))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        String batch = mockMvc.perform(admin.withAuth(post(
                                "/api/v1/customer-segments/{id}/operation-batches", segmentId))
                        .header("Idempotency-Key", "v33-operation-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("operationType", "FOLLOWUP", "version", 0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.memberCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String batchNo = objectMapper.readTree(batch).at("/data/batchNo").asText();
        mockMvc.perform(admin.withAuth(post(
                                "/api/v1/customer-segments/{id}/operation-batches", segmentId))
                        .header("Idempotency-Key", "v33-operation-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("operationType", "FOLLOWUP", "version", 0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.batchNo").value(batchNo));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_segment_batch",
                Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer_segment_batch_member",
                Long.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE crm_customer_segment_batch SET member_count=0 WHERE batch_no=?", batchNo))
                .hasMessageContaining("immutable");
    }

    @Test
    void segmentCrudEnforcesCsrfIdempotencyVersionAuditAndDelete() throws Exception {
        AuthCookies admin = login("admin");
        Map<String, Object> request = Map.of("name", "V33-crud", "filters", Map.of("cityCodes", List.of("BJ")));
        String payload = json(request);
        mockMvc.perform(post("/api/v1/customer-segments").cookie(admin.cookies())
                        .header("Idempotency-Key", "v33-no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        String created = mockMvc.perform(admin.withAuth(post("/api/v1/customer-segments"))
                        .header("Idempotency-Key", "v33-crud-create")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).at("/data/id").asLong();
        String replay = mockMvc.perform(admin.withAuth(post("/api/v1/customer-segments"))
                        .header("Idempotency-Key", "v33-crud-create")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay).at("/data/id").asLong()).isEqualTo(id);

        mockMvc.perform(admin.withAuth(patch("/api/v1/customer-segments/{id}", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "V33-crud-updated", "filters",
                                Map.of("tierCodes", List.of("A")), "version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(admin.withAuth(patch("/api/v1/customer-segments/{id}", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "stale", "filters", Map.of(), "version", 0))))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/customer-segments?page=1&size=20&keyword=V33-crud")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log "
                + "WHERE action_code LIKE 'CUSTOMER_SEGMENT_%' AND target_id=?", Long.class,
                Long.toString(id))).isGreaterThanOrEqualTo(2);

        mockMvc.perform(admin.withAuth(delete("/api/v1/customer-segments/{id}?version=1", id)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customer-segments/{id}", id).cookie(admin.cookies()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateAndDeleteEnforceCreatorOrAllScopeWhileMembersRemainReadable() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");

        String adminCreated = createSegment(admin, "H-SEC-09-admin-" + UUID.randomUUID(),
                Map.of("cityCodes", List.of("BJ")), key("seg-admin"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long adminSegId = objectMapper.readTree(adminCreated).at("/data/id").asLong();

        mockMvc.perform(manager1.withAuth(patch("/api/v1/customer-segments/{id}", adminSegId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "越权修改", "filters", Map.of(), "version", 0))))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager1.withAuth(delete("/api/v1/customer-segments/{id}?version=0", adminSegId)))
                .andExpect(status().isForbidden());

        String m1Name = "H-SEC-09-m1-" + UUID.randomUUID();
        String m1Created = createSegment(manager1, m1Name,
                Map.of("cityCodes", List.of("SH")), key("seg-m1"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long m1SegId = objectMapper.readTree(m1Created).at("/data/id").asLong();

        mockMvc.perform(manager1.withAuth(patch("/api/v1/customer-segments/{id}", m1SegId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", m1Name + "-upd", "filters", Map.of("cityCodes", List.of("SH")),
                                "version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(manager2.withAuth(patch("/api/v1/customer-segments/{id}", m1SegId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "manager2越权", "filters", Map.of(), "version", 1))))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager2.withAuth(delete("/api/v1/customer-segments/{id}?version=1", m1SegId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(admin.withAuth(patch("/api/v1/customer-segments/{id}", m1SegId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", m1Name + "-admin", "filters", Map.of("tierCodes", List.of("A")),
                                "version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        mockMvc.perform(admin.withAuth(delete("/api/v1/customer-segments/{id}?version=2", m1SegId)))
                .andExpect(status().isOk());
    }

    @Test
    void segmentPreviewAndMembersReturnAllSortedLongTagsWithoutTruncation() throws Exception {
        AuthCookies admin = login("admin");
        String cityCode = "SGT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long taggedCustomerId = customer(admin, "SEG-LONG-TAGS-" + cityCode);
        long emptyCustomerId = customer(admin, "SEG-NO-TAGS-" + cityCode);
        jdbcTemplate.update("UPDATE crm_customer SET city_code=? WHERE tenant_id=1 AND id IN (?,?)",
                cityCode, taggedCustomerId, emptyCustomerId);

        java.util.List<String> expectedSorted = new java.util.ArrayList<>();
        for (int i = 24; i >= 0; i--) {
            String code = String.format("SEG_TAG_%02d_%s", i, "B".repeat(41));
            jdbcTemplate.update("INSERT INTO crm_customer_tag (tenant_id,customer_id,tag_code,created_by) VALUES (1,?,?,1)",
                    taggedCustomerId, code);
            expectedSorted.add(code);
        }
        java.util.Collections.sort(expectedSorted);

        Map<String, Object> filters = Map.of("cityCodes", List.of(cityCode));
        String previewJson = mockMvc.perform(admin.withAuth(post("/api/v1/customer-segments/preview"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("filters", filters, "page", 1, "size", 20))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn().getResponse().getContentAsString();
        assertSegmentTags(previewJson, taggedCustomerId, emptyCustomerId, expectedSorted);

        String created = createSegment(admin, "SEG-TAGS-" + cityCode, filters, key("seg-long-tags"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long segmentId = objectMapper.readTree(created).at("/data/id").asLong();

        String membersJson = mockMvc.perform(get("/api/v1/customer-segments/{id}/members?page=1&size=20", segmentId)
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn().getResponse().getContentAsString();
        assertSegmentTags(membersJson, taggedCustomerId, emptyCustomerId, expectedSorted);
    }

    private void assertSegmentTags(String responseJson, long taggedCustomerId, long emptyCustomerId,
                                   List<String> expectedSorted) throws Exception {
        var items = objectMapper.readTree(responseJson).at("/data/items");
        java.util.List<String> actualTagged = new java.util.ArrayList<>();
        java.util.List<String> actualEmpty = null;
        for (var item : items) {
            long id = item.get("id").asLong();
            if (id == taggedCustomerId) {
                item.get("tagCodes").forEach(node -> actualTagged.add(node.asText()));
            } else if (id == emptyCustomerId) {
                java.util.List<String> emptyTags = new java.util.ArrayList<>();
                item.get("tagCodes").forEach(node -> emptyTags.add(node.asText()));
                actualEmpty = emptyTags;
            }
        }
        assertThat(actualTagged).hasSize(25).containsExactlyElementsOf(expectedSorted);
        assertThat(actualEmpty).isNotNull().isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions createSegment(
            AuthCookies user, String name, Map<String, Object> filters, String idempotencyKey) throws Exception {
        return mockMvc.perform(user.withAuth(post("/api/v1/customer-segments"))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", name, "filters", filters))));
    }

    private Map<String, Object> filters(int minimumRedeemed) {
        return new java.util.LinkedHashMap<>(Map.of(
                "cityCodes", List.of("BJ"), "tierCodes", List.of("A"),
                "attentionProjectIds", List.of(101), "purchasedProjectIds", List.of(101),
                "redeemedProjectIds", List.of(101), "redeemedMinCount", minimumRedeemed));
    }

    private long customer(AuthCookies admin, String nickname) throws Exception {
        String response = mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .header("Idempotency-Key", key("customer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", nickname, "ownerStaffId", 2))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/id").asLong();
    }

    private void replaceAttention(AuthCookies admin, long customerId, long version, long projectId) throws Exception {
        mockMvc.perform(admin.withAuth(patch("/api/v1/customers/{id}", customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("projectIds", List.of(projectId), "version", version))))
                .andExpect(status().isOk());
    }

    private long entitlementFixture(long customerId, long projectId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,list_amount_fen,discount_amount_fen,"
                        + "payable_fen,paid_fen,refunded_fen,ordered_at,paid_at) VALUES "
                        + "(1,?,?,2,2,'CRM','REGULAR','PAID','PLATFORM',100,0,100,100,0,?,?)",
                "V33-O-" + suffix, customerId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        Long orderId = jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE order_no=?",
                Long.class, "V33-O-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_order_item (tenant_id,order_id,sku_id,package_version_id,"
                        + "sku_name_snapshot,purchase_quantity,list_amount_fen,paid_allocated_fen) "
                        + "VALUES (1,?,301,301,'V33测试',1,100,100)", orderId);
        Long itemId = jdbcTemplate.queryForObject("SELECT id FROM crm_order_item WHERE order_id=?",
                Long.class, orderId);
        jdbcTemplate.update("INSERT INTO crm_entitlement (tenant_id,customer_id,order_item_id,project_id,"
                + "granted_count,available_count,status) VALUES (1,?,?,?,3,0,'REVOKED')",
                customerId, itemId, projectId);
        return jdbcTemplate.queryForObject("SELECT id FROM crm_entitlement WHERE order_item_id=? AND project_id=?",
                Long.class, itemId, projectId);
    }

    private void ledger(long entitlementId, String type, int delta, String sourceType, String sourceRef) {
        jdbcTemplate.update("INSERT INTO crm_entitlement_ledger (tenant_id,entitlement_id,txn_type,delta_count,"
                        + "source_type,source_ref,idempotency_key,actor_user_id) VALUES (1,?,?,?,?,?,?,1)",
                entitlementId, type, delta, sourceType, sourceRef, "v33-ledger-" + sourceRef);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return "v33-" + prefix + "-" + UUID.randomUUID();
    }
}
