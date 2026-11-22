package com.whale.crm.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.support.KafkaTestContainer;
import com.whale.crm.trading.application.OutboxRelayService;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F5, F6, F7 & F8 全链路交易-履约-结算-冲正-退款-分层-报表端到端闭环（含真实 Kafka Broker）：
 * - H-BIZ-02 (USE_N PENDING_Q01 422 阻断) & H-BIZ-01 (同套餐下不同机构 SKU 隔离)
 * - FIXED 2项目 SKU -> 订单提交 -> 财务确认收款 -> OutboxRelayService -> Kafka crm.order.events.v1 -> 客户分层升 S
 * - H-BIZ-08 同毫秒窗口、Tenant 2 无规则安全 ACK、无关 order.created.v1 安全 ACK
 * - 预约 -> 机构操作员确认/签到/服务完成 -> 核销 R1a/R1b/R2 -> 月度结算 B1 (多行净额校验 H-BIZ-07) -> 全额付款后归零
 * - C-01 双向锁：Dir B 已结算 R1 冲正 409；Dir A R2 申请冲正后批结算 409，批准冲正后仍 409
 * - M-BE-06 & M-BE-08：同月第二个月度批次 B2 补结 R3 并完成 0 分付款至 PAID
 * - H-BIZ-05 & M-BE-07 & H-BIZ-08：部分退款 -> voucherFileId/evidenceRef 落库 -> 订单重算 FULFILLED -> Kafka 降级审批准至 D
 */
