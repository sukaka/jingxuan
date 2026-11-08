package com.whale.crm.integration.youzan.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whale.crm.integration.youzan.adapter.MockYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanOrderLine;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.outbox.relay.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YouzanAcceptanceIT extends AbstractMysqlIT {
    private static final long TENANT_ID = 1L;
    private static final String SHOP = "MOCK-SHOP-001";
    private static final String LINKED_EXTERNAL_CUSTOMER = "YZ-DEMO-CUSTOMER-1010";
    private static final long LINKED_CUSTOMER_ID = 1010L;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockYouzanAdapter adapter;
    @Autowired
    private AesGcmCipher cipher;

    private AuthCookies admin;
    private AuthCookies manager1;
    private AuthCookies manager2;

    @BeforeAll
    void setUp() throws Exception {
        admin = login("admin");
        manager1 = login("manager1");
        manager2 = login("manager2");
    }

    @Test
    void o03WebhookAndPullConcurrencyCreatesOnePaidOrderAndStaleWaitPayCannotRegressIt() throws Exception {
        YouzanOrderEvent paid = MockYouzanAdapter.paidEvent();
        String raw = json(paid);
        int workers = 6;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CallResult>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                boolean pull = i == 0 || i == workers - 1;
                calls.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (pull) {
                        return call(syncRequest(admin, "0", 1, key("o03-concurrent-sync")), "sync");
                    }
                    long timestamp = Instant.now().getEpochSecond();
                    return call(webhookRequest(raw, timestamp, adapter.signMock(raw, timestamp)), "webhook");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<CallResult> future : calls) {
                CallResult result = future.get(45, TimeUnit.SECONDS);
                if ("sync".equals(result.kind())) {
                    assertThat(result.status()).as("sync response: %s", result.body()).isEqualTo(200);
                    assertThat(result.body().at("/data/received").asInt()).isEqualTo(1);
                    assertThat(result.body().at("/data/processed").asInt()).isEqualTo(1);
                    assertThat(result.body().at("/data/issues").asInt()).isZero();
                    assertThat(result.body().at("/data/nextCursor").asText()).isEqualTo("1");
                } else {
                    assertThat(result.status()).as("webhook response: %s", result.body()).isEqualTo(202);
                    assertThat(result.body().at("/data/eventId").asText()).isEqualTo(paid.eventId());
                }
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        awaitEvent(paid.eventId(), "PROCESSED");
        long orderId = assertSingleOrderFacts(paid.externalOrderNo(), paid.externalTradeNo(), 362200L, 6);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?",
                SHOP, paid.eventId())).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?",
                "youzan.order.processed.v1:" + paid.eventId())).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?",
                "youzan.sync.completed.v1:" + SHOP + ":"
                        + com.whale.crm.platform.idempotency.IdempotencyService.sha256("1"))).isEqualTo(1);
        JsonNode paidPayload = outboxPayload("order.paid.v1:" + orderId);
        assertThat(paidPayload.at("/orderId").asLong()).isEqualTo(orderId);
        assertThat(paidPayload.at("/amountFen").asLong()).isEqualTo(362200L);
        assertThat(paidPayload.at("/verificationMethod").asText()).isEqualTo("PROVIDER_CALLBACK");
        JsonNode processedPayload = outboxPayload("youzan.order.processed.v1:" + paid.eventId());
        assertThat(processedPayload.at("/orderId").asLong()).isEqualTo(orderId);
        assertThat(processedPayload.at("/externalOrderNo").asText()).isEqualTo(paid.externalOrderNo());

        JsonNode duplicate = postWebhook(paid);
        assertThat(duplicate.at("/duplicate").asBoolean()).isTrue();
        JsonNode replayedPull = data(syncRequest(admin, "0", 1, key("o03-replayed-sync")));
        assertThat(replayedPull.at("/received").asInt()).isEqualTo(1);
        assertThat(replayedPull.at("/duplicates").asInt()).isEqualTo(1);
        assertThat(replayedPull.at("/issues").asInt()).isZero();
        assertSingleOrderFacts(paid.externalOrderNo(), paid.externalTradeNo(), 362200L, 6);

        YouzanOrderEvent staleWaitPay = MockYouzanAdapter.pendingEvent();
        JsonNode staleResponse = postWebhook(staleWaitPay);
        assertThat(staleResponse.at("/duplicate").asBoolean()).isTrue();
        awaitEvent(staleWaitPay.eventId(), "PROCESSED");
        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT status,payable_fen,paid_fen FROM crm_order WHERE tenant_id=1 AND id=?", orderId);
        assertThat(order.get("status")).isEqualTo("PAID");
        assertThat(((Number) order.get("payable_fen")).longValue()).isEqualTo(362200L);
        assertThat(((Number) order.get("paid_fen")).longValue()).isEqualTo(362200L);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_payment_txn WHERE tenant_id=1 AND order_id=?", orderId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?",
                "order.paid.v1:" + orderId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger l JOIN crm_entitlement e ON e.id=l.entitlement_id "
                + "JOIN crm_order_item oi ON oi.id=e.order_item_id WHERE l.tenant_id=1 AND oi.order_id=? "
                + "AND l.txn_type='GRANT'", orderId)).isEqualTo(1);

        assertThat(data(manager1.withAuth(get("/api/v1/orders/{id}", orderId)))
                .at("/order/order/status").asText()).isEqualTo("PAID");
        mockMvc.perform(get("/api/v1/orders/{id}", orderId).cookie(manager2.cookies()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/integrations/youzan/orders").param("shopId", SHOP)
                        .cookie(manager2.cookies()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertForeignTenantOrderIsNotExposed(orderId);
    }

    @Test
    void o04MissingSkuIsTraceableThenManualMappingCustomerConfirmationAndRetryAreIdempotent() throws Exception {
        YouzanOrderEvent missing = MockYouzanAdapter.missingSkuEvent();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_sku_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_sku_id=?", SHOP, "YZ-SKU-MISSING")).isZero();
        int grantsBefore = grantCountForCustomer(LINKED_CUSTOMER_ID);

        JsonNode pulled = data(syncRequest(admin, "1", 1, key("o04-missing-sku-pull")));
        assertThat(pulled.at("/received").asInt()).isEqualTo(1);
        assertThat(pulled.at("/processed").asInt()).isZero();
        assertThat(pulled.at("/issues").asInt()).isEqualTo(1);
        Map<String, Object> raw = awaitEvent(missing.eventId(), "MAPPING_FAILED");
        long eventId = ((Number) raw.get("id")).longValue();
        assertThat(raw.get("source_system")).isEqualTo("PULL");
        JsonNode issue = issueFromApi(eventId, "MAPPING_FAILED", "OPEN");
        long issueId = issue.at("/id").asLong();
        assertThat(issue.at("/detailMasked").asText()).contains("YZ-SKU-MISSING");
        assertNoOrderFacts(missing.externalOrderNo(), missing.externalTradeNo());
        assertThat(grantCountForCustomer(LINKED_CUSTOMER_ID)).isEqualTo(grantsBefore);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id=? "
                + "AND aggregate_type='youzan'", "youzan.sync.issue.v1:" + missing.eventId() + ":MAPPING_FAILED"))
                .isEqualTo(1);

        mockMvc.perform(manager1.withAuth(post("/api/v1/integrations/youzan/sync-issues/{id}/retry", issueId))
                        .header("Idempotency-Key", key("o04-denied-retry")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(queryString("SELECT status FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?", issueId))
                .isEqualTo("OPEN");

        JsonNode mapping = data(admin.withAuth(post("/api/v1/integrations/youzan/sku-mappings"))
                .header("Idempotency-Key", key("o04-save-sku"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("shopId", SHOP, "externalSkuId", "YZ-SKU-MISSING", "crmSkuId", 301))));
        assertThat(mapping.at("/id").asLong()).isPositive();
        JsonNode existingLink = customerLinkFromApi(LINKED_EXTERNAL_CUSTOMER);
        JsonNode confirmedLink = data(admin.withAuth(post("/api/v1/integrations/youzan/customer-links"))
                .header("Idempotency-Key", key("o04-confirm-customer"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("shopId", SHOP, "externalCustomerId", LINKED_EXTERNAL_CUSTOMER,
                        "customerId", LINKED_CUSTOMER_ID, "version", existingLink.at("/version").asLong(),
                        "reason", "P1-1 IT：人工核对虚构客户映射"))));
        assertThat(confirmedLink.at("/customerId").asLong()).isEqualTo(LINKED_CUSTOMER_ID);
        assertThat(confirmedLink.at("/createdBy").asLong()).isEqualTo(1L);

        JsonNode retried = data(admin.withAuth(post("/api/v1/integrations/youzan/sync-issues/{id}/retry", issueId))
                .header("Idempotency-Key", key("o04-retry")));
        assertThat(retried.at("/processStatus").asText()).isEqualTo("PROCESSED");
        assertThat(retried.at("/orderId").asLong()).isPositive();
        long orderId = assertSingleOrderFacts(missing.externalOrderNo(), missing.externalTradeNo(), 181100L, 3);
        assertThat(retried.at("/orderId").asLong()).isEqualTo(orderId);
        JsonNode resolved = issueFromApi(eventId, "MAPPING_FAILED", "RESOLVED");
        assertThat(resolved.at("/resolvedBy").asLong()).isEqualTo(1L);
        assertThat(resolved.at("/resolvedAt").isTextual()).isTrue();
        assertThat(grantCountForCustomer(LINKED_CUSTOMER_ID)).isEqualTo(grantsBefore + 3);

        assertThat(postWebhook(missing).at("/duplicate").asBoolean()).isTrue();
        JsonNode replayedPull = data(syncRequest(admin, "1", 1, key("o04-replay-after-resolution")));
        assertThat(replayedPull.at("/duplicates").asInt()).isEqualTo(1);
        assertThat(replayedPull.at("/issues").asInt()).isZero();
        assertSingleOrderFacts(missing.externalOrderNo(), missing.externalTradeNo(), 181100L, 3);
        assertThat(grantCountForCustomer(LINKED_CUSTOMER_ID)).isEqualTo(grantsBefore + 3);
    }

    @Test
    void unresolvedCustomerDoesNotAutoCreateCustomerAndRawPayloadIsMaskedAndEncrypted() throws Exception {
        String suffix = suffix();
        String externalOrder = "IT-YZ-CUSTOMER-" + suffix;
        String externalCustomer = "IT-YZ-UNKNOWN-" + suffix;
        String tradeNo = "IT-YZ-CUSTOMER-TRADE-" + suffix;
        String nickname = "IT-YZ-UNMATCHED-" + suffix;
        String mobile = "13900000000";
        String buyerName = "虚构验收客户";
        String address = "虚构地址001";
        YouzanOrderEvent event = event("IT-YZ-CUSTOMER-EVENT-" + suffix, "ORDER_PAID", externalOrder,
                externalCustomer, tradeNo, 181100L, Instant.parse("2026-10-24T06:00:00Z"), "YZ-SKU-301", 1);
        ObjectNode body = objectMapper.valueToTree(event);
        body.put("mobile", mobile);
        body.put("nickname", nickname);
        body.put("buyerName", buyerName);
        body.putObject("receiver").put("contactName", buyerName).put("address", address);
        String rawBody = json(body);
        int customersBefore = queryInt("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=1");

        postWebhook(rawBody);
        Map<String, Object> raw = awaitEvent(event.eventId(), "CUSTOMER_UNRESOLVED");
        long eventId = ((Number) raw.get("id")).longValue();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=1")).isEqualTo(customersBefore);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=1 AND nickname=?", nickname)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=? "
                + "AND external_customer_id=?", SHOP, externalCustomer)).isZero();
        assertNoOrderFacts(externalOrder, tradeNo);
        assertThat(issueFromApi(eventId, "CUSTOMER_UNRESOLVED", "OPEN").at("/id").asLong()).isPositive();

        String masked = (String) raw.get("payload_masked_json");
        String encrypted = (String) raw.get("payload_cipher");
        String providerEncrypted = (String) raw.get("provider_payload_cipher");
        assertThat(masked).contains("\"mobile\":\"***\"", "\"nickname\":\"***\"",
                "\"buyerName\":\"***\"", "\"contactName\":\"***\"", "\"address\":\"***\"");
        assertThat(masked).doesNotContain(mobile, nickname, buyerName, address);
        assertThat(encrypted).isNotEqualTo(rawBody).isNotEqualTo(masked).doesNotContain(rawBody);
        assertThat(objectMapper.readTree(cipher.decrypt(providerEncrypted))).isEqualTo(objectMapper.readTree(rawBody));
        assertThat(queryInt("SELECT signature_valid FROM crm_youzan_event WHERE tenant_id=1 AND id=?", eventId))
                .isEqualTo(1);
    }

    @Test
    void forgedAndExpiredSignaturesAreUnauthorizedAndPersistNothing() throws Exception {
        String suffix = suffix();
        YouzanOrderEvent forged = event("IT-YZ-FORGED-" + suffix, "ORDER_PAID", "IT-YZ-FORGED-ORDER-" + suffix,
                LINKED_EXTERNAL_CUSTOMER, "IT-YZ-FORGED-TRADE-" + suffix, 181100L,
                Instant.parse("2026-10-24T07:00:00Z"), "YZ-SKU-301", 1);
        YouzanOrderEvent expired = event("IT-YZ-EXPIRED-" + suffix, "ORDER_PAID", "IT-YZ-EXPIRED-ORDER-" + suffix,
                LINKED_EXTERNAL_CUSTOMER, "IT-YZ-EXPIRED-TRADE-" + suffix, 181100L,
                Instant.parse("2026-10-24T07:01:00Z"), "YZ-SKU-301", 1);
        String forgedRaw = json(forged);
        long now = Instant.now().getEpochSecond();
        String wrongSignature = adapter.signMock(forgedRaw + " ", now);
        mockMvc.perform(webhookRequest(forgedRaw, now, wrongSignature))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        String expiredRaw = json(expired);
        long expiredAt = now - 301;
        mockMvc.perform(webhookRequest(expiredRaw, expiredAt, adapter.signMock(expiredRaw, expiredAt)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        for (YouzanOrderEvent event : List.of(forged, expired)) {
            assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?",
                    SHOP, event.eventId())).isZero();
            assertNoOrderFacts(event.externalOrderNo(), event.externalTradeNo());
            assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id LIKE ?",
                    "%" + event.eventId() + "%")).isZero();
        }
    }

    @Test
    void paidAmountDifferentFromExistingExternalOrderCreatesPaymentMismatchWithoutPaymentOrGrants() throws Exception {
        String suffix = suffix();
        String externalOrder = "IT-YZ-AMOUNT-" + suffix;
        String tradeNo = "IT-YZ-AMOUNT-TRADE-" + suffix;
        YouzanOrderEvent waitPay = event("IT-YZ-AMOUNT-WAIT-" + suffix, "WAIT_PAY", externalOrder,
                LINKED_EXTERNAL_CUSTOMER, null, 181100L, Instant.parse("2026-10-24T08:00:00Z"), "YZ-SKU-301", 1);
        postWebhook(waitPay);
        awaitEvent(waitPay.eventId(), "PROCESSED");
        long orderId = orderIdFor(externalOrder);
        assertThat(queryString("SELECT status FROM crm_order WHERE tenant_id=1 AND id=?", orderId)).isEqualTo("DRAFT");
        assertNoPaymentArtifacts(orderId, tradeNo);

        YouzanOrderEvent mismatchedPaid = event("IT-YZ-AMOUNT-PAID-" + suffix, "ORDER_PAID", externalOrder,
                LINKED_EXTERNAL_CUSTOMER, tradeNo, 1L, Instant.parse("2026-10-24T08:01:00Z"), "YZ-SKU-301", 1);
        postWebhook(mismatchedPaid);
        Map<String, Object> raw = awaitEvent(mismatchedPaid.eventId(), "PAYMENT_MISMATCH");
        long eventId = ((Number) raw.get("id")).longValue();
        assertThat(issueFromApi(eventId, "PAYMENT_MISMATCH", "OPEN").at("/id").asLong()).isPositive();
        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT status,payable_fen,paid_fen FROM crm_order WHERE tenant_id=1 AND id=?", orderId);
        assertThat(order.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) order.get("payable_fen")).longValue()).isEqualTo(181100L);
        assertThat(((Number) order.get("paid_fen")).longValue()).isZero();
        assertNoPaymentArtifacts(orderId, tradeNo);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?",
                "youzan.sync.issue.v1:" + mismatchedPaid.eventId() + ":PAYMENT_MISMATCH")).isEqualTo(1);
    }

    @Test
    void lateOutboxConstraintFailureRollsBackWholePaymentAndCanBeRetriedWithoutDuplicates() throws Exception {
        String suffix = suffix();
        String eventKey = "IT-YZ-TX-" + suffix;
        String externalOrder = "IT-YZ-TX-ORDER-" + suffix;
        String tradeNo = "IT-YZ-TX-TRADE-" + suffix;
        YouzanOrderEvent event = event(eventKey, "ORDER_PAID", externalOrder, LINKED_EXTERNAL_CUSTOMER,
                tradeNo, 181100L, Instant.parse("2026-10-24T09:00:00Z"), "YZ-SKU-301", 1);
        String blockedEventId = "youzan.order.processed.v1:" + eventKey;
        String constraint = "it_yz_tx_" + suffix.replace("-", "");
        jdbcTemplate.execute("ALTER TABLE crm_outbox_event ADD CONSTRAINT " + constraint
                + " CHECK (event_id <> '" + blockedEventId + "')");
        Map<String, Object> raw;
        try {
            postWebhook(event);
            raw = awaitEvent(eventKey, "RETRY");
        } finally {
            jdbcTemplate.execute("ALTER TABLE crm_outbox_event DROP CHECK " + constraint);
        }
        long rawId = ((Number) raw.get("id")).longValue();
        assertNoOrderFacts(externalOrder, tradeNo);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE tenant_id=1 "
                + "AND scope_code='payment.provider_callback' AND idempotency_key=?", SHOP + ":" + tradeNo)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?", blockedEventId))
                .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='PAYMENT_CONFIRM' "
                + "AND reason LIKE ?", "%youzan.raw:" + rawId + "%")).isZero();
        long issueId = queryLong("SELECT id FROM crm_youzan_sync_issue WHERE tenant_id=1 AND event_id=? "
                + "AND issue_type='RETRY'", rawId);

        JsonNode retried = data(admin.withAuth(post("/api/v1/integrations/youzan/sync-issues/{id}/retry", issueId))
                .header("Idempotency-Key", key("tx-recovery")));
        assertThat(retried.at("/processStatus").asText()).isEqualTo("PROCESSED");
        long orderId = assertSingleOrderFacts(externalOrder, tradeNo, 181100L, 3);
        assertThat(retried.at("/orderId").asLong()).isEqualTo(orderId);
        assertThat(queryString("SELECT status FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?", issueId))
                .isEqualTo("RESOLVED");
        assertThat(postWebhook(event).at("/duplicate").asBoolean()).isTrue();
        assertSingleOrderFacts(externalOrder, tradeNo, 181100L, 3);
    }

    @Test
    void managementWritesRequireCampaignPermissionAndDeniedRequestsHaveNoSideEffects() throws Exception {
        String suffix = suffix();
        String deniedShop = "IT-YZ-DENIED-SHOP-" + suffix;
        String deniedSku = "IT-YZ-DENIED-SKU-" + suffix;
        String deniedCustomer = "IT-YZ-DENIED-CUSTOMER-" + suffix;
        mockMvc.perform(manager1.withAuth(post("/api/v1/integrations/youzan/sync"))
                        .header("Idempotency-Key", key("denied-sync"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("shopId", deniedShop, "cursor", "0", "limit", 1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(manager1.withAuth(post("/api/v1/integrations/youzan/sku-mappings"))
                        .header("Idempotency-Key", key("denied-sku"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("shopId", SHOP, "externalSkuId", deniedSku, "crmSkuId", 301))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(manager1.withAuth(post("/api/v1/integrations/youzan/customer-links"))
                        .header("Idempotency-Key", key("denied-customer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("shopId", SHOP, "externalCustomerId", deniedCustomer,
                                "customerId", LINKED_CUSTOMER_ID, "reason", "越权验收，不应写入"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_sku_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_sku_id=?", SHOP, deniedSku)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=? "
                + "AND external_customer_id=?", SHOP, deniedCustomer)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='YOUZAN_SYNC' "
                + "AND target_id=?", deniedShop)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 "
                + "AND event_type='youzan.sync.completed.v1' AND aggregate_id=?", deniedShop)).isZero();
    }

    private long assertSingleOrderFacts(String externalOrder, String tradeNo, long amountFen, int grantCount)
            throws Exception {
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", SHOP, externalOrder)).isEqualTo(1);
        long orderId = orderIdFor(externalOrder);
        Map<String, Object> order = jdbcTemplate.queryForMap("SELECT status,source_system,external_shop_id,"
                + "external_order_no,payable_fen,paid_fen FROM crm_order WHERE tenant_id=1 AND id=?", orderId);
        assertThat(order.get("status")).isEqualTo("PAID");
        assertThat(order.get("source_system")).isEqualTo("YOUZAN");
        assertThat(order.get("external_shop_id")).isEqualTo(SHOP);
        assertThat(order.get("external_order_no")).isEqualTo(externalOrder);
        assertThat(((Number) order.get("payable_fen")).longValue()).isEqualTo(amountFen);
        assertThat(((Number) order.get("paid_fen")).longValue()).isEqualTo(amountFen);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_order WHERE tenant_id=1 AND source_system='YOUZAN' "
                + "AND external_shop_id=? AND external_order_no=?", SHOP, externalOrder)).isEqualTo(1);

        List<Map<String, Object>> payments = jdbcTemplate.queryForList("SELECT external_trade_no,payment_channel,"
                + "amount_fen,status,verification_method,evidence_ref FROM crm_payment_txn WHERE tenant_id=1 AND order_id=?",
                orderId);
        assertThat(payments).hasSize(1);
        Map<String, Object> payment = payments.get(0);
        assertThat(payment.get("external_trade_no")).isEqualTo(tradeNo);
        assertThat(payment.get("payment_channel")).isEqualTo("YOUZAN");
        assertThat(((Number) payment.get("amount_fen")).longValue()).isEqualTo(amountFen);
        assertThat(payment.get("status")).isEqualTo("CONFIRMED");
        assertThat(payment.get("verification_method")).isEqualTo("PROVIDER_CALLBACK");
        String rawEventId = queryString("SELECT event_id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=? AND order_id=? ORDER BY id LIMIT 1", SHOP, externalOrder, orderId);
        long rawId = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?",
                SHOP, rawEventId);
        assertThat(payment.get("evidence_ref")).isEqualTo("youzan.raw:" + rawId);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_payment_txn WHERE tenant_id=1 AND payment_channel='YOUZAN' "
                + "AND external_trade_no=?", tradeNo)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE tenant_id=1 "
                + "AND scope_code='payment.provider_callback' AND idempotency_key=?", SHOP + ":" + tradeNo)).isEqualTo(1);

        List<Map<String, Object>> entitlements = jdbcTemplate.queryForList("SELECT e.id,e.granted_count,e.available_count "
                + "FROM crm_entitlement e JOIN crm_order_item oi ON oi.id=e.order_item_id "
                + "WHERE e.tenant_id=1 AND oi.order_id=?", orderId);
        assertThat(entitlements).hasSize(1);
        assertThat(((Number) entitlements.get(0).get("granted_count")).intValue()).isEqualTo(grantCount);
        assertThat(((Number) entitlements.get(0).get("available_count")).intValue()).isEqualTo(grantCount);
        long entitlementId = ((Number) entitlements.get(0).get("id")).longValue();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE tenant_id=1 AND entitlement_id=? "
                + "AND txn_type='GRANT' AND delta_count=?", entitlementId, grantCount)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE tenant_id=1 AND entitlement_id=?",
                entitlementId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id=? "
                + "AND event_type='order.paid.v1' AND aggregate_type='order'", "order.paid.v1:" + orderId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_type='order.paid.v1' "
                + "AND aggregate_id=?", Long.toString(orderId))).isEqualTo(1);
        return orderId;
    }

    private void assertNoOrderFacts(String externalOrder, String tradeNo) {
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", SHOP, externalOrder)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_order WHERE tenant_id=1 AND source_system='YOUZAN' "
                + "AND external_order_no=?", externalOrder)).isZero();
        if (tradeNo != null) {
            assertThat(queryInt("SELECT COUNT(*) FROM crm_payment_txn WHERE tenant_id=1 AND payment_channel='YOUZAN' "
                    + "AND external_trade_no=?", tradeNo)).isZero();
            assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE tenant_id=1 "
                    + "AND scope_code='payment.provider_callback' AND idempotency_key=?", SHOP + ":" + tradeNo)).isZero();
        }
    }

    private void assertNoPaymentArtifacts(long orderId, String tradeNo) {
        assertThat(queryInt("SELECT COUNT(*) FROM crm_payment_txn WHERE tenant_id=1 AND order_id=?", orderId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_payment_txn WHERE tenant_id=1 AND payment_channel='YOUZAN' "
                + "AND external_trade_no=?", tradeNo)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement e JOIN crm_order_item oi ON oi.id=e.order_item_id "
                + "WHERE e.tenant_id=1 AND oi.order_id=?", orderId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger l JOIN crm_entitlement e ON e.id=l.entitlement_id "
                + "JOIN crm_order_item oi ON oi.id=e.order_item_id WHERE l.tenant_id=1 AND oi.order_id=?", orderId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?",
                "order.paid.v1:" + orderId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_order_status_history WHERE tenant_id=1 AND order_id=? "
                + "AND to_status='PAID'", orderId)).isZero();
    }

    private void assertForeignTenantOrderIsNotExposed(long visibleOrderId) throws Exception {
        String foreignOrder = "IT-YZ-FOREIGN-TENANT-" + suffix();
        jdbcTemplate.update("INSERT INTO crm_youzan_event (tenant_id,shop_id,event_id,event_type,external_order_no,"
                        + "payload_masked_json,payload_cipher,signature_valid,source_system,process_status,received_at) "
                        + "VALUES (999,?,?,?,?,?,'synthetic-cipher',1,'PULL','PROCESSED',NOW(3))",
                SHOP, foreignOrder, "ORDER_PAID", foreignOrder, "{}");
        jdbcTemplate.update("INSERT INTO crm_youzan_order_map (tenant_id,shop_id,external_order_no,crm_order_id,"
                + "last_external_updated_at) VALUES (999,?,?,?,NOW(3))", SHOP, foreignOrder, visibleOrderId);
        try {
            JsonPage page = youzanOrders(manager1, "999");
            assertThat(page.externalOrderNos()).contains("YZ-MOCK-PAID-001").doesNotContain(foreignOrder);
            assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_order_map WHERE tenant_id=999 AND shop_id=? "
                    + "AND external_order_no=?", SHOP, foreignOrder)).isEqualTo(1);
        } finally {
            jdbcTemplate.update("DELETE FROM crm_youzan_order_map WHERE tenant_id=999 AND shop_id=? AND external_order_no=?",
                    SHOP, foreignOrder);
            jdbcTemplate.update("DELETE FROM crm_youzan_event WHERE tenant_id=999 AND shop_id=? AND event_id=?",
                    SHOP, foreignOrder);
        }
    }

    private JsonPage youzanOrders(AuthCookies actor, String spoofedTenant) throws Exception {
        JsonNode data = data(actor.withAuth(get("/api/v1/integrations/youzan/orders")
                        .param("shopId", SHOP).param("size", "100"))
                .header("X-Tenant-Id", spoofedTenant));
        List<String> externalOrderNos = new ArrayList<>();
        data.at("/items").forEach(item -> externalOrderNos.add(item.at("/externalOrderNo").asText()));
        return new JsonPage(externalOrderNos);
    }

    private Map<String, Object> awaitEvent(String eventKey, String expectedStatus) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Map<String, Object> latest = null;
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,process_status,source_system,"
                    + "payload_masked_json,payload_cipher,provider_payload_cipher,last_error FROM crm_youzan_event "
                    + "WHERE tenant_id=1 AND shop_id=? AND event_id=?", SHOP, eventKey);
            if (rows.size() == 1) {
                latest = rows.get(0);
                String actual = (String) latest.get("process_status");
                if (expectedStatus.equals(actual)) {
                    return latest;
                }
                if (!"RECEIVED".equals(actual)) {
                    assertThat(actual).as("有赞事件 %s 的终态；lastError=%s", eventKey, latest.get("last_error"))
                            .isEqualTo(expectedStatus);
                }
            }
            Thread.sleep(50);
        }
        assertThat(latest).as("等待有赞事件 %s 进入 %s", eventKey, expectedStatus).isNotNull();
        assertThat(latest.get("process_status")).isEqualTo(expectedStatus);
        return latest;
    }

    private JsonNode issueFromApi(long eventId, String type, String issueStatus) throws Exception {
        JsonNode page = data(admin.withAuth(get("/api/v1/integrations/youzan/sync-issues")
                .param("status", issueStatus).param("size", "100")));
        List<JsonNode> matches = new ArrayList<>();
        page.at("/items").forEach(item -> {
            if (item.at("/eventId").asLong() == eventId && type.equals(item.at("/issueType").asText())) {
                matches.add(item);
            }
        });
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).at("/status").asText()).isEqualTo(issueStatus);
        return matches.get(0);
    }

    private JsonNode customerLinkFromApi(String externalCustomerId) throws Exception {
        JsonNode links = data(admin.withAuth(get("/api/v1/integrations/youzan/customer-links")
                .param("shopId", SHOP)));
        List<JsonNode> matches = new ArrayList<>();
        links.forEach(link -> {
            if (externalCustomerId.equals(link.at("/externalCustomerId").asText())) {
                matches.add(link);
            }
        });
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private JsonNode postWebhook(YouzanOrderEvent event) throws Exception {
        return postWebhook(json(event));
    }

    private JsonNode postWebhook(String rawBody) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        return data(webhookRequest(rawBody, timestamp, adapter.signMock(rawBody, timestamp)));
    }

    private MockHttpServletRequestBuilder webhookRequest(String rawBody, long timestamp, String signature) {
        return post("/api/v1/webhooks/youzan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody)
                .header("X-Youzan-Mock-Signature", signature)
                .header("X-Youzan-Mock-Timestamp", timestamp);
    }

    private MockHttpServletRequestBuilder syncRequest(AuthCookies actor, String cursor, int limit, String key)
            throws Exception {
        return actor.withAuth(post("/api/v1/integrations/youzan/sync"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("shopId", SHOP, "cursor", cursor, "limit", limit)));
    }

    private CallResult call(MockHttpServletRequestBuilder request, String kind) throws Exception {
        var result = mockMvc.perform(request).andReturn().getResponse();
        return new CallResult(kind, result.getStatus(), objectMapper.readTree(result.getContentAsString()));
    }

    private YouzanOrderEvent event(String eventId, String eventType, String externalOrder, String externalCustomer,
                                   String tradeNo, long amountFen, Instant updatedAt, String sku, int quantity) {
        boolean paid = "ORDER_PAID".equals(eventType);
        return new YouzanOrderEvent(eventId, eventType, SHOP, externalOrder, externalCustomer, tradeNo,
                paid ? "PAID" : "WAIT_PAY", amountFen, paid ? updatedAt : null, updatedAt,
                "REGULAR", 1L, 1L, "PLATFORM", LocalDate.parse("2027-12-31"),
                List.of(new YouzanOrderLine(sku, quantity)));
    }

    private long orderIdFor(String externalOrder) {
        return queryLong("SELECT crm_order_id FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", SHOP, externalOrder);
    }

    private int grantCountForCustomer(long customerId) {
        Integer value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(delta_count),0) FROM crm_entitlement_ledger l "
                + "JOIN crm_entitlement e ON e.id=l.entitlement_id WHERE l.tenant_id=1 AND e.customer_id=? "
                + "AND l.txn_type='GRANT'", Integer.class, customerId);
        return value == null ? 0 : value;
    }

    private JsonNode outboxPayload(String eventId) throws Exception {
        String payload = queryString("SELECT payload_json FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?", eventId);
        return objectMapper.readTree(payload);
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return "it-p11-" + prefix + "-" + UUID.randomUUID();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private record CallResult(String kind, int status, JsonNode body) {
    }

    private record JsonPage(List<String> externalOrderNos) {
    }
}
