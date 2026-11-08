package com.whale.crm.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.refund.api.dto.ManualRefundSuccessRequest;
import com.whale.crm.refund.application.MockRefundProviderAdapter;
import com.whale.crm.refund.application.RefundApplicationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.outbox.relay.enabled=false", "app.refunds.mock.enabled=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RefundApplicationService refundService;
    @Autowired
    private CurrentUserService currentUserService;
    @Autowired
    private MockRefundProviderAdapter mockAdapter;

    private AuthCookies admin;
    private AuthCookies manager1;
    private AuthCookies manager2;
    private AuthCookies finance;
    private long multiProjectSku;

    @BeforeAll
    void setUp() throws Exception {
        admin = login("admin");
        manager1 = login("manager1");
        manager2 = login("manager2");
        finance = login("finance1");
        multiProjectSku = createTwoProjectSku();
    }

    @Test
    void r01RequestedApprovedAndProcessingNeverChangeAssets() throws Exception {
        PaidOrder paid = paidOrder(301, 181100);
        long entitlementId = entitlementIds(paid.orderId()).get(0);
        String createKey = key("r01-create");
        JsonNode created = data(createRequest(manager1, paid.orderId(), 1000,
                List.of(item(entitlementId, 1, 1000)), createKey, "R01"));
        long refundId = created.at("/refund/id").asLong();
        assertThat(created.at("/refund/status").asText()).isEqualTo("REQUESTED");
        assertThat(data(createRequest(manager1, paid.orderId(), 1000,
                List.of(item(entitlementId, 1, 1000)), createKey, "R01")).at("/refund/id").asLong())
                .isEqualTo(refundId);
        mockMvc.perform(createRequest(manager1, paid.orderId(), 1001,
                        List.of(item(entitlementId, 1, 1001)), createKey, "R01-CHANGED"))
                .andExpect(status().isConflict());
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);

        JsonNode approved = data(reviewRequest(finance, refundId, 0, 1000, key("r01-review")));
        assertThat(approved.at("/refund/status").asText()).isEqualTo("APPROVED");
        assertThat(approved.at("/refund/succeededFen").asLong()).isZero();
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);
        mockMvc.perform(executeRequest(finance, refundId, 1, "BANK_TRANSFER", key("r01-external"), 999))
                .andExpect(status().isBadRequest());
        JsonNode processing = data(executeRequest(finance, refundId, 1, "BANK_TRANSFER", key("r01-external"), 1000));
        assertThat(processing.at("/refund/status").asText()).isEqualTo("PROCESSING");
        assertThat(processing.at("/payments/0/status").asText()).isEqualTo("PENDING");
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);
    }

    @Test
    void r02ConcurrentSuccessCreatesOneCashFactOneRevokeAndOutboxes() throws Exception {
        PaidOrder paid = paidOrder(301, 181100);
        long entitlementId = entitlementIds(paid.orderId()).get(0);
        long refundId = processingRefund(manager1, finance, paid.orderId(), 1000,
                List.of(item(entitlementId, 1, 1000)), "BANK_TRANSFER", key("r02-external"));
        long cleanFile = file("CLEAN");
        long pendingFile = file("PENDING");
        String externalRefundNo = queryString("SELECT external_refund_no FROM crm_refund_payment WHERE refund_id=?", refundId);
        ManualRefundSuccessRequest pending = manualRequest(externalRefundNo, 1000, pendingFile, "R02-PENDING");
        mockMvc.perform(successRequest(finance, refundId, pending, key("r02-pending")))
                .andExpect(status().isConflict());
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);

        long wrongClassFile = file("CLEAN");
        jdbcTemplate.update("UPDATE crm_file_object SET classification='MATERIAL' WHERE id=?", wrongClassFile);
        ManualRefundSuccessRequest wrongClass = manualRequest(externalRefundNo, 1000, wrongClassFile, "R02-WRONG-CLASS");
        mockMvc.perform(successRequest(finance, refundId, wrongClass, key("r02-wrong-class")))
                .andExpect(status().isConflict());
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);

        ManualRefundSuccessRequest request = manualRequest(externalRefundNo, 1000, cleanFile, "R02-CLEAN");
        CurrentUser actor = currentUserService.loadById(5).orElseThrow();
        String successKey = key("r02-success");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return objectMapper.valueToTree(refundService.succeedManually(1, actor, refundId, request, successKey));
                }));
            }
            start.countDown();
            for (Future<JsonNode> future : futures) {
                assertThat(future.get().at("/refund/status").asText()).isEqualTo("SUCCEEDED");
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", paid.orderId())).isEqualTo(1000);
        assertThat(queryLong("SELECT succeeded_fen FROM crm_refund WHERE id=?", refundId)).isEqualTo(1000);
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", entitlementId)).isEqualTo(2);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_refund_payment WHERE refund_id=? AND status='SUCCEEDED'", refundId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='REFUND_REVOKE'", entitlementId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", "refund.succeeded.v1:" + refundId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", "entitlement.refund_revoked.v1:" + refundId + ":" + entitlementId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE action_code='REFUND_SUCCEED' AND target_id=?", Long.toString(refundId))).isEqualTo(1);

        data(successRequest(finance, refundId, request, successKey));
        data(successRequest(finance, refundId, request, key("r02-natural-replay")));
        mockMvc.perform(successRequest(finance, refundId,
                        manualRequest(externalRefundNo, 1000, cleanFile, "R02-DIFFERENT"), successKey))
                .andExpect(status().isConflict());
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", paid.orderId())).isEqualTo(1000);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='REFUND_REVOKE'", entitlementId)).isEqualTo(1);
    }

    @Test
    void failedCallbackDoesNotChangeAssetsAndNewExternalNumberCanRetry() throws Exception {
        PaidOrder paid = paidOrder(301, 181100);
        long entitlementId = entitlementIds(paid.orderId()).get(0);
        String firstExternal = key("mock-failed");
        long refundId = processingRefund(manager1, finance, paid.orderId(), 1000,
                List.of(item(entitlementId, 1, 1000)), "MOCK", firstExternal);
        String failedRaw = callbackJson(firstExternal, 1000, "FAILED", "MOCK-FAILED");
        mockMvc.perform(callback(failedRaw, key("callback-failed"), false))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(callback(failedRaw, key("callback-bad-signature"), true)
                        .header("X-Refund-Mock-Signature", "bad"))
                .andExpect(status().isUnauthorized());
        JsonNode failed = data(callback(failedRaw, key("callback-failed"), true));
        assertThat(failed.at("/refund/status").asText()).isEqualTo("FAILED");
        assertUnchanged(paid.orderId(), entitlementId, 3, refundId);

        String secondExternal = key("mock-succeeded");
        JsonNode retry = data(executeRequest(finance, refundId, 3, "MOCK", secondExternal, 1000));
        assertThat(retry.at("/refund/status").asText()).isEqualTo("PROCESSING");
        String succeededRaw = callbackJson(secondExternal, 1000, "SUCCEEDED", "MOCK-OK");
        String callbackKey = key("callback-success");
        data(callback(succeededRaw, callbackKey, true));
        data(callback(succeededRaw, callbackKey, true));
        data(callback(succeededRaw, key("callback-success-natural"), true));
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", paid.orderId())).isEqualTo(1000);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_refund_payment WHERE refund_id=? AND status='SUCCEEDED'", refundId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='REFUND_REVOKE'", entitlementId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", "refund.succeeded.v1:" + refundId)).isEqualTo(1);
    }

    @Test
    void nthEntitlementFailureRollsBackCashEarlierRevokesOutboxAuditAndIdempotency() throws Exception {
        PaidOrder paid = paidOrder(multiProjectSku, 1000);
        List<Long> entitlementIds = entitlementIds(paid.orderId());
        long refundId = processingRefund(manager1, finance, paid.orderId(), 1000,
                List.of(item(entitlementIds.get(0), 1, 400), item(entitlementIds.get(1), 1, 600)),
                "BANK_TRANSFER", key("rollback-external"));
        long otherCustomer = createCustomer(admin, "IT-REFUND-OTHER", 3);
        jdbcTemplate.update("UPDATE crm_entitlement SET customer_id=? WHERE id=?", otherCustomer, entitlementIds.get(1));
        String external = queryString("SELECT external_refund_no FROM crm_refund_payment WHERE refund_id=?", refundId);
        String successKey = key("rollback-success");
        mockMvc.perform(successRequest(finance, refundId,
                        manualRequest(external, 1000, file("CLEAN"), "ROLLBACK"), successKey))
                .andExpect(status().isConflict());

        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", paid.orderId())).isZero();
        assertThat(queryString("SELECT status FROM crm_refund WHERE id=?", refundId)).isEqualTo("PROCESSING");
        assertThat(queryString("SELECT status FROM crm_refund_payment WHERE refund_id=?", refundId)).isEqualTo("PENDING");
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", entitlementIds.get(0))).isEqualTo(2);
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", entitlementIds.get(1))).isEqualTo(3);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id IN (?,?) AND txn_type='REFUND_REVOKE'", entitlementIds.get(0), entitlementIds.get(1))).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=? OR event_id LIKE ?", "refund.succeeded.v1:" + refundId, "entitlement.refund_revoked.v1:" + refundId + ":%")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE action_code='REFUND_SUCCEED' AND target_id=?", Long.toString(refundId))).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE scope_code='refund.success' AND idempotency_key=?", successKey)).isZero();
    }

    @Test
    void selfReviewFunctionPermissionAndCustomerScopeAreEnforced() throws Exception {
        PaidOrder paid = paidOrder(301, 181100);
        long entitlementId = entitlementIds(paid.orderId()).get(0);
        long refundId = data(createRequest(admin, paid.orderId(), 1000,
                List.of(item(entitlementId, 1, 1000)), key("self-create"), "SELF")).at("/refund/id").asLong();
        mockMvc.perform(reviewRequest(admin, refundId, 0, 1000, key("self-review")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/refunds/" + refundId).cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(executeRequest(manager1, refundId, 0, "BANK_TRANSFER", key("forbidden"), 1000))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/refunds").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    private long processingRefund(AuthCookies creator, AuthCookies finance, long orderId, long amount,
                                  List<Map<String, Object>> items, String provider, String external) throws Exception {
        long id = data(createRequest(creator, orderId, amount, items, key("flow-create"), "FLOW"))
                .at("/refund/id").asLong();
        data(reviewRequest(finance, id, 0, amount, key("flow-review")));
        data(executeRequest(finance, id, 1, provider, external, amount));
        return id;
    }

    private MockHttpServletRequestBuilder createRequest(AuthCookies actor, long orderId, long amount,
                                                         List<Map<String, Object>> items, String key, String reason) throws Exception {
        Map<String, Object> body = Map.of("orderId", orderId, "requestedFen", amount, "reasonCode", "CUSTOMER_REQUEST",
                "reasonDetail", reason, "items", items);
        return actor.withAuth(post("/api/v1/refunds")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private MockHttpServletRequestBuilder reviewRequest(AuthCookies actor, long refundId, long version,
                                                         long amount, String key) throws Exception {
        Map<String, Object> body = Map.of("version", version, "decision", "APPROVED", "approvedFen", amount, "reason", "IT-APPROVE");
        return actor.withAuth(post("/api/v1/refunds/" + refundId + "/review")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private MockHttpServletRequestBuilder executeRequest(AuthCookies actor, long refundId, long version, String provider,
                                                          String external, long amount) throws Exception {
        Map<String, Object> body = Map.of("version", version, "provider", provider, "externalRefundNo", external,
                "amountFen", amount, "evidenceRef", "IT-EXECUTE");
        return actor.withAuth(post("/api/v1/refunds/" + refundId + "/executions")).header("Idempotency-Key", key("execute"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private MockHttpServletRequestBuilder successRequest(AuthCookies actor, long refundId,
                                                          ManualRefundSuccessRequest body, String key) throws Exception {
        return actor.withAuth(post("/api/v1/refunds/" + refundId + "/success")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private ManualRefundSuccessRequest manualRequest(String external, long amount, long fileId, String evidence) {
        return new ManualRefundSuccessRequest(external, amount, Instant.now().minusSeconds(10), evidence, fileId);
    }

    private MockHttpServletRequestBuilder callback(String raw, String key, boolean validSignature) {
        long timestamp = Instant.now().getEpochSecond();
        String signature = validSignature ? mockAdapter.sign(raw, timestamp) : "missing";
        return post("/api/v1/integrations/refunds/MOCK/callback")
                .header("Idempotency-Key", key)
                .header("X-Refund-Mock-Timestamp", timestamp)
                .header("X-Refund-Mock-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).content(raw);
    }

    private String callbackJson(String external, long amount, String status, String evidence) throws Exception {
        return json(Map.of("externalRefundNo", external, "amountFen", amount, "status", status,
                "occurredAt", Instant.now().minusSeconds(5).toString(), "evidenceRef", evidence));
    }

    private PaidOrder paidOrder(long skuId, long amount) throws Exception {
        long customerId = createCustomer(admin, "IT-REFUND-" + UUID.randomUUID(), 2);
        long orderId = createOrder(admin, customerId, skuId, amount);
        submitOrder(admin, orderId);
        confirmPayment(admin, orderId, key("pay"), amount);
        return new PaidOrder(customerId, orderId);
    }

    private long createOrder(AuthCookies actor, long customerId, long skuId, long amount) throws Exception {
        Map<String, Object> body = Map.of("customerId", customerId, "receiverType", "PLATFORM", "orderTypeCode", "REGULAR",
                "campaignId", 1, "campaignBatchId", 1, "payableFen", amount, "validTo", "2027-12-31",
                "items", List.of(Map.of("skuId", skuId, "quantity", 1)));
        return data(actor.withAuth(post("/api/v1/orders")).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .at("/order/order/id").asLong();
    }

    private void submitOrder(AuthCookies actor, long orderId) throws Exception {
        data(actor.withAuth(post("/api/v1/orders/" + orderId + "/submit"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
    }

    private void confirmPayment(AuthCookies actor, long orderId, String tradeNo, long amount) throws Exception {
        String body = "{\"externalTradeNo\":\"" + tradeNo + "\",\"paymentChannel\":\"BANK_TRANSFER\","
                + "\"amountFen\":" + amount + ",\"paidAt\":\"2026-10-24T03:00:00Z\",\"evidenceRef\":\"IT-PAID\"}";
        data(actor.withAuth(post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", tradeNo).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long createCustomer(AuthCookies actor, String nickname, long ownerId) throws Exception {
        Map<String, Object> body = Map.of("nickname", nickname, "ownerStaffId", ownerId);
        return data(actor.withAuth(post("/api/v1/customers")).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .at("/id").asLong();
    }

    private long createTwoProjectSku() throws Exception {
        Map<String, Object> versionBody = Map.of("selectionMode", "USE_N", "choiceCount", 2,
                "projects", List.of(Map.of("projectId", 101, "grantCount", 2, "allocationPriceFen", 400),
                        Map.of("projectId", 102, "grantCount", 3, "allocationPriceFen", 600)));
        long versionId = data(admin.withAuth(post("/api/v1/packages/201/versions"))
                .contentType(MediaType.APPLICATION_JSON).content(json(versionBody))).at("/id").asLong();
        data(admin.withAuth(post("/api/v1/package-versions/" + versionId + "/activate"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
        Map<String, Object> skuBody = Map.of("skuCode", "IT-REFUND-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "packageVersionId", versionId, "specName", "退款双权益", "salePriceFen", 1000, "institutionIds", List.of(1));
        long skuId = data(admin.withAuth(post("/api/v1/skus")).contentType(MediaType.APPLICATION_JSON).content(json(skuBody)))
                .at("/id").asLong();
        data(admin.withAuth(post("/api/v1/skus/" + skuId + "/activate"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
        return skuId;
    }

    private long file(String scanStatus) {
        String objectKey = "sealed/1/it-refund-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO crm_file_object (tenant_id,object_key,mime_type,size_bytes,sha256,classification,scan_status,uploaded_by) VALUES (1,?,'application/pdf',1,?,'PAYMENT',?,1)",
                objectKey, UUID.randomUUID().toString().replace("-", "").repeat(2), scanStatus);
        return queryLong("SELECT id FROM crm_file_object WHERE object_key=?", objectKey);
    }

    private List<Long> entitlementIds(long orderId) {
        return jdbcTemplate.queryForList("SELECT e.id FROM crm_entitlement e JOIN crm_order_item oi ON oi.id=e.order_item_id WHERE oi.order_id=? ORDER BY e.id", Long.class, orderId);
    }

    private Map<String, Object> item(long entitlementId, int count, long amount) {
        return Map.of("entitlementId", entitlementId, "revokeCount", count, "allocatedFen", amount);
    }

    private void assertUnchanged(long orderId, long entitlementId, int balance, long refundId) {
        assertThat(queryLong("SELECT refunded_fen FROM crm_order WHERE id=?", orderId)).isZero();
        assertThat(queryLong("SELECT succeeded_fen FROM crm_refund WHERE id=?", refundId)).isZero();
        assertThat(queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", entitlementId)).isEqualTo(balance);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type='REFUND_REVOKE'", entitlementId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?", "refund.succeeded.v1:" + refundId)).isZero();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return "it-refund-" + prefix + "-" + UUID.randomUUID();
    }

    private record PaidOrder(long customerId, long orderId) { }
}
