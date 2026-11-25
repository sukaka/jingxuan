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
import java.time.YearMonth;
import java.time.ZoneId;
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
    private AuthCookies manager2;
    private AuthCookies lead1;
    private AuthCookies lead2;
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
        manager2 = login("manager2");
        lead1 = login("lead1");
        lead2 = login("lead2");
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

    @Test
    void c01DirBSettledRedemptionsAcrossAllBatchStatusesBlockReversalRequest() throws Exception {
        long cleanFileId = insertCleanPaymentFile();
        String currentPeriod = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();

        for (String targetStatus : List.of("DRAFT", "CONFIRMED", "PARTIALLY_PAID", "PAID")) {
            PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
            EntitlementRow entitlement = singleEntitlement(paid.orderId());
            long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
            long appointmentVersion = toServiceDone(appointmentId);
            JsonNode posted = data(redemptionRequest(operator1, appointmentId, appointmentVersion,
                    List.of(new Line(entitlement.id(), 1)), "C01-" + targetStatus, key("c01-post")));
            long redemptionId = posted.at("/redemption/id").asLong();
            long redemptionVersion = posted.at("/redemption/version").asLong();

            long batchId = createMonthlyBatch(1L, currentPeriod, redemptionId);
            if (!"DRAFT".equals(targetStatus)) {
                data(finance1.withAuth(post("/api/v1/settlements/batches/" + batchId + "/verification"))
                        .header("Idempotency-Key", key("c01-verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "ruleVersion", "R1", "grossFen", 1000,
                                "commissionFen", 100, "netPayableFen", 900,
                                "verificationEvidence", "c01-proof"))));
            }
            if ("PARTIALLY_PAID".equals(targetStatus) || "PAID".equals(targetStatus)) {
                long payAmount = "PAID".equals(targetStatus) ? 900L : 400L;
                data(finance1.withAuth(post("/api/v1/settlements/batches/" + batchId + "/payments"))
                        .header("Idempotency-Key", key("c01-pay"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 1, "amountFen", payAmount,
                                "bankTradeNo", key("C01-BANK"), "paidAt", Instant.now().toString(),
                                "voucherFileId", cleanFileId, "evidenceRef", "c01-pay-proof"))));
            }
            assertThat(queryString("SELECT status FROM crm_settlement_batch WHERE id=?", batchId))
                    .isEqualTo(targetStatus);

            mockMvc.perform(reversalRequest(manager1, redemptionId, redemptionVersion,
                            "C01-BLOCK-" + targetStatus, key("c01-req-block")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"));
            assertThat(queryInt("SELECT COUNT(*) FROM crm_redemption_reversal WHERE redemption_id=?", redemptionId))
                    .isZero();
        }
    }

    @Test
    void c01DirBPreExistingRequestedReversalBlockedOnApproveButAllowedOnReject() throws Exception {
        String currentPeriod = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();

        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
        long appointmentVersion = toServiceDone(appointmentId);
        JsonNode posted = data(redemptionRequest(operator1, appointmentId, appointmentVersion,
                List.of(new Line(entitlement.id(), 1)), "C01-PRE-REV", key("c01-pre-post")));
        long redemptionId = posted.at("/redemption/id").asLong();
        long redemptionVersion = posted.at("/redemption/version").asLong();

        JsonNode requested = data(reversalRequest(manager1, redemptionId, redemptionVersion,
                "C01-PRE-REQUEST", key("c01-pre-req")));
        long reversalId = requested.at("/reversal/id").asLong();
        long reversalVersion = requested.at("/reversal/version").asLong();

        jdbcTemplate.update("INSERT INTO crm_settlement_batch (tenant_id,institution_id,settlement_type,period_key,"
                + "status,amount_status) VALUES (1,1,'MONTHLY',?,'DRAFT','PENDING_VERIFICATION')", currentPeriod);
        long batchId = queryLong("SELECT MAX(id) FROM crm_settlement_batch WHERE tenant_id=1 AND institution_id=1 "
                + "AND settlement_type='MONTHLY' AND period_key=?", currentPeriod);
        jdbcTemplate.update("INSERT INTO crm_settlement_line (tenant_id,settlement_batch_id,redemption_id,rate_snapshot) "
                + "VALUES (1,?,?, '{}')", batchId, redemptionId);

        mockMvc.perform(reviewRequest(finance1, reversalId, "approve", reversalVersion,
                        "C01-APPROVE-BLOCK", key("c01-app-block")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(availableCount(entitlement.id())).isEqualTo(2);
        assertThat(correctionCount(entitlement.id())).isZero();

        JsonNode rejected = data(reviewRequest(finance1, reversalId, "reject", reversalVersion,
                "C01-REJECT-ALLOW", key("c01-rej-allow")));
        assertThat(rejected.at("/reversal/status").asText()).isEqualTo("REJECTED");
        assertThat(queryString("SELECT active_redemption_id FROM crm_redemption_reversal WHERE id=?", reversalId))
                .isNull();
    }

    @Test
    void c01ConcurrentSettlementAndReversalRequestNeverCoexist() throws Exception {
        String currentPeriod = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
        long appointmentVersion = toServiceDone(appointmentId);
        JsonNode posted = data(redemptionRequest(operator1, appointmentId, appointmentVersion,
                List.of(new Line(entitlement.id(), 1)), "C01-RACE", key("c01-race-post")));
        long redemptionId = posted.at("/redemption/id").asLong();
        long redemptionVersion = posted.at("/redemption/version").asLong();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> settleFuture = executor.submit(() -> {
                start.await();
                return mockMvc.perform(finance1.withAuth(post("/api/v1/settlements/batches"))
                                .header("Idempotency-Key", key("c01-race-settle"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("institutionId", 1, "settlementType", "MONTHLY",
                                        "periodKey", currentPeriod, "redemptionIds", List.of(redemptionId)))))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> reverseFuture = executor.submit(() -> {
                start.await();
                return mockMvc.perform(reversalRequest(manager1, redemptionId, redemptionVersion,
                                "C01-RACE-REV", key("c01-race-rev")))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int settleStatus = settleFuture.get(30, TimeUnit.SECONDS);
            int reverseStatus = reverseFuture.get(30, TimeUnit.SECONDS);
            assertThat((settleStatus == 201 && reverseStatus == 409)
                    || (settleStatus == 409 && reverseStatus == 200))
                    .as("Expected exactly one winner (settle=201 or reverse=200) and one 409 conflict, got settle=%s reverse=%s",
                            settleStatus, reverseStatus)
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
        int lineCount = queryInt("SELECT COUNT(*) FROM crm_settlement_line WHERE tenant_id=1 AND redemption_id=?", redemptionId);
        int activeRevCount = queryInt("SELECT COUNT(*) FROM crm_redemption_reversal WHERE tenant_id=1 AND active_redemption_id=?", redemptionId);
        assertThat(lineCount + activeRevCount).isEqualTo(1);
    }

    @Test
    void hBiz04MultiOrderRedemptionAndReversalUpdateOrdersWithoutDeadlock() throws Exception {
        long customerId = createCustomer();
        PaidOrder orderA = paidOrderForCustomer(customerId, SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        PaidOrder orderB = paidOrderForCustomer(customerId, SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        assertThat(orderA.orderId()).isLessThan(orderB.orderId());
        EntitlementRow entA = singleEntitlement(orderA.orderId());
        EntitlementRow entB = singleEntitlement(orderB.orderId());

        long appointmentId = newAppointment(customerId, List.of(
                new Line(entB.id(), 3), new Line(entA.id(), 3)));
        long version = toServiceDone(appointmentId);
        JsonNode posted = data(redemptionRequest(operator1, appointmentId, version,
                List.of(new Line(entB.id(), 3), new Line(entA.id(), 3)), "MULTI-ORDER-POST", key("mo-post")));
        long redemptionId = posted.at("/redemption/id").asLong();
        long redemptionVersion = posted.at("/redemption/version").asLong();

        assertOrderStatus(orderA.orderId(), "FULFILLED");
        assertOrderStatus(orderB.orderId(), "FULFILLED");
        assertThat(availableCount(entA.id())).isZero();
        assertThat(availableCount(entB.id())).isZero();

        JsonNode req = data(reversalRequest(manager1, redemptionId, redemptionVersion,
                "MULTI-ORDER-REV", key("mo-rev")));
        long reversalId = req.at("/reversal/id").asLong();
        long reversalVersion = req.at("/reversal/version").asLong();
        data(reviewRequest(finance1, reversalId, "approve", reversalVersion,
                "MULTI-ORDER-APP", key("mo-app")));

        assertOrderStatus(orderA.orderId(), "PAID");
        assertOrderStatus(orderB.orderId(), "PAID");
        assertThat(availableCount(entA.id())).isEqualTo(3);
        assertThat(availableCount(entB.id())).isEqualTo(3);
    }

    @Test
    void hBiz04ConcurrentRedemptionAndManualRefundNeverDeadlock() throws Exception {
        long cleanFileId = insertCleanPaymentFile();
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 3)));
        long appointmentVersion = toServiceDone(appointmentId);

        JsonNode refundCreated = data(manager1.withAuth(post("/api/v1/refunds"))
                .header("Idempotency-Key", key("race-rf-create"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("orderId", paid.orderId(), "requestedFen", 60000L,
                        "reasonCode", "CUSTOMER_REQUEST", "reasonDetail", "race",
                        "items", List.of(Map.of("entitlementId", entitlement.id(),
                                "revokeCount", 3, "allocatedFen", 60000L))))));
        long refundId = refundCreated.at("/refund/id").asLong();
        data(finance1.withAuth(post("/api/v1/refunds/" + refundId + "/review"))
                .header("Idempotency-Key", key("race-rf-review"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0, "decision", "APPROVED",
                        "approvedFen", 60000L, "reason", "race-approve"))));
        String extNo = key("EXT-RF");
        data(finance1.withAuth(post("/api/v1/refunds/" + refundId + "/executions"))
                .header("Idempotency-Key", key("race-rf-exec"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "provider", "BANK_TRANSFER",
                        "externalRefundNo", extNo, "amountFen", 60000L, "evidenceRef", "race-exec"))));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> redeemFuture = executor.submit(() -> {
                start.await();
                return mockMvc.perform(redemptionRequest(operator1, appointmentId, appointmentVersion,
                                List.of(new Line(entitlement.id(), 3)), "RACE-REDEEM", key("race-redeem")))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> refundFuture = executor.submit(() -> {
                start.await();
                return mockMvc.perform(finance1.withAuth(post("/api/v1/refunds/" + refundId + "/success"))
                                .header("Idempotency-Key", key("race-rf-succ"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("externalRefundNo", extNo, "amountFen", 60000L,
                                        "succeededAt", Instant.now().minusSeconds(5).toString(),
                                        "evidenceRef", "race-proof", "voucherFileId", cleanFileId))))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int redeemStatus = redeemFuture.get(30, TimeUnit.SECONDS);
            int refundStatus = refundFuture.get(30, TimeUnit.SECONDS);
            assertThat(List.of(redeemStatus, refundStatus)).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void hSec09CustomerTransferAndSoftDeleteSynchronizeFulfillmentSearchAndTimeline() throws Exception {
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 1)));
        long appointmentVersion = toServiceDone(appointmentId);
        JsonNode posted = data(redemptionRequest(operator1, appointmentId, appointmentVersion,
                List.of(new Line(entitlement.id(), 1)), "HSEC09-POST", key("hsec09-post")));
        long redemptionId = posted.at("/redemption/id").asLong();
        long redemptionVersion = posted.at("/redemption/version").asLong();
        long reversalId = data(reversalRequest(manager1, redemptionId, redemptionVersion,
                "HSEC09-REV", key("hsec09-rev"))).at("/reversal/id").asLong();

        mockMvc.perform(get("/api/v1/appointments?customerId=" + paid.customerId()).cookie(manager1.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/appointments?customerId=" + paid.customerId()).cookie(manager2.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));

        jdbcTemplate.update("UPDATE crm_customer SET owner_staff_id=3, team_id=2 WHERE tenant_id=1 AND id=?",
                paid.customerId());

        for (AuthCookies oldViewer : List.of(manager1, lead1)) {
            mockMvc.perform(get("/api/v1/appointments?customerId=" + paid.customerId()).cookie(oldViewer.cookies()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
            mockMvc.perform(get("/api/v1/redemptions?customerId=" + paid.customerId()).cookie(oldViewer.cookies()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
            mockMvc.perform(get("/api/v1/redemption-reversals?customerId=" + paid.customerId())
                            .cookie(oldViewer.cookies()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
            mockMvc.perform(get("/api/v1/appointments/" + appointmentId).cookie(oldViewer.cookies()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/redemptions/" + redemptionId).cookie(oldViewer.cookies()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/redemption-reversals/" + reversalId).cookie(oldViewer.cookies()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/customers/" + paid.customerId() + "/timeline").cookie(oldViewer.cookies()))
                    .andExpect(status().isNotFound());
        }

        for (AuthCookies newViewer : List.of(manager2, lead2)) {
            mockMvc.perform(get("/api/v1/appointments?customerId=" + paid.customerId()).cookie(newViewer.cookies()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
            mockMvc.perform(get("/api/v1/redemptions?customerId=" + paid.customerId()).cookie(newViewer.cookies()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
            mockMvc.perform(get("/api/v1/redemption-reversals?customerId=" + paid.customerId())
                            .cookie(newViewer.cookies()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
            mockMvc.perform(get("/api/v1/appointments/" + appointmentId).cookie(newViewer.cookies()))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/redemptions/" + redemptionId).cookie(newViewer.cookies()))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/redemption-reversals/" + reversalId).cookie(newViewer.cookies()))
                    .andExpect(status().isOk());
            JsonNode timeline = data(get("/api/v1/customers/" + paid.customerId() + "/timeline")
                    .cookie(newViewer.cookies()));
            assertThat(timeline.findValues("type").stream().map(JsonNode::asText))
                    .contains("APPOINTMENT", "REDEMPTION");
        }

        jdbcTemplate.update("UPDATE crm_customer SET deleted=1 WHERE tenant_id=1 AND id=?", paid.customerId());
        for (AuthCookies employee : List.of(manager2, lead2, admin)) {
            mockMvc.perform(get("/api/v1/appointments?customerId=" + paid.customerId()).cookie(employee.cookies()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
            mockMvc.perform(get("/api/v1/redemptions?customerId=" + paid.customerId()).cookie(employee.cookies()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
            mockMvc.perform(get("/api/v1/redemption-reversals?customerId=" + paid.customerId())
                            .cookie(employee.cookies()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        }
        mockMvc.perform(get("/api/v1/appointments?customerId=" + paid.customerId()).cookie(operator1.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void mBe08AppointmentOptionsAndCreationWorkBeyond100ActiveInstitutions() throws Exception {
        List<Long> seededIds = new ArrayList<>();
        try {
            for (int i = 0; i < 105; i++) {
                String no = "IT-OVER100-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                jdbcTemplate.update("INSERT INTO crm_institution (tenant_id,institution_no,full_name,status,"
                        + "settlement_type,online_rate,offline_rate,platform_rate) "
                        + "VALUES (1,?,?,'ACTIVE','MONTHLY',0.18,0.12,0.15)", no, "超百机构-" + i);
                seededIds.add(queryLong("SELECT id FROM crm_institution WHERE tenant_id=1 AND institution_no=?", no));
            }
            long over100InstitutionId = seededIds.get(seededIds.size() - 1);

            String skuCode = "IT-OVER100-SKU-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            JsonNode sku = data(admin.withAuth(post("/api/v1/skus"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("skuCode", skuCode, "packageVersionId", 301L,
                            "specName", "超100机构规格", "salePriceFen", SINGLE_PROJECT_PRICE,
                            "institutionIds", List.of(over100InstitutionId)))));
            long skuId = sku.at("/id").asLong();
            mockMvc.perform(admin.withAuth(post("/api/v1/skus/" + skuId + "/activate"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                    .andExpect(status().isOk());

            PaidOrder paid = paidOrder(skuId, SINGLE_PROJECT_PRICE);
            EntitlementRow entitlement = singleEntitlement(paid.orderId());
            JsonNode options = data(manager1.withAuth(get("/api/v1/appointment-options?customerId=" + paid.customerId())));
            assertThat(options.at("/institutions").findValues("id").stream().map(JsonNode::asLong))
                    .contains(over100InstitutionId);

            JsonNode created = data(manager1.withAuth(post("/api/v1/appointments"))
                    .header("Idempotency-Key", key("over100-appt"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("customerId", paid.customerId(), "institutionId", over100InstitutionId,
                            "scheduledAt", scheduledAt, "remark", "over-100",
                            "items", lineMaps(List.of(new Line(entitlement.id(), 1)))))));
            assertThat(created.at("/appointment/institutionId").asLong()).isEqualTo(over100InstitutionId);
        } finally {
            for (Long id : seededIds) {
                jdbcTemplate.update("UPDATE crm_institution SET status='INACTIVE' WHERE tenant_id=1 AND id=?", id);
            }
        }
    }

    @Test
    void concurrentRedemptionsOnSeparateEntitlementsOfSameOrderSetStatusFulfilled() throws Exception {
        for (int round = 0; round < 5; round++) {
            PaidOrder paid = paidOrder(multiProjectSku, MULTI_PROJECT_PRICE);
            List<EntitlementRow> rows = entitlements(paid.orderId());
            EntitlementRow e1 = rows.get(0);
            EntitlementRow e2 = rows.get(1);
            long appt1 = newAppointment(paid.customerId(), List.of(new Line(e1.id(), e1.grantedCount())));
            long appt2 = newAppointment(paid.customerId(), List.of(new Line(e2.id(), e2.grantedCount())));
            long v1 = toServiceDone(appt1);
            long v2 = toServiceDone(appt2);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<Integer> f1 = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(redemptionRequest(operator1, appt1, v1,
                                    List.of(new Line(e1.id(), e1.grantedCount())), "MVCC-A1", key("mvcc-a1")))
                            .andReturn().getResponse().getStatus();
                });
                Future<Integer> f2 = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(redemptionRequest(operator1, appt2, v2,
                                    List.of(new Line(e2.id(), e2.grantedCount())), "MVCC-A2", key("mvcc-a2")))
                            .andReturn().getResponse().getStatus();
                });
                start.countDown();
                assertThat(f1.get(30, TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(f2.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            } finally {
                executor.shutdownNow();
            }

            assertThat(availableCount(e1.id())).isZero();
            assertThat(availableCount(e2.id())).isZero();
            assertOrderStatus(paid.orderId(), "FULFILLED");
        }
    }

    @Test
    void concurrentReversalApprovalsOnSeparateEntitlementsOfSameOrderRestorePaidStatus() throws Exception {
        for (int round = 0; round < 5; round++) {
            PaidOrder paid = paidOrder(multiProjectSku, MULTI_PROJECT_PRICE);
            List<EntitlementRow> rows = entitlements(paid.orderId());
            EntitlementRow e1 = rows.get(0);
            EntitlementRow e2 = rows.get(1);
            long appt1 = newAppointment(paid.customerId(), List.of(new Line(e1.id(), e1.grantedCount())));
            long appt2 = newAppointment(paid.customerId(), List.of(new Line(e2.id(), e2.grantedCount())));
            long v1 = toServiceDone(appt1);
            long v2 = toServiceDone(appt2);
            JsonNode post1 = data(redemptionRequest(operator1, appt1, v1,
                    List.of(new Line(e1.id(), e1.grantedCount())), "MVCC-B-POST1", key("mvcc-bp1")));
            JsonNode post2 = data(redemptionRequest(operator1, appt2, v2,
                    List.of(new Line(e2.id(), e2.grantedCount())), "MVCC-B-POST2", key("mvcc-bp2")));
            assertOrderStatus(paid.orderId(), "FULFILLED");

            JsonNode req1 = data(reversalRequest(manager1, post1.at("/redemption/id").asLong(),
                    post1.at("/redemption/version").asLong(), "MVCC-B-REQ1", key("mvcc-br1")));
            JsonNode req2 = data(reversalRequest(manager1, post2.at("/redemption/id").asLong(),
                    post2.at("/redemption/version").asLong(), "MVCC-B-REQ2", key("mvcc-br2")));
            long rev1Id = req1.at("/reversal/id").asLong();
            long rev1Version = req1.at("/reversal/version").asLong();
            long rev2Id = req2.at("/reversal/id").asLong();
            long rev2Version = req2.at("/reversal/version").asLong();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<Integer> f1 = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(reviewRequest(finance1, rev1Id, "approve", rev1Version,
                                    "MVCC-B-APP1", key("mvcc-ba1")))
                            .andReturn().getResponse().getStatus();
                });
                Future<Integer> f2 = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(reviewRequest(finance1, rev2Id, "approve", rev2Version,
                                    "MVCC-B-APP2", key("mvcc-ba2")))
                            .andReturn().getResponse().getStatus();
                });
                start.countDown();
                assertThat(f1.get(30, TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(f2.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            } finally {
                executor.shutdownNow();
            }

            assertThat(availableCount(e1.id())).isEqualTo(e1.grantedCount());
            assertThat(availableCount(e2.id())).isEqualTo(e2.grantedCount());
            assertOrderStatus(paid.orderId(), "PAID");
        }
    }

    @Test
    void concurrentReversalApprovalAndRedemptionOnSameOrderKeepPartiallyRedeemedStatus() throws Exception {
        for (int round = 0; round < 5; round++) {
            PaidOrder paid = paidOrder(multiProjectSku, MULTI_PROJECT_PRICE);
            List<EntitlementRow> rows = entitlements(paid.orderId());
            EntitlementRow e1 = rows.get(0);
            EntitlementRow e2 = rows.get(1);
            long appt1 = newAppointment(paid.customerId(), List.of(new Line(e1.id(), e1.grantedCount())));
            long v1 = toServiceDone(appt1);
            JsonNode post1 = data(redemptionRequest(operator1, appt1, v1,
                    List.of(new Line(e1.id(), e1.grantedCount())), "MVCC-C-POST1", key("mvcc-cp1")));
            assertOrderStatus(paid.orderId(), "PARTIALLY_REDEEMED");

            JsonNode req1 = data(reversalRequest(manager1, post1.at("/redemption/id").asLong(),
                    post1.at("/redemption/version").asLong(), "MVCC-C-REQ1", key("mvcc-cr1")));
            long rev1Id = req1.at("/reversal/id").asLong();
            long rev1Version = req1.at("/reversal/version").asLong();

            long appt2 = newAppointment(paid.customerId(), List.of(new Line(e2.id(), e2.grantedCount())));
            long v2 = toServiceDone(appt2);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<Integer> f1 = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(reviewRequest(finance1, rev1Id, "approve", rev1Version,
                                    "MVCC-C-APP1", key("mvcc-ca1")))
                            .andReturn().getResponse().getStatus();
                });
                Future<Integer> f2 = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(redemptionRequest(operator1, appt2, v2,
                                    List.of(new Line(e2.id(), e2.grantedCount())), "MVCC-C-POST2", key("mvcc-cp2")))
                            .andReturn().getResponse().getStatus();
                });
                start.countDown();
                assertThat(f1.get(30, TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(f2.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            } finally {
                executor.shutdownNow();
            }

            assertThat(availableCount(e1.id())).isEqualTo(e1.grantedCount());
            assertThat(availableCount(e2.id())).isZero();
            assertOrderStatus(paid.orderId(), "PARTIALLY_REDEEMED");
        }
    }

    @Test
    void concurrentPartialRedemptionsOnSharedEntitlementsAcrossOrdersNeverFailWith409OrDeadlock() throws Exception {
        for (int round = 0; round < 5; round++) {
            long customerId = createCustomer();
            long orderAId = createSubmittedOrderForCustomer(customerId, SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
            long orderBId = createSubmittedOrderForCustomer(customerId, SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
            assertThat(orderAId).isLessThan(orderBId);
            confirmOrderPayment(orderBId, SINGLE_PROJECT_PRICE);
            confirmOrderPayment(orderAId, SINGLE_PROJECT_PRICE);
            EntitlementRow entA = singleEntitlement(orderAId);
            EntitlementRow entB = singleEntitlement(orderBId);
            assertThat(entB.id()).isLessThan(entA.id());

            long appt1 = newAppointment(customerId, List.of(new Line(entA.id(), 2), new Line(entB.id(), 2)));
            long appt2 = newAppointment(customerId, List.of(new Line(entB.id(), 1), new Line(entA.id(), 1)));
            long v1 = toServiceDone(appt1);
            long v2 = toServiceDone(appt2);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<Integer> f1 = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(redemptionRequest(operator1, appt1, v1,
                                    List.of(new Line(entA.id(), 2), new Line(entB.id(), 2)),
                                    "MVCC-D-1", key("mvcc-d1")))
                            .andReturn().getResponse().getStatus();
                });
                Future<Integer> f2 = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(redemptionRequest(operator1, appt2, v2,
                                    List.of(new Line(entB.id(), 1), new Line(entA.id(), 1)),
                                    "MVCC-D-2", key("mvcc-d2")))
                            .andReturn().getResponse().getStatus();
                });
                start.countDown();
                assertThat(f1.get(30, TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(f2.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            } finally {
                executor.shutdownNow();
            }

            assertThat(availableCount(entA.id())).isZero();
            assertThat(availableCount(entB.id())).isZero();
            assertOrderStatus(orderAId, "FULFILLED");
            assertOrderStatus(orderBId, "FULFILLED");
        }
    }

    @Test
    void milestoneTimestampsTrackTransitionsAndPreserveServiceDoneOnPartialRedemption() throws Exception {
        PaidOrder paid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow entitlement = singleEntitlement(paid.orderId());
        long appointmentId = newAppointment(paid.customerId(), List.of(new Line(entitlement.id(), 3)));

        JsonNode initial = data(operator1.withAuth(get("/api/v1/appointments/" + appointmentId)));
        assertThat(initial.at("/appointment/status").asText()).isEqualTo("PENDING_INSTITUTION");
        assertThat(initial.at("/appointment/confirmedAt").isNull()).isTrue();
        assertThat(initial.at("/appointment/checkedInAt").isNull()).isTrue();
        assertThat(initial.at("/appointment/serviceDoneAt").isNull()).isTrue();

        transition(appointmentId, "confirm", 0);
        JsonNode afterConfirm = data(operator1.withAuth(get("/api/v1/appointments/" + appointmentId)));
        String confirmedAt = afterConfirm.at("/appointment/confirmedAt").asText();
        assertThat(confirmedAt).isNotBlank();
        assertThat(afterConfirm.at("/appointment/checkedInAt").isNull()).isTrue();
        assertThat(afterConfirm.at("/appointment/serviceDoneAt").isNull()).isTrue();

        transition(appointmentId, "check-in", 1);
        JsonNode afterCheckIn = data(operator1.withAuth(get("/api/v1/appointments/" + appointmentId)));
        String checkedInAt = afterCheckIn.at("/appointment/checkedInAt").asText();
        assertThat(afterCheckIn.at("/appointment/confirmedAt").asText()).isEqualTo(confirmedAt);
        assertThat(checkedInAt).isNotBlank();
        assertThat(afterCheckIn.at("/appointment/serviceDoneAt").isNull()).isTrue();

        long versionAfterServiceDone = transition(appointmentId, "service-done", 2);
        serviceAt = Instant.now().toString();
        JsonNode afterServiceDone = data(operator1.withAuth(get("/api/v1/appointments/" + appointmentId)));
        String serviceDoneAt = afterServiceDone.at("/appointment/serviceDoneAt").asText();
        assertThat(afterServiceDone.at("/appointment/confirmedAt").asText()).isEqualTo(confirmedAt);
        assertThat(afterServiceDone.at("/appointment/checkedInAt").asText()).isEqualTo(checkedInAt);
        assertThat(serviceDoneAt).isNotBlank();

        // Partial redemption (1 of 3): SERVICE_DONE -> SERVICE_DONE must NOT overwrite service_done_at
        Thread.sleep(20);
        serviceAt = Instant.now().toString();
        data(redemptionRequest(operator1, appointmentId, versionAfterServiceDone,
                List.of(new Line(entitlement.id(), 1)), "MS-PARTIAL", key("ms-partial")));
        JsonNode afterPartial = data(operator1.withAuth(get("/api/v1/appointments/" + appointmentId)));
        assertThat(afterPartial.at("/appointment/status").asText()).isEqualTo("SERVICE_DONE");
        assertThat(afterPartial.at("/appointment/confirmedAt").asText()).isEqualTo(confirmedAt);
        assertThat(afterPartial.at("/appointment/checkedInAt").asText()).isEqualTo(checkedInAt);
        assertThat(afterPartial.at("/appointment/serviceDoneAt").asText()).isEqualTo(serviceDoneAt);

        // Final redemption (remaining 2 of 3): SERVICE_DONE -> REDEEMED preserves all 3 milestone timestamps
        long versionAfterPartial = afterPartial.at("/appointment/version").asLong();
        serviceAt = Instant.now().toString();
        data(redemptionRequest(operator1, appointmentId, versionAfterPartial,
                List.of(new Line(entitlement.id(), 2)), "MS-FINAL", key("ms-final")));
        JsonNode afterFinal = data(operator1.withAuth(get("/api/v1/appointments/" + appointmentId)));
        assertThat(afterFinal.at("/appointment/status").asText()).isEqualTo("REDEEMED");
        assertThat(afterFinal.at("/appointment/confirmedAt").asText()).isEqualTo(confirmedAt);
        assertThat(afterFinal.at("/appointment/checkedInAt").asText()).isEqualTo(checkedInAt);
        assertThat(afterFinal.at("/appointment/serviceDoneAt").asText()).isEqualTo(serviceDoneAt);

        // No-show branch: confirm -> no-show preserves confirmed_at and leaves checked_in_at & service_done_at NULL
        PaidOrder noShowPaid = paidOrder(SINGLE_PROJECT_SKU, SINGLE_PROJECT_PRICE);
        EntitlementRow noShowEntitlement = singleEntitlement(noShowPaid.orderId());
        long noShowAppt = newAppointment(noShowPaid.customerId(), List.of(new Line(noShowEntitlement.id(), 1)));
        transition(noShowAppt, "confirm", 0);
        transition(noShowAppt, "no-show", 1);
        JsonNode afterNoShow = data(operator1.withAuth(get("/api/v1/appointments/" + noShowAppt)));
        assertThat(afterNoShow.at("/appointment/status").asText()).isEqualTo("NO_SHOW");
        assertThat(afterNoShow.at("/appointment/confirmedAt").isTextual()).isTrue();
        assertThat(afterNoShow.at("/appointment/checkedInAt").isNull()).isTrue();
        assertThat(afterNoShow.at("/appointment/serviceDoneAt").isNull()).isTrue();
    }

    private long createMonthlyBatch(long institutionId, String periodKey, long redemptionId) throws Exception {
        return data(finance1.withAuth(post("/api/v1/settlements/batches"))
                        .header("Idempotency-Key", key("c01-batch"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", institutionId, "settlementType", "MONTHLY",
                                "periodKey", periodKey, "redemptionIds", List.of(redemptionId)))))
                .at("/batch/id").asLong();
    }

    private long insertCleanPaymentFile() {
        String objectKey = "sealed/1/it-fulfillment-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO crm_file_object (tenant_id,object_key,mime_type,size_bytes,sha256,"
                        + "classification,scan_status,uploaded_by) VALUES (1,?,'application/pdf',1,?,'PAYMENT','CLEAN',1)",
                objectKey, UUID.randomUUID().toString().replace("-", "").repeat(2));
        return queryLong("SELECT id FROM crm_file_object WHERE object_key=?", objectKey);
    }

    private PaidOrder paidOrderForCustomer(long customerId, long skuId, long payableFen) throws Exception {
        long orderId = createSubmittedOrderForCustomer(customerId, skuId, payableFen);
        confirmOrderPayment(orderId, payableFen);
        return new PaidOrder(customerId, orderId);
    }

    private long createSubmittedOrderForCustomer(long customerId, long skuId, long payableFen) throws Exception {
        Map<String, Object> orderBody = Map.of("customerId", customerId, "receiverType", "PLATFORM",
                "orderTypeCode", "REGULAR", "campaignId", 1, "campaignBatchId", 1,
                "payableFen", payableFen, "validTo", LocalDate.now(ZoneId.of("Asia/Shanghai")).plusYears(1).toString(),
                "items", List.of(Map.of("skuId", skuId, "quantity", 1)));
        JsonNode order = data(manager1.withAuth(post("/api/v1/orders"))
                .contentType(MediaType.APPLICATION_JSON).content(json(orderBody)));
        long orderId = order.at("/order/order/id").asLong();
        data(manager1.withAuth(post("/api/v1/orders/" + orderId + "/submit"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));
        return orderId;
    }

    private void confirmOrderPayment(long orderId, long payableFen) throws Exception {
        String tradeNo = key("payment");
        Map<String, Object> paymentBody = Map.of("externalTradeNo", tradeNo, "paymentChannel", "BANK_TRANSFER",
                "amountFen", payableFen, "paidAt", Instant.now().minusSeconds(120).toString(),
                "evidenceRef", "IT-P0-4-PAYMENT");
        data(admin.withAuth(post("/api/v1/orders/" + orderId + "/payment-confirmations"))
                .header("Idempotency-Key", tradeNo)
                .contentType(MediaType.APPLICATION_JSON).content(json(paymentBody)));
    }

    private PaidOrder paidOrder(long skuId, long payableFen) throws Exception {
        long customerId = createCustomer();
        Map<String, Object> orderBody = Map.of("customerId", customerId, "receiverType", "PLATFORM",
                "orderTypeCode", "REGULAR", "campaignId", 1, "campaignBatchId", 1,
                "payableFen", payableFen, "validTo", LocalDate.now(ZoneId.of("Asia/Shanghai")).plusYears(1).toString(),
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
        Map<String, Object> versionBody = Map.of("selectionMode", "FIXED", "choiceCount", 0,
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
