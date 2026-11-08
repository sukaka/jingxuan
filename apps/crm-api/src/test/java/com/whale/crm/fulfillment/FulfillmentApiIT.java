package com.whale.crm.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.ZoneOffset;
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
class FulfillmentApiIT extends AbstractMysqlIT {
    private static final long SINGLE_PROJECT_SKU = 301L;
    private static final long SINGLE_PROJECT_PRICE = 181100L;
    private static final long MULTI_PROJECT_PRICE = 1000L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthCookies admin;
    private AuthCookies manager1;
    private AuthCookies finance1;
    private AuthCookies frontdesk;
    private AuthCookies operator1;
    private AuthCookies operator2;
    private long multiProjectSku;
    private String scheduledAt;
    private String serviceAt;

    @BeforeAll
    void setUp() throws Exception {
        admin = login("admin");
        manager1 = login("manager1");
        finance1 = login("finance1");
        frontdesk = login("inst1");
        operator1 = login("operator1");
        operator2 = login("operator2");
        scheduledAt = Instant.now().plusSeconds(86400).toString();
        serviceAt = Instant.now().minusSeconds(60).toString();
        multiProjectSku = createTwoProjectSku();
    }

    @Test
    void v01TargetInstitutionCanReadAndOtherInstitutionGets404() throws Exception {
        assertOperatorMapping("operator1", 1);
        assertOperatorMapping("operator2", 2);
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));

        JsonNode detail = data(operator1.withAuth(get("/api/v1/appointments/" + appointmentId)));
        assertThat(detail.at("/appointment/id").asLong()).isEqualTo(appointmentId);
        mockMvc.perform(get("/api/v1/appointments/" + appointmentId).cookie(operator2.cookies()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void institutionAppointmentOptionsAreScopedAndCustomerModeStillWorks() throws Exception {
        JsonNode operatorProfile = data(operator1.withAuth(get("/api/v1/auth/me")));
        assertThat(operatorProfile.at("/permissions").toString()).doesNotContain("\"customer:read\"");
        PaidOrder paid = paidOrder(multiProjectSku, MULTI_PROJECT_PRICE);
        List<EntitlementRow> entitlements = entitlements(paid.orderId());
        EntitlementRow water = entitlements.get(0);
        EntitlementRow photon = entitlements.get(1);
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(water.id(), 1)));
        String institutionPath = "/api/v1/appointment-options?appointmentId=" + appointmentId;

        JsonNode institutionOptions = data(operator1.withAuth(get(institutionPath)));
        assertThat(institutionOptions.at("/customerId").asLong()).isEqualTo(paid.customerId());
        assertThat(institutionOptions.at("/institutions")).hasSize(1);
        assertThat(institutionOptions.at("/institutions/0/id").asLong()).isEqualTo(1);
        assertThat(institutionOptions.at("/entitlements")).hasSize(1);
        assertThat(institutionOptions.at("/entitlements/0/id").asLong()).isEqualTo(water.id());
        assertThat(institutionOptions.at("/entitlements/0/projectId").asLong()).isEqualTo(101);
        assertThat(institutionOptions.at("/entitlements/0/availableCount").asInt()).isEqualTo(2);
        assertThat(institutionOptions.at("/entitlements/0/institutionIds")).hasSize(1);
        assertThat(institutionOptions.at("/entitlements/0/institutionIds/0").asLong()).isEqualTo(1);

        mockMvc.perform(get(institutionPath).cookie(operator2.cookies()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        int frontdeskStatus = mockMvc.perform(get(institutionPath).cookie(frontdesk.cookies()))
                .andReturn().getResponse().getStatus();
        assertThat(frontdeskStatus).isIn(403, 404);
        int managerInstitutionStatus = mockMvc.perform(get(institutionPath).cookie(manager1.cookies()))
                .andReturn().getResponse().getStatus();
        assertThat(managerInstitutionStatus).isIn(403, 404);

        JsonNode customerOptions = data(manager1.withAuth(get("/api/v1/appointment-options?customerId=" + paid.customerId())));
        assertThat(customerOptions.at("/customerId").asLong()).isEqualTo(paid.customerId());
        assertThat(customerOptions.at("/institutions")).hasSize(1);
        assertThat(customerOptions.at("/institutions/0/id").asLong()).isEqualTo(1);
        assertThat(customerOptions.at("/entitlements")).hasSize(2);
        assertThat(customerOptions.at("/entitlements").findValues("projectId").stream()
                .map(JsonNode::asLong).toList()).containsExactlyInAnyOrder(101L, 102L);
        assertThat(customerOptions.at("/entitlements").findValues("id").stream()
                .map(JsonNode::asLong).toList()).containsExactlyInAnyOrder(water.id(), photon.id());
    }

    @Test
    void v02ManagerAndFrontdeskCannotPostAndLedgerIsUnchanged() throws Exception {
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
        long version = toServiceDone(appointmentId);
        List<Line> lines = List.of(new Line(entitlement.id(), 1));
        int beforeBalance = availableCount(entitlement.id());

        mockMvc.perform(redemptionRequest(manager1, appointmentId, version, lines, "IT-V02-MANAGER", key("v02-manager")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(redemptionRequest(frontdesk, appointmentId, version, lines, "IT-V02-FRONTDESK", key("v02-frontdesk")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(availableCount(entitlement.id())).isEqualTo(beforeBalance);
        assertThat(ledgerCount(entitlement.id(), "REDEEM")).isZero();
        assertThat(redemptionCount(appointmentId)).isZero();
    }

    @Test
    void v03SameKeyReturnsSamePostedAndDifferentRequestIs409() throws Exception {
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
        long version = toServiceDone(appointmentId);
        List<Line> lines = List.of(new Line(entitlement.id(), 1));
        String postKey = key("v03-post");

        JsonNode first = data(redemptionRequest(operator1, appointmentId, version, lines, "IT-V03-PROOF", postKey));
        JsonNode replay = data(redemptionRequest(operator1, appointmentId, version, lines, "IT-V03-PROOF", postKey));
        long redemptionId = first.at("/redemption/id").asLong();
        assertThat(redemptionId).isPositive();
        assertThat(replay.at("/redemption/id").asLong()).isEqualTo(redemptionId);
        assertThat(replay.at("/redemption/status").asText()).isEqualTo("POSTED");
        assertThat(redemptionCount(appointmentId)).isEqualTo(1);
        assertThat(ledgerCount(entitlement.id(), "REDEEM")).isEqualTo(1);
        assertThat(availableCount(entitlement.id())).isEqualTo(2);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?",
                "redemption.posted.v1:" + redemptionId)).isEqualTo(1);
        assertOrderStatus(paid.orderId(), "PARTIALLY_REDEEMED");
        List<OrderStatusTransition> history = fulfillmentHistory(paid.orderId());
        assertThat(history).hasSize(1);
        assertTransition(history.get(0), "PAID", "PARTIALLY_REDEEMED", "REDEMPTION_POSTED");
        long redemptionLineId = queryLong("SELECT id FROM crm_redemption_line WHERE redemption_id=?", redemptionId);
        List<JsonNode> entitlementEvents = outboxPayloads("entitlement.redeemed.v1", entitlement.id());
        assertThat(entitlementEvents).hasSize(1);
        assertThat(entitlementEvents.get(0).at("/entitlementId").asLong()).isEqualTo(entitlement.id());
        assertThat(entitlementEvents.get(0).at("/count").asInt()).isEqualTo(1);
        assertThat(entitlementEvents.get(0).at("/sourceRef").asText()).isEqualTo(Long.toString(redemptionLineId));

        mockMvc.perform(redemptionRequest(operator1, appointmentId, version, lines, "IT-V03-DIFFERENT", postKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(redemptionCount(appointmentId)).isEqualTo(1);
        assertThat(ledgerCount(entitlement.id(), "REDEEM")).isEqualTo(1);
        assertThat(availableCount(entitlement.id())).isEqualTo(2);
        assertThat(fulfillmentHistory(paid.orderId())).hasSize(1);
        assertThat(outboxPayloads("entitlement.redeemed.v1", entitlement.id())).hasSize(1);
    }

    @Test
    void v04CannotPostBeforeCheckIn() throws Exception {
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
        long confirmedVersion = transition(appointmentId, "confirm", 0);
        List<Line> lines = List.of(new Line(entitlement.id(), 1));

        mockMvc.perform(redemptionRequest(operator1, appointmentId, confirmedVersion, lines, "IT-V04-EARLY", key("v04-early")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(redemptionCount(appointmentId)).isZero();
        assertThat(ledgerCount(entitlement.id(), "REDEEM")).isZero();
        assertThat(availableCount(entitlement.id())).isEqualTo(3);
        assertThat(queryString("SELECT status FROM crm_appointment WHERE id=?", appointmentId)).isEqualTo("CONFIRMED");
    }

    @Test
    void v05ReversalRestoresOnlyAfterIndependentApprovalAndOnlyOnce() throws Exception {
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
        long appointmentVersion = toServiceDone(appointmentId);
        List<Line> lines = List.of(new Line(entitlement.id(), 1));
        JsonNode posted = data(redemptionRequest(operator1, appointmentId, appointmentVersion, lines,
                "IT-V05-POSTED", key("v05-post")));
        long redemptionId = posted.at("/redemption/id").asLong();
        long redemptionVersion = posted.at("/redemption/version").asLong();
        assertThat(availableCount(entitlement.id())).isEqualTo(2);
        assertOrderStatus(paid.orderId(), "PARTIALLY_REDEEMED");
        List<OrderStatusTransition> historyBeforeReversal = fulfillmentHistory(paid.orderId());
        assertThat(historyBeforeReversal).hasSize(1);
        assertTransition(historyBeforeReversal.get(0), "PAID", "PARTIALLY_REDEEMED", "REDEMPTION_POSTED");

        String requestKey = key("v05-request");
        JsonNode requested = data(reversalRequest(manager1, redemptionId, redemptionVersion,
                "IT-V05-REQUEST", requestKey));
        long reversalId = requested.at("/reversal/id").asLong();
        long reversalVersion = requested.at("/reversal/version").asLong();
        assertThat(requested.at("/reversal/status").asText()).isEqualTo("REQUESTED");
        assertThat(requested.at("/reversal/requestedBy").asLong()).isEqualTo(2);
        assertThat(correctionCount(entitlement.id())).isZero();
        assertThat(availableCount(entitlement.id())).isEqualTo(2);
        assertOrderStatus(paid.orderId(), "PARTIALLY_REDEEMED");
        assertThat(fulfillmentHistory(paid.orderId())).hasSize(1);

        JsonNode requestReplay = data(reversalRequest(manager1, redemptionId, redemptionVersion,
                "IT-V05-REQUEST", requestKey));
        assertThat(requestReplay.at("/reversal/id").asLong()).isEqualTo(reversalId);
        mockMvc.perform(reversalRequest(manager1, redemptionId, redemptionVersion, "IT-V05-DIFFERENT", requestKey))
                .andExpect(status().isConflict());
        mockMvc.perform(reversalRequest(manager1, redemptionId, redemptionVersion, "IT-V05-REQUEST", key("v05-active")))
                .andExpect(status().isConflict());
        assertThat(correctionCount(entitlement.id())).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_reversal WHERE redemption_id=?", redemptionId)).isEqualTo(1);

        String reviewKey = key("v05-review");
        JsonNode approved = data(reviewRequest(finance1, reversalId, "approve", reversalVersion,
                "IT-V05-APPROVE", reviewKey));
        assertThat(approved.at("/reversal/status").asText()).isEqualTo("APPROVED");
        assertThat(approved.at("/reversal/reviewedBy").asLong()).isEqualTo(5);
        assertThat(approved.at("/reversal/reviewedBy").asLong()).isNotEqualTo(requested.at("/reversal/requestedBy").asLong());
        assertThat(approved.at("/corrections")).hasSize(1);
        assertThat(approved.at("/corrections/0/txnType").asText()).isEqualTo("CORRECTION_IN");
        assertThat(approved.at("/corrections/0/deltaCount").asInt()).isEqualTo(1);
        assertThat(approved.at("/corrections/0/entitlementId").asLong()).isEqualTo(entitlement.id());
        assertThat(queryString("SELECT status FROM crm_redemption WHERE id=?", redemptionId)).isEqualTo("POSTED");
        assertThat(queryInt("SELECT version FROM crm_redemption WHERE id=?", redemptionId)).isZero();
        assertThat(ledgerCount(entitlement.id(), "REDEEM")).isEqualTo(1);
        assertThat(correctionCount(entitlement.id())).isEqualTo(1);
        assertThat(queryInt("SELECT COALESCE(SUM(delta_count),0) FROM crm_entitlement_ledger "
                + "WHERE entitlement_id=? AND txn_type='CORRECTION_IN' AND source_type='REDEMPTION_REVERSAL'", entitlement.id()))
                .isEqualTo(1);
        assertThat(availableCount(entitlement.id())).isEqualTo(3);
        assertThat(ledgerSum(entitlement.id())).isEqualTo(3);
        assertOrderStatus(paid.orderId(), "PAID");
        List<OrderStatusTransition> historyAfterApproval = fulfillmentHistory(paid.orderId());
        assertThat(historyAfterApproval).hasSize(2);
        assertTransition(historyAfterApproval.get(0), "PAID", "PARTIALLY_REDEEMED", "REDEMPTION_POSTED");
        assertTransition(historyAfterApproval.get(1), "PARTIALLY_REDEEMED", "PAID", "REDEMPTION_REVERSED");
        assertThat(historyAfterApproval.get(1).triggerRef()).isEqualTo(Long.toString(reversalId));
        long correctionLedgerId = queryLong("SELECT id FROM crm_entitlement_ledger WHERE entitlement_id=? "
                + "AND txn_type='CORRECTION_IN' AND source_type='REDEMPTION_REVERSAL' AND source_ref=?",
                entitlement.id(), Long.toString(reversalId));
        JsonNode approvedPayload = outboxPayload("redemption.reversal.approved.v1:" + reversalId);
        assertThat(approvedPayload.at("/corrections")).hasSize(1);
        JsonNode eventCorrection = approvedPayload.at("/corrections/0");
        assertThat(eventCorrection.at("/ledgerId").asLong()).isEqualTo(correctionLedgerId);
        assertThat(eventCorrection.at("/entitlementId").asLong()).isEqualTo(entitlement.id());
        assertThat(eventCorrection.at("/projectId").asLong()).isEqualTo(101);
        assertThat(eventCorrection.at("/deltaCount").asInt()).isEqualTo(1);
        assertThat(eventCorrection.at("/sourceRef").asText()).isEqualTo(Long.toString(reversalId));

        JsonNode reviewReplay = data(reviewRequest(finance1, reversalId, "approve", reversalVersion,
                "IT-V05-APPROVE", reviewKey));
        assertThat(reviewReplay.at("/reversal/id").asLong()).isEqualTo(reversalId);
        JsonNode approvedRequestReplay = data(reversalRequest(manager1, redemptionId, redemptionVersion,
                "IT-V05-REQUEST", requestKey));
        assertThat(approvedRequestReplay.at("/reversal/id").asLong()).isEqualTo(reversalId);
        mockMvc.perform(reviewRequest(finance1, reversalId, "approve", reversalVersion + 1,
                "IT-V05-REVIEW-AGAIN", key("v05-review-again")))
                .andExpect(status().isConflict());
        mockMvc.perform(reversalRequest(manager1, redemptionId, redemptionVersion,
                "IT-V05-REQUEST", key("v05-request-again")))
                .andExpect(status().isConflict());

        assertThat(correctionCount(entitlement.id())).isEqualTo(1);
        assertThat(availableCount(entitlement.id())).isEqualTo(3);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_reversal WHERE redemption_id=?", redemptionId)).isEqualTo(1);
        assertThat(queryString("SELECT status FROM crm_redemption WHERE id=?", redemptionId)).isEqualTo("POSTED");
        assertOrderStatus(paid.orderId(), "PAID");
        assertThat(fulfillmentHistory(paid.orderId())).hasSize(2);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?",
                "redemption.reversal.approved.v1:" + reversalId)).isEqualTo(1);
    }

    @Test
    void multiProjectRedemptionUsesSeparateBatchesAndCounts() throws Exception {
        PaidOrder paid = paidOrder(multiProjectSku, MULTI_PROJECT_PRICE);
        List<EntitlementRow> entitlements = entitlements(paid.orderId());
        assertThat(entitlements).hasSize(2);
        EntitlementRow water = entitlements.get(0);
        EntitlementRow photon = entitlements.get(1);
        assertThat(water.projectId()).isEqualTo(101);
        assertThat(water.grantedCount()).isEqualTo(2);
        assertThat(photon.projectId()).isEqualTo(102);
        assertThat(photon.grantedCount()).isEqualTo(3);
        assertThat(water.id()).isNotEqualTo(photon.id());

        long appointmentId = newAppointment(paid.customerId(), List.of(
                new Line(water.id(), 2), new Line(photon.id(), 2)));
        long appointmentVersion = toServiceDone(appointmentId);
        JsonNode posted = data(redemptionRequest(operator1, appointmentId, appointmentVersion,
                List.of(new Line(water.id(), 2), new Line(photon.id(), 1)), "IT-MULTI-PROOF", key("multi-post")));
        long redemptionId = posted.at("/redemption/id").asLong();

        assertThat(redeemedCountForProject(redemptionId, 101)).isEqualTo(2);
        assertThat(redeemedCountForProject(redemptionId, 102)).isEqualTo(1);
        assertThat(availableCount(water.id())).isZero();
        assertThat(availableCount(photon.id())).isEqualTo(2);
        assertThat(ledgerSum(water.id())).isZero();
        assertThat(ledgerSum(photon.id())).isEqualTo(2);
        assertThat(queryString("SELECT status FROM crm_appointment WHERE id=?", appointmentId)).isEqualTo("SERVICE_DONE");
        assertOrderStatus(paid.orderId(), "PARTIALLY_REDEEMED");
        List<OrderStatusTransition> history = fulfillmentHistory(paid.orderId());
        assertThat(history).hasSize(1);
        assertTransition(history.get(0), "PAID", "PARTIALLY_REDEEMED", "REDEMPTION_POSTED");
    }

    @Test
    void concurrentRedemptionsWithOneRemainingOnlyOneSucceeds() throws Exception {
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long initialAppointment = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 2)));
        long initialVersion = toServiceDone(initialAppointment);
        data(redemptionRequest(operator1, initialAppointment, initialVersion,
                List.of(new Line(entitlement.id(), 2)), "IT-CONCURRENT-INITIAL", key("concurrent-initial")));
        assertThat(availableCount(entitlement.id())).isEqualTo(1);

        long firstAppointment = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
        long secondAppointment = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
        long firstVersion = toServiceDone(firstAppointment);
        long secondVersion = toServiceDone(secondAppointment);
        List<Line> lines = List.of(new Line(entitlement.id(), 1));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> {
                start.await();
                return mockMvc.perform(redemptionRequest(operator1, firstAppointment, firstVersion, lines,
                        "IT-CONCURRENT-A", key("concurrent-a"))).andReturn().getResponse().getStatus();
            });
            Future<Integer> second = executor.submit(() -> {
                start.await();
                return mockMvc.perform(redemptionRequest(operator1, secondAppointment, secondVersion, lines,
                        "IT-CONCURRENT-B", key("concurrent-b"))).andReturn().getResponse().getStatus();
            });
            start.countDown();
            int firstStatus = first.get(30, TimeUnit.SECONDS);
            int secondStatus = second.get(30, TimeUnit.SECONDS);
            assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }

        assertThat(redemptionCount(firstAppointment) + redemptionCount(secondAppointment)).isEqualTo(1);
        assertThat(availableCount(entitlement.id())).isZero();
        assertThat(ledgerSum(entitlement.id())).isZero();
        assertThat(ledgerCount(entitlement.id(), "REDEEM")).isEqualTo(2);
    }

    @Test
    void orderStatusTracksFullAndPartialReversalWithIdempotentHistory() throws Exception {
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long firstAppointment = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 2)));
        long firstVersion = toServiceDone(firstAppointment);
        JsonNode firstPosted = data(redemptionRequest(operator1, firstAppointment, firstVersion,
                List.of(new Line(entitlement.id(), 2)), "IT-STATUS-PARTIAL", key("status-partial")));
        long firstRedemptionId = firstPosted.at("/redemption/id").asLong();
        assertThat(availableCount(entitlement.id())).isEqualTo(1);
        assertOrderStatus(paid.orderId(), "PARTIALLY_REDEEMED");

        long secondAppointment = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
        long secondVersion = toServiceDone(secondAppointment);
        String secondPostKey = key("status-full");
        List<Line> secondLines = List.of(new Line(entitlement.id(), 1));
        JsonNode secondPosted = data(redemptionRequest(operator1, secondAppointment, secondVersion,
                secondLines, "IT-STATUS-FULL", secondPostKey));
        long secondRedemptionId = secondPosted.at("/redemption/id").asLong();
        long secondRedemptionVersion = secondPosted.at("/redemption/version").asLong();
        assertThat(availableCount(entitlement.id())).isZero();
        assertOrderStatus(paid.orderId(), "FULFILLED");
        List<OrderStatusTransition> fullHistory = fulfillmentHistory(paid.orderId());
        assertThat(fullHistory).hasSize(2);
        assertTransition(fullHistory.get(0), "PAID", "PARTIALLY_REDEEMED", "REDEMPTION_POSTED");
        assertTransition(fullHistory.get(1), "PARTIALLY_REDEEMED", "FULFILLED", "REDEMPTION_POSTED");

        JsonNode postReplay = data(redemptionRequest(operator1, secondAppointment, secondVersion,
                secondLines, "IT-STATUS-FULL", secondPostKey));
        assertThat(postReplay.at("/redemption/id").asLong()).isEqualTo(secondRedemptionId);
        assertThat(fulfillmentHistory(paid.orderId())).hasSize(2);
        assertThat(outboxPayloads("entitlement.redeemed.v1", entitlement.id())).hasSize(2);
        assertThat(availableCount(entitlement.id())).isZero();

        String requestKey = key("status-reversal-request");
        JsonNode requested = data(reversalRequest(manager1, secondRedemptionId, secondRedemptionVersion,
                "IT-STATUS-PARTIAL-REVERSAL", requestKey));
        long reversalId = requested.at("/reversal/id").asLong();
        long reversalVersion = requested.at("/reversal/version").asLong();
        String reviewKey = key("status-reversal-review");
        JsonNode approved = data(reviewRequest(finance1, reversalId, "approve", reversalVersion,
                "IT-STATUS-PARTIAL-APPROVE", reviewKey));
        assertThat(approved.at("/reversal/status").asText()).isEqualTo("APPROVED");
        assertThat(approved.at("/corrections")).hasSize(1);
        assertThat(correctionCount(entitlement.id())).isEqualTo(1);
        assertThat(availableCount(entitlement.id())).isEqualTo(1);
        assertThat(ledgerSum(entitlement.id())).isEqualTo(1);
        assertOrderStatus(paid.orderId(), "PARTIALLY_REDEEMED");
        List<OrderStatusTransition> reversedHistory = fulfillmentHistory(paid.orderId());
        assertThat(reversedHistory).hasSize(3);
        assertTransition(reversedHistory.get(2), "FULFILLED", "PARTIALLY_REDEEMED", "REDEMPTION_REVERSED");
        assertThat(reversedHistory.get(2).triggerRef()).isEqualTo(Long.toString(reversalId));
        assertThat(queryString("SELECT status FROM crm_redemption WHERE id=?", firstRedemptionId)).isEqualTo("POSTED");
        assertThat(queryString("SELECT status FROM crm_redemption WHERE id=?", secondRedemptionId)).isEqualTo("POSTED");
        assertThat(ledgerCount(entitlement.id(), "REDEEM")).isEqualTo(2);

        JsonNode reviewReplay = data(reviewRequest(finance1, reversalId, "approve", reversalVersion,
                "IT-STATUS-PARTIAL-APPROVE", reviewKey));
        assertThat(reviewReplay.at("/reversal/id").asLong()).isEqualTo(reversalId);
        assertThat(fulfillmentHistory(paid.orderId())).hasSize(3);
        assertThat(correctionCount(entitlement.id())).isEqualTo(1);
        assertThat(availableCount(entitlement.id())).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE event_id=?",
                "redemption.reversal.approved.v1:" + reversalId)).isEqualTo(1);
    }

    @Test
    void lateSecondLedgerFailureRollsBackEverything() throws Exception {
        PaidOrder paid = paidOrder(multiProjectSku, MULTI_PROJECT_PRICE);
        List<EntitlementRow> entitlements = entitlements(paid.orderId());
        EntitlementRow first = entitlements.get(0);
        EntitlementRow second = entitlements.get(1);
        long appointmentId = newAppointment(paid.customerId(), List.of(
                new Line(first.id(), 2), new Line(second.id(), 1)));
        long appointmentVersion = toServiceDone(appointmentId);
        List<Line> lines = List.of(new Line(first.id(), 2), new Line(second.id(), 1));
        String postKey = key("late-failure");
        int outboxBefore = queryInt("SELECT COUNT(*) FROM crm_outbox_event");
        int historyBefore = queryInt("SELECT COUNT(*) FROM crm_appointment_history WHERE appointment_id=?", appointmentId);
        int auditBefore = queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE action_code='REDEMPTION_POST'");

        Long nextLineId = jdbcTemplate.queryForObject("SELECT AUTO_INCREMENT FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='crm_redemption_line'", Long.class);
        assertThat(nextLineId).isNotNull().isPositive();
        String conflictingLedgerKey = "redemption.post:" + (nextLineId + 1);
        jdbcTemplate.update("INSERT INTO crm_entitlement_ledger (tenant_id,entitlement_id,txn_type,delta_count,"
                        + "source_type,source_ref,idempotency_key) VALUES (1,?,'REDEEM',0,'REDEMPTION',?,?)",
                second.id(), "IT-LATE-MISMATCH", conflictingLedgerKey);
        try {
            mockMvc.perform(redemptionRequest(operator1, appointmentId, appointmentVersion, lines,
                            "IT-LATE-FAILURE", postKey))
                    .andExpect(status().isConflict());
        } finally {
            jdbcTemplate.update("DELETE FROM crm_entitlement_ledger WHERE tenant_id=1 AND idempotency_key=?", conflictingLedgerKey);
        }

        assertThat(redemptionCount(appointmentId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_line rl "
                + "JOIN crm_appointment_item ai ON ai.id=rl.appointment_item_id WHERE ai.appointment_id=?", appointmentId)).isZero();
        assertThat(ledgerCount(first.id(), "REDEEM")).isZero();
        assertThat(ledgerCount(second.id(), "REDEEM")).isZero();
        assertThat(availableCount(first.id())).isEqualTo(2);
        assertThat(availableCount(second.id())).isEqualTo(3);
        assertThat(ledgerSum(first.id())).isEqualTo(2);
        assertThat(ledgerSum(second.id())).isEqualTo(3);
        assertThat(queryString("SELECT status FROM crm_appointment WHERE id=?", appointmentId)).isEqualTo("SERVICE_DONE");
        assertThat(queryInt("SELECT version FROM crm_appointment WHERE id=?", appointmentId)).isEqualTo(appointmentVersion);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_appointment_history WHERE appointment_id=?", appointmentId))
                .isEqualTo(historyBefore);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event")).isEqualTo(outboxBefore);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE action_code='REDEMPTION_POST'")).isEqualTo(auditBefore);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE scope_code='redemption.post' "
                + "AND idempotency_key=?", postKey)).isZero();
        assertOrderStatus(paid.orderId(), "PAID");
        assertThat(fulfillmentHistory(paid.orderId())).isEmpty();
    }

    private PaidOrder paidOrder(long skuId, long payableFen) throws Exception {
        long customerId = createCustomer();
        Map<String, Object> orderBody = Map.of("customerId", customerId, "receiverType", "PLATFORM",
                "orderTypeCode", "REGULAR", "campaignId", 1, "campaignBatchId", 1,
                "payableFen", payableFen, "validTo", LocalDate.now(ZoneOffset.UTC).plusYears(1).toString(),
                "items", List.of(Map.of("skuId", skuId, "quantity", 1)));
        JsonNode order = data(manager1.withAuth(post("/api/v1/orders"))
                .contentType(MediaType.APPLICATION_JSON).content(json(orderBody)));
        long orderId = order.at("/order/order/id").asLong();
        data(manager1.withAuth(post("/api/v1/orders/" + orderId + "/submit"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
        String tradeNo = key("payment");
        Map<String, Object> paymentBody = Map.of("externalTradeNo", tradeNo, "paymentChannel", "BANK_TRANSFER",
                "amountFen", payableFen, "paidAt", Instant.now().minusSeconds(120).toString(),
                "evidenceRef", "IT-P0-4-PAYMENT");
        data(admin.withAuth(post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", tradeNo)
                .contentType(MediaType.APPLICATION_JSON).content(json(paymentBody)));
        return new PaidOrder(customerId, orderId);
    }

    private long createCustomer() throws Exception {
        Map<String, Object> body = Map.of("nickname", "IT-P0-4-" + UUID.randomUUID(), "ownerStaffId", 2);
        JsonNode created = data(manager1.withAuth(post("/api/v1/customers"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
        return created.at("/id").asLong();
    }

    private long createTwoProjectSku() throws Exception {
        Map<String, Object> versionBody = Map.of("selectionMode", "USE_N", "choiceCount", 2,
                "projects", List.of(
                        Map.of("projectId", 101, "grantCount", 2, "allocationPriceFen", 100),
                        Map.of("projectId", 102, "grantCount", 3, "allocationPriceFen", 200)));
        JsonNode version = data(admin.withAuth(post("/api/v1/packages/201/versions"))
                .contentType(MediaType.APPLICATION_JSON).content(json(versionBody)));
        long versionId = version.at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/package-versions/" + versionId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());

        String skuCode = "IT-P0-4-MULTI-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> skuBody = Map.of("skuCode", skuCode, "packageVersionId", versionId,
                "specName", "P0-4多项目集成", "salePriceFen", MULTI_PROJECT_PRICE, "institutionIds", List.of(1));
        JsonNode sku = data(admin.withAuth(post("/api/v1/skus"))
                .contentType(MediaType.APPLICATION_JSON).content(json(skuBody)));
        long skuId = sku.at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/skus/" + skuId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        return skuId;
    }

    private long newAppointment(long customerId, List<Line> lines) throws Exception {
        Map<String, Object> body = Map.of("customerId", customerId, "institutionId", 1,
                "scheduledAt", scheduledAt, "remark", "IT P0-4", "items", lineMaps(lines));
        JsonNode created = data(manager1.withAuth(post("/api/v1/appointments"))
                .header("Idempotency-Key", key("appointment"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
        return created.at("/appointment/id").asLong();
    }

    private long toServiceDone(long appointmentId) throws Exception {
        transition(appointmentId, "confirm", 0);
        transition(appointmentId, "check-in", 1);
        long version = transition(appointmentId, "service-done", 2);
        serviceAt = Instant.now().toString();
        return version;
    }

    private long transition(long appointmentId, String action, long version) throws Exception {
        Map<String, Object> body = Map.of("version", version, "reason", "IT-" + action);
        JsonNode detail = data(operator1.withAuth(post("/api/v1/appointments/" + appointmentId + "/" + action))
                .header("Idempotency-Key", key(action))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
        return detail.at("/appointment/version").asLong();
    }

    private MockHttpServletRequestBuilder redemptionRequest(AuthCookies actor, long appointmentId, long version,
                                                            List<Line> lines, String evidenceRef, String key) throws Exception {
        Map<String, Object> body = Map.of("appointmentVersion", version, "serviceAt", serviceAt,
                "evidenceRef", evidenceRef, "lines", lineMaps(lines));
        return actor.withAuth(post("/api/v1/appointments/" + appointmentId + "/redemptions"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private MockHttpServletRequestBuilder reversalRequest(AuthCookies actor, long redemptionId, long redemptionVersion,
                                                          String reason, String key) throws Exception {
        Map<String, Object> body = Map.of("redemptionVersion", redemptionVersion, "reason", reason);
        return actor.withAuth(post("/api/v1/redemptions/" + redemptionId + "/reversal-requests"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private MockHttpServletRequestBuilder reviewRequest(AuthCookies actor, long reversalId, String action,
                                                        long version, String reason, String key) throws Exception {
        Map<String, Object> body = Map.of("version", version, "reason", reason);
        return actor.withAuth(post("/api/v1/redemption-reversals/" + reversalId + "/" + action))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private List<Map<String, Object>> lineMaps(List<Line> lines) {
        return lines.stream().map(line -> Map.<String, Object>of(
                "entitlementId", line.entitlementId(), "count", line.count())).toList();
    }

    private EntitlementRow singleEntitlement(long orderId) {
        List<EntitlementRow> rows = entitlements(orderId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<EntitlementRow> entitlements(long orderId) {
        String sql = "SELECT e.id,e.project_id,e.granted_count,e.available_count FROM crm_entitlement e "
                + "JOIN crm_order_item oi ON oi.id=e.order_item_id WHERE oi.order_id=? ORDER BY e.project_id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new EntitlementRow(rs.getLong("id"),
                rs.getLong("project_id"), rs.getInt("granted_count"), rs.getInt("available_count")), orderId);
    }

    private void assertOperatorMapping(String username, long institutionId) {
        String role = jdbcTemplate.queryForObject("SELECT iu.institution_role FROM crm_institution_user iu "
                        + "JOIN sys_user u ON u.id=iu.user_id AND u.tenant_id=iu.tenant_id "
                        + "WHERE u.username=? AND iu.institution_id=? AND iu.status='ACTIVE'",
                String.class, username, institutionId);
        assertThat(role).isEqualTo("OPERATOR");
    }

    private int availableCount(long entitlementId) {
        return queryInt("SELECT available_count FROM crm_entitlement WHERE id=?", entitlementId);
    }

    private long ledgerSum(long entitlementId) {
        Long value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(delta_count),0) FROM crm_entitlement_ledger "
                + "WHERE entitlement_id=?", Long.class, entitlementId);
        return value == null ? 0 : value;
    }

    private int ledgerCount(long entitlementId, String txnType) {
        return queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? AND txn_type=?",
                entitlementId, txnType);
    }

    private int correctionCount(long entitlementId) {
        return queryInt("SELECT COUNT(*) FROM crm_entitlement_ledger WHERE entitlement_id=? "
                + "AND txn_type='CORRECTION_IN' AND source_type='REDEMPTION_REVERSAL'", entitlementId);
    }

    private int redemptionCount(long appointmentId) {
        return queryInt("SELECT COUNT(*) FROM crm_redemption WHERE appointment_id=?", appointmentId);
    }

    private int redeemedCountForProject(long redemptionId, long projectId) {
        return queryInt("SELECT COALESCE(SUM(count),0) FROM crm_redemption_line WHERE redemption_id=? AND project_id=?",
                redemptionId, projectId);
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }

    private void assertOrderStatus(long orderId, String expected) {
        assertThat(queryString("SELECT status FROM crm_order WHERE id=?", orderId)).isEqualTo(expected);
    }

    private List<OrderStatusTransition> fulfillmentHistory(long orderId) {
        String sql = "SELECT from_status,to_status,trigger_type,trigger_ref FROM crm_order_status_history "
                + "WHERE order_id=? AND trigger_type IN ('REDEMPTION_POSTED','REDEMPTION_REVERSED') ORDER BY id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OrderStatusTransition(rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("trigger_type"), rs.getString("trigger_ref")), orderId);
    }

    private void assertTransition(OrderStatusTransition actual, String fromStatus, String toStatus, String triggerType) {
        assertThat(actual.fromStatus()).isEqualTo(fromStatus);
        assertThat(actual.toStatus()).isEqualTo(toStatus);
        assertThat(actual.triggerType()).isEqualTo(triggerType);
    }

    private JsonNode outboxPayload(String eventId) throws Exception {
        return objectMapper.readTree(queryString("SELECT payload_json FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?", eventId));
    }

    private List<JsonNode> outboxPayloads(String eventType, long aggregateId) throws Exception {
        List<String> payloads = jdbcTemplate.queryForList("SELECT payload_json FROM crm_outbox_event "
                + "WHERE tenant_id=1 AND event_type=? AND aggregate_id=? ORDER BY id", String.class, eventType,
                Long.toString(aggregateId));
        List<JsonNode> events = new ArrayList<>();
        for (String payload : payloads) {
            events.add(objectMapper.readTree(payload));
        }
        return events;
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
        return "it-p04-" + prefix + "-" + UUID.randomUUID();
    }

    private record PaidOrder(long customerId, long orderId) {
    }

    private record EntitlementRow(long id, long projectId, int grantedCount, int availableCount) {
    }

    private record Line(long entitlementId, int count) {
    }

    private record OrderStatusTransition(String fromStatus, String toStatus, String triggerType, String triggerRef) {
    }
}