@SpringBootTest(properties = {
        "app.customer-tier.kafka.enabled=true",
        "app.outbox.relay.enabled=false",
        "app.notification.kafka.enabled=false",
        "app.notification.delivery.worker-enabled=false",
        "app.files.enabled=true",
        "app.files.endpoint=http://127.0.0.1:9",
        "app.files.access-key=synthetic-test-only",
        "app.files.secret-key=synthetic-test-only",
        "app.files.max-file-size-bytes=65536",
        "app.exports.worker.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TradingFulfillmentSettlementClosedLoopE2EIT extends AbstractE2EIT {

    private static final GenericContainer<?> KAFKA = KafkaTestContainer.start();

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        KafkaTestContainer.registerProperties(registry, KAFKA);
    }

    @MockitoBean
    private PrivateObjectStorage storage;

    @MockitoBean
    private ClamAvScanner scanner;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

    @BeforeEach
    void initStorageMocks() throws Exception {
        configureMockStorage(storage, scanner, objectStore);
    }

    @Test
    void fullClosedLoopOrderKafkaTierFulfillmentSettlementReversalAndPartialRefund() throws Exception {
        awaitListenerReady();
        activateTierRule();

        AuthCookies admin = login("admin");
        AuthCookies manager1 = login("manager1");
        AuthCookies finance1 = login("finance1");
        AuthCookies operation1 = login("operation1");
        AuthCookies inst1Staff = login("inst1");

        // Fresh institution 1-like dedicated institution + operator so reporting pendingReconciliationFen is 100% isolated
        long dedicatedInstId = createDedicatedActiveInstitution();
        AuthCookies dedicatedOperator = createDedicatedOperator(dedicatedInstId);

        // 1. H-BIZ-02: USE_N package version activation blocked with 422 PENDING_BUSINESS_RULE Q01
        long pkgId = data(admin.withAuth(post("/api/v1/packages"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("packageCode", shortUuid("PKG-CL"), "packageName", "E2E闭环套餐"))))
                .at("/id").asLong();

        long useNVersionId = data(admin.withAuth(post("/api/v1/packages/" + pkgId + "/versions"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("selectionMode", "USE_N", "choiceCount", 2,
                        "projects", List.of(
                                Map.of("projectId", 101, "grantCount", 2),
                                Map.of("projectId", 102, "grantCount", 3))))))
                .at("/id").asLong();

        mockMvc.perform(admin.withAuth(post("/api/v1/package-versions/" + useNVersionId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PENDING_BUSINESS_RULE"))
                .andExpect(jsonPath("$.details[0].field").value("Q01"));

        // 2. FIXED 2-project SKU (3x101 + 3x102 = 6 total, salePriceFen = 1_000_000) + H-BIZ-01 sibling isolation
        long fixedVersionId = data(admin.withAuth(post("/api/v1/packages/" + pkgId + "/versions"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("selectionMode", "FIXED", "choiceCount", 0,
                        "projects", List.of(
                                Map.of("projectId", 101, "grantCount", 3, "allocationPriceFen", 100_000),
                                Map.of("projectId", 102, "grantCount", 3, "allocationPriceFen", 233_333))))))
                .at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/package-versions/" + fixedVersionId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());

        long mainSkuId = data(admin.withAuth(post("/api/v1/skus"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("skuCode", shortUuid("SKU-MAIN"), "packageVersionId", fixedVersionId,
                        "specName", "E2E主规格", "salePriceFen", 1_000_000L,
                        "institutionIds", List.of(dedicatedInstId)))))
                .at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/skus/" + mainSkuId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());

        // Sibling SKU bound ONLY to institution 2 must not leak to inst 1 or dedicatedInstId
        long siblingSkuId = data(admin.withAuth(post("/api/v1/skus"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("skuCode", shortUuid("SKU-SIB2"), "packageVersionId", fixedVersionId,
                        "specName", "机构2隔离规格", "salePriceFen", 888_800L,
                        "institutionIds", List.of(2L)))))
                .at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/skus/" + siblingSkuId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());

        // H-BIZ-01: inst1 sees neither dedicatedInstId-only mainSku nor inst2-only siblingSku
        String inst1SkuList = mockMvc.perform(get("/api/v1/skus?packageId=" + pkgId + "&size=50")
                        .cookie(inst1Staff.cookies()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(inst1SkuList).doesNotContain("SKU-MAIN", "SKU-SIB2");

        // 3. Order -> Submit -> Payment Confirm -> Outbox -> Kafka -> Customer Tier S (H-BIZ-08)
        long customerId = data(manager1.withAuth(post("/api/v1/customers"))
                .header("Idempotency-Key", uniqueKey("e2e-cl-cust"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nickname", shortUuid("CLCust")))))
                .at("/id").asLong();

        long orderId = data(manager1.withAuth(post("/api/v1/orders"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "customerId", customerId,
                        "receiverType", "PLATFORM",
                        "orderTypeCode", "REGULAR",
                        "payableFen", 1_000_000L,
                        "validTo", LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(30).toString(),
                        "items", List.of(Map.of("skuId", mainSkuId, "quantity", 1))))))
                .at("/order/order/id").asLong();

        mockMvc.perform(manager1.withAuth(post("/api/v1/orders/" + orderId + "/submit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());

        long paymentVoucherId = uploadCleanFileViaHttp(finance1, objectStore, shortUuid("pay") + ".pdf",
                "application/pdf", cleanPdfBytes(), "PAYMENT", null);

        JsonNode payRes = data(finance1.withAuth(post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", uniqueKey("e2e-cl-pay"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "externalTradeNo", shortUuid("TRD"),
                        "paymentChannel", "BANK_TRANSFER",
                        "amountFen", 1_000_000L,
                        "paidAt", Instant.now().toString(),
                        "evidenceRef", "E2E收款回执",
                        "voucherFileId", paymentVoucherId))));
        assertThat(payRes.at("/order/order/status").asText()).isEqualTo("PAID");

        long ent101 = -1L;
        long ent102 = -1L;
        for (JsonNode ent : payRes.withArray("entitlements")) {
            long projId = ent.at("/projectId").asLong();
            if (projId == 101L) {
                ent101 = ent.at("/id").asLong();
            } else if (projId == 102L) {
                ent102 = ent.at("/id").asLong();
            }
        }
        assertThat(ent101).isPositive();
        assertThat(ent102).isPositive();

        String paidEventId = "order.paid.v1:" + orderId;
        relayUntilSent(paidEventId);
        awaitTierSnapshot(1L, paidEventId);

        mockMvc.perform(get("/api/v1/customers/" + customerId + "/tier").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tierCode").value("S"));

        // H-BIZ-08: Unrelated order.created.v1 & Tenant 2 missing-rule order.paid.v1 ack cleanly in inbox
        String unrelatedEventId = "e2e-order-created-" + UUID.randomUUID();
        kafkaTemplate.send(OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC, unrelatedEventId, """
                {"event_id":"%s","event_type":"order.created.v1","event_version":1,"tenant_id":1,
                "aggregate_type":"order","aggregate_id":"99999","occurred_at":"2025-04-01T12:00:00.123Z",
                "correlation_id":"%s","payload":{"orderId":99999}}
                """.formatted(unrelatedEventId, unrelatedEventId)).get(30, TimeUnit.SECONDS);
        awaitTierInbox(1L, unrelatedEventId);

        String t2OrderEventId = "e2e-t2-paid-" + UUID.randomUUID();
        kafkaTemplate.send(OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC, t2OrderEventId, """
                {"event_id":"%s","event_type":"order.paid.v1","event_version":1,"tenant_id":2,
                "aggregate_type":"order","aggregate_id":"99998","occurred_at":"2025-04-01T12:00:00.123Z",
                "correlation_id":"%s","payload":{"customerId":99998}}
                """.formatted(t2OrderEventId, t2OrderEventId)).get(30, TimeUnit.SECONDS);
        awaitTierInbox(2L, t2OrderEventId);

        // 4. Appointments & Operator Redemptions: R1a, R1b (for multi-line B1), R2 (for reversal C-01)
        long r1a = createAndPostRedemption(manager1, dedicatedOperator, customerId, dedicatedInstId, ent101, 1);
        long r1b = createAndPostRedemption(manager1, dedicatedOperator, customerId, dedicatedInstId, ent101, 1);
        long r2 = createAndPostRedemption(manager1, dedicatedOperator, customerId, dedicatedInstId, ent101, 1);

        // 5. MONTHLY settlement batch B1 (2 redemptions -> 2 lines) + H-BIZ-07 Reporting verification
        String currentMonth = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        long b1Id = data(finance1.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", uniqueKey("e2e-b1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", dedicatedInstId, "settlementType", "MONTHLY",
                        "periodKey", currentMonth, "redemptionIds", List.of(r1a, r1b)))))
                .at("/batch/id").asLong();

        data(finance1.withAuth(post("/api/v1/settlements/batches/" + b1Id + "/verification"))
                .header("Idempotency-Key", uniqueKey("e2e-b1-ver"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0, "ruleVersion", "E2E-RULE-1",
                        "grossFen", 5000, "commissionFen", 1000, "netPayableFen", 4000,
                        "verificationEvidence", "双行核对"))));

        // H-BIZ-07: Verified multi-line B1 reports 4000 (not 8000) for finance1, null for operation1/manager1/INSTITUTION
        JsonNode finRpt = data(get("/api/v1/reports/operations")
                .param("from", today).param("to", today).param("timeZone", "Asia/Shanghai")
                .param("institutionId", Long.toString(dedicatedInstId))
                .cookie(finance1.cookies()));
        JsonNode finInstRow = findInstRow(finRpt.at("/institutionRows"), dedicatedInstId);
        assertThat(finInstRow.at("/pendingReconciliationFen").asLong()).isEqualTo(4000L);
        assertThat(finInstRow.at("/pendingReconciliationStatus").asText()).isEqualTo("VERIFIED");

        JsonNode opRpt = data(get("/api/v1/reports/operations")
                .param("from", today).param("to", today).param("timeZone", "Asia/Shanghai")
                .param("institutionId", Long.toString(dedicatedInstId))
                .cookie(operation1.cookies()));
        assertThat(findInstRow(opRpt.at("/institutionRows"), dedicatedInstId)
                .at("/pendingReconciliationFen").isNull()).isTrue();

        // manager1 gets pendingReconciliationFen == null; dedicatedOperator lacks report:read -> 403;
        // INSTITUTION user with report:read + settlement:read gets null
        JsonNode mgrRpt = data(get("/api/v1/reports/operations")
                .param("from", today).param("to", today).param("timeZone", "Asia/Shanghai")
                .param("institutionId", Long.toString(dedicatedInstId))
                .cookie(manager1.cookies()));
        for (JsonNode row : mgrRpt.withArray("institutionRows")) {
            assertThat(row.at("/pendingReconciliationFen").isNull()).isTrue();
        }
        mockMvc.perform(get("/api/v1/reports/operations")
                        .param("from", today).param("to", today).param("timeZone", "Asia/Shanghai")
                        .cookie(dedicatedOperator.cookies()))
                .andExpect(status().isForbidden());
        AuthCookies instReportActor = createInstitutionReportActor(admin, dedicatedInstId);
        JsonNode instRpt = data(get("/api/v1/reports/operations")
                .param("from", today).param("to", today).param("timeZone", "Asia/Shanghai")
                .param("institutionId", Long.toString(dedicatedInstId))
                .cookie(instReportActor.cookies()));
        for (JsonNode row : instRpt.withArray("institutionRows")) {
            assertThat(row.at("/pendingReconciliationFen").isNull()).isTrue();
        }
        assertThat(instRpt.toString()).doesNotContain("\"pendingReconciliationFen\":4000");

        // Full payment on B1 -> pendingReconciliationFen drops to 0
        data(finance1.withAuth(post("/api/v1/settlements/batches/" + b1Id + "/payments"))
                .header("Idempotency-Key", uniqueKey("e2e-b1-pay"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "amountFen", 4000, "bankTradeNo", shortUuid("BK-B1"),
                        "paidAt", Instant.now().toString(), "voucherFileId", paymentVoucherId,
                        "evidenceRef", "B1全额打款"))));
        JsonNode finAfterPay = data(get("/api/v1/reports/operations")
                .param("from", today).param("to", today).param("timeZone", "Asia/Shanghai")
                .param("institutionId", Long.toString(dedicatedInstId))
                .cookie(finance1.cookies()));
        assertThat(findInstRow(finAfterPay.at("/institutionRows"), dedicatedInstId)
                .at("/pendingReconciliationFen").asLong()).isZero();

        // 6. C-01 Bidirectional Lock: Dir B (settled R1a cannot be reversed -> 409) & Dir A (reversed R2 cannot be batched -> 409)
        mockMvc.perform(manager1.withAuth(post("/api/v1/redemptions/" + r1a + "/reversal-requests"))
                        .header("Idempotency-Key", uniqueKey("e2e-c01-dirb"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("redemptionVersion", 0, "reason", "试图冲正已结算"))))
                .andExpect(status().isConflict());

        long rev2Id = data(manager1.withAuth(post("/api/v1/redemptions/" + r2 + "/reversal-requests"))
                        .header("Idempotency-Key", uniqueKey("e2e-c01-dira-req"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("redemptionVersion", 0, "reason", "E2E正常冲正R2"))))
                .at("/reversal/id").asLong();

        // Active REQUESTED reversal blocks batching R2 -> 409
        mockMvc.perform(finance1.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", uniqueKey("e2e-c01-dira-batch1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", dedicatedInstId, "settlementType", "MONTHLY",
                                "periodKey", currentMonth, "redemptionIds", List.of(r2)))))
                .andExpect(status().isConflict());

        // Approve R2 reversal -> restores 1 unit of ent101; R2 still blocked from batching -> 409
        mockMvc.perform(finance1.withAuth(post("/api/v1/redemption-reversals/" + rev2Id + "/approve"))
                        .header("Idempotency-Key", uniqueKey("e2e-c01-dira-app"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "reason", "同意冲正"))))
                .andExpect(status().isOk());

        mockMvc.perform(finance1.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", uniqueKey("e2e-c01-dira-batch2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", dedicatedInstId, "settlementType", "MONTHLY",
                                "periodKey", currentMonth, "redemptionIds", List.of(r2)))))
                .andExpect(status().isConflict());

        // 7. M-BE-06 & M-BE-08: Post R3 on restored ent101 -> 2nd MONTHLY batch B2 in same month -> 0-fen pay -> PAID
        long r3 = createAndPostRedemption(manager1, dedicatedOperator, customerId, dedicatedInstId, ent101, 1);
        long b2Id = data(finance1.withAuth(post("/api/v1/settlements/batches"))
                .header("Idempotency-Key", uniqueKey("e2e-b2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", dedicatedInstId, "settlementType", "MONTHLY",
                        "periodKey", currentMonth, "redemptionIds", List.of(r3)))))
                .at("/batch/id").asLong();
        data(finance1.withAuth(post("/api/v1/settlements/batches/" + b2Id + "/verification"))
                .header("Idempotency-Key", uniqueKey("e2e-b2-ver"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0, "ruleVersion", "E2E-RULE-ZERO",
                        "grossFen", 1000, "commissionFen", 1000, "netPayableFen", 0,
                        "verificationEvidence", "零元结算单"))));
        JsonNode b2Paid = data(finance1.withAuth(post("/api/v1/settlements/batches/" + b2Id + "/payments"))
                .header("Idempotency-Key", uniqueKey("e2e-b2-pay0"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "amountFen", 0, "bankTradeNo", shortUuid("BK-ZERO"),
                        "paidAt", Instant.now().toString(), "voucherFileId", paymentVoucherId,
                        "evidenceRef", "0分结算完结"))));
        assertThat(b2Paid.at("/batch/status").asText()).isEqualTo("PAID");
        assertThat(b2Paid.at("/batch/paidFen").asLong()).isZero();

        // 8. H-BIZ-05 & M-BE-07 & H-BIZ-08: Partial refund of remaining 3x ent102 (600_000 fen)
        long refundId = data(manager1.withAuth(post("/api/v1/refunds"))
                .header("Idempotency-Key", uniqueKey("e2e-ref-create"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "orderId", orderId,
                        "requestedFen", 600_000L,
                        "reasonCode", "CUSTOMER_REQUEST",
                        "reasonDetail", "部分项目退款",
                        "items", List.of(Map.of("entitlementId", ent102, "revokeCount", 3, "allocatedFen", 600_000L))))))
                .at("/refund/id").asLong();
        data(finance1.withAuth(post("/api/v1/refunds/" + refundId + "/review"))
                .header("Idempotency-Key", uniqueKey("e2e-ref-rev"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0, "decision", "APPROVED", "approvedFen", 600_000L,
                        "reason", "核准部分退款"))));
        String extRefundNo = shortUuid("E2E-REF");
        data(finance1.withAuth(post("/api/v1/refunds/" + refundId + "/executions"))
                .header("Idempotency-Key", uniqueKey("e2e-ref-exec"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "provider", "BANK_TRANSFER",
                        "externalRefundNo", extRefundNo, "amountFen", 600_000L))));
        String refundEvidence = "E2E-REFUND-VOUCHER-" + shortUuid("EVID");
        JsonNode refundSucceeded = data(finance1.withAuth(post("/api/v1/refunds/" + refundId + "/success"))
                .header("Idempotency-Key", uniqueKey("e2e-ref-succ"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("externalRefundNo", extRefundNo, "amountFen", 600_000L,
                        "succeededAt", Instant.now().toString(),
                        "voucherFileId", paymentVoucherId, "evidenceRef", refundEvidence))));
        assertThat(refundSucceeded.at("/payments/0/voucherFileId").asLong()).isEqualTo(paymentVoucherId);
        assertThat(refundSucceeded.at("/payments/0/evidenceRef").asText()).isEqualTo(refundEvidence);
        assertThat(queryString("SELECT status FROM crm_order WHERE id=?", orderId)).isEqualTo("FULFILLED");

        String refundEventId = "refund.succeeded.v1:" + refundId;
        relayUntilSent(refundEventId);
        awaitTierSnapshot(1L, refundEventId);

        long reviewId = awaitPendingDowngradeReview(customerId);
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-tier-reviews/" + reviewId + "/approve"))
                        .header("Idempotency-Key", uniqueKey("e2e-tier-dg-app"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "reason", "部分退款后确认降级"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/customers/" + customerId + "/tier").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tierCode").value("D"));
    }

    private long createAndPostRedemption(AuthCookies manager, AuthCookies operator, long customerId,
                                         long institutionId, long entitlementId, int count) throws Exception {
        long apptId = data(manager.withAuth(post("/api/v1/appointments"))
                .header("Idempotency-Key", uniqueKey("e2e-appt"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "customerId", customerId,
                        "institutionId", institutionId,
                        "scheduledAt", Instant.now().plusSeconds(3600).toString(),
                        "remark", "E2E履约预约",
                        "items", List.of(Map.of("entitlementId", entitlementId, "count", count))))))
                .at("/appointment/id").asLong();
        data(operator.withAuth(post("/api/v1/appointments/" + apptId + "/confirm"))
                .header("Idempotency-Key", uniqueKey("e2e-appt-conf"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"确认\"}"));
        data(operator.withAuth(post("/api/v1/appointments/" + apptId + "/check-in"))
                .header("Idempotency-Key", uniqueKey("e2e-appt-ci"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"到院\"}"));
        data(operator.withAuth(post("/api/v1/appointments/" + apptId + "/service-done"))
                .header("Idempotency-Key", uniqueKey("e2e-appt-sd"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2,\"reason\":\"服务完成\"}"));

        return data(operator.withAuth(post("/api/v1/appointments/" + apptId + "/redemptions"))
                .header("Idempotency-Key", uniqueKey("e2e-redeem"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "appointmentVersion", 3L,
                        "serviceAt", Instant.now().toString(),
                        "evidenceRef", shortUuid("RED-EVD"),
                        "lines", List.of(Map.of("entitlementId", entitlementId, "count", count))))))
                .at("/redemption/id").asLong();
    }

    private long createDedicatedActiveInstitution() {
        String no = shortUuid("E2E-CL-INST").toUpperCase();
        jdbcTemplate.update("INSERT INTO crm_institution (tenant_id,institution_no,full_name,status,settlement_type,"
                + "online_rate,offline_rate,platform_rate) VALUES (1,?,?,'ACTIVE','MONTHLY',0.18,0.12,0.15)",
                no, "E2E闭环机构-" + no);
        return queryLong("SELECT id FROM crm_institution WHERE tenant_id=1 AND institution_no=?", no);
    }

    private AuthCookies createDedicatedOperator(long institutionId) throws Exception {
        String username = shortUuid("e2e_cl_op");
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) "
                        + "SELECT 1,1,NULL,?,'E2E闭环操作员',password_hash,'INSTITUTION','ACTIVE' FROM sys_user WHERE id=10",
                username);
        long userId = queryLong("SELECT id FROM sys_user WHERE tenant_id=1 AND username=?", username);
        long staffRoleId = queryLong("SELECT id FROM sys_role WHERE tenant_id=1 AND role_code='INSTITUTION_STAFF'");
        jdbcTemplate.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (1,?,?)", userId, staffRoleId);
        jdbcTemplate.update("INSERT INTO crm_institution_user (tenant_id,institution_id,user_id,institution_role,status) "
                + "VALUES (1,?,?,'OPERATOR','ACTIVE')", institutionId, userId);
        return loginWithTenantCode(username, DEV_PASSWORD, "WHALE", 1L);
    }

    private AuthCookies createInstitutionReportActor(AuthCookies admin, long institutionId) throws Exception {
        String roleCode = shortCode("E2E_INST_RPT");
        long roleId = data(admin.withAuth(post("/api/v1/roles"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("roleCode", roleCode, "roleName", "机构报表测试角色"))))
                .at("/id").asLong();
        mockMvc.perform(admin.withAuth(putPermissionsRequest(roleId)))
                .andExpect(status().isOk());

        String username = shortUuid("e2e_inst_rpt");
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) "
                        + "SELECT 1,1,NULL,?,'机构报表号',password_hash,'INSTITUTION','ACTIVE' FROM sys_user WHERE id=10",
                username);
        long userId = queryLong("SELECT id FROM sys_user WHERE tenant_id=1 AND username=?", username);
        jdbcTemplate.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (1,?,?)", userId, roleId);
        jdbcTemplate.update("INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id) "
                + "VALUES (1,?,'customer','ALL',NULL),(1,?,'institution','INSTITUTION',NULL)", userId, userId);
        jdbcTemplate.update("INSERT INTO crm_institution_user (tenant_id,institution_id,user_id,institution_role,status) "
                + "VALUES (1,?,?,'OPERATOR','ACTIVE')", institutionId, userId);
        return loginWithTenantCode(username, DEV_PASSWORD, "WHALE", 1L);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putPermissionsRequest(long roleId)
            throws Exception {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/roles/" + roleId + "/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("permissionCodes", List.of("report:read", "settlement:read"))));
    }

    private JsonNode findInstRow(JsonNode array, long institutionId) {
        for (JsonNode item : array) {
            if (item.at("/institutionId").asLong() == institutionId) {
                return item;
            }
        }
        throw new AssertionError("Missing institutionId=" + institutionId + " in " + array);
    }

    private void activateTierRule() {
        jdbcTemplate.update("UPDATE crm_customer_tier_rule SET status='RETIRED',retired_at=CURRENT_TIMESTAMP(3),"
                + "version=version+1 WHERE tenant_id=1 AND status='ACTIVE'");
        String ruleCode = shortUuid("TIER_E2E").toUpperCase();
        jdbcTemplate.update("INSERT INTO crm_customer_tier_rule (tenant_id,rule_code,version_no,rule_name,zone_id,"
                + "status,metric_config_json,created_by,activated_at) VALUES (1,?,1,'E2E闭环分层规则','UTC',"
                + "'ACTIVE','[]',1,CURRENT_TIMESTAMP(3))", ruleCode);
        long ruleId = queryLong("SELECT id FROM crm_customer_tier_rule WHERE tenant_id=1 AND rule_code=?", ruleCode);
        jdbcTemplate.update("INSERT INTO crm_customer_tier_level (tenant_id,rule_id,tier_code,tier_name,sort_no,"
                + "min_score,min_consumption_fen) VALUES "
                + "(1,?,'S','S级',1,90,1000000),"
                + "(1,?,'A','A级',2,70,950000),"
                + "(1,?,'B','B级',3,50,900000),"
                + "(1,?,'C','C级',4,30,850000),"
                + "(1,?,'D','D级',5,0,0)", ruleId, ruleId, ruleId, ruleId, ruleId);
    }

    private void awaitListenerReady() throws InterruptedException {
        for (int i = 0; i < 1200; i++) {
            boolean ready = listenerRegistry.getListenerContainers().stream()
                    .allMatch(c -> c.isRunning() && !c.getAssignedPartitions().isEmpty());
            if (ready) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("CustomerTier Kafka listener partitions were not assigned in time");
    }

    private void relayUntilSent(String eventId) throws Exception {
        for (int i = 0; i < 1200; i++) {
            outboxRelayService.relayOnce();
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?",
                    String.class, eventId);
            if ("SENT".equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Outbox event not SENT in time: " + eventId);
    }

    private void awaitTierSnapshot(long tenantId, String eventId) throws InterruptedException {
        long count = 0;
        for (int i = 0; i < 900; i++) {
            count = queryLong("SELECT COUNT(*) FROM crm_customer_tier_metric_snapshot "
                    + "WHERE tenant_id=? AND recalculation_key=?", tenantId, "event:" + eventId);
            if (count == 1L) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(count).as("Expected tier metric snapshot for event %s", eventId).isEqualTo(1L);
    }

    private void awaitTierInbox(long tenantId, String eventId) throws InterruptedException {
        long count = 0;
        for (int i = 0; i < 900; i++) {
            count = queryLong("SELECT COUNT(*) FROM crm_inbox_event "
                    + "WHERE tenant_id=? AND consumer_group='crm-customer-tier-recalculation' AND event_id=?",
                    tenantId, eventId);
            if (count == 1L) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(count).as("Expected inbox ack for tenant=%d event=%s", tenantId, eventId).isEqualTo(1L);
    }

    private long awaitPendingDowngradeReview(long customerId) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM crm_customer_tier_downgrade_review "
                            + "WHERE tenant_id=1 AND customer_id=? AND status='PENDING_REVIEW' ORDER BY id DESC",
                    Long.class, customerId);
            if (!ids.isEmpty()) {
                return ids.getFirst();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Missing PENDING_REVIEW downgrade review for customer " + customerId);
    }
}
