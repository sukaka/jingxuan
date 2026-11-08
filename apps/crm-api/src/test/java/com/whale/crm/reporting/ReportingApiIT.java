package com.whale.crm.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportingApiIT extends AbstractMysqlIT {
    private static final String FROM = "2026-10-01";
    private static final String TO = "2026-10-31";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(jdbcTemplate);
    }

    @Test
    void operationsUsesOnlyRealFactsAndReturnsUtcMetadataAndPendingQ13() throws Exception {
        long customer = fixture.customer(1, 2, 1);
        Fixture.CatalogIds catalog = fixture.catalog("ITR-OPS", "水光卡", "3次", true);
        long platformOrder = fixture.order(1, customer, 2, "PARTIALLY_REDEEMED", "PLATFORM",
                10_000, "2026-10-02T03:00:00Z", "ITR_OPS");
        long platformItem = fixture.orderItem(1, platformOrder, catalog, 1);
        fixture.payment(1, platformOrder, "PLATFORM", "CONFIRMED", 10_000, "2026-10-02T03:00:00Z");
        fixture.payment(1, platformOrder, "PLATFORM", "PENDING", 777, "2026-10-02T04:00:00Z");
        long institutionOrder = fixture.order(1, customer, 2, "PAID", "INSTITUTION",
                5_000, "2026-10-03T03:00:00Z", "ITR_OPS");
        fixture.payment(1, institutionOrder, "INSTITUTION", "CONFIRMED", 5_000, "2026-10-03T03:00:00Z");
        long invalidDraft = fixture.order(1, customer, 2, "DRAFT", "PLATFORM",
                99_999, "2026-10-04T03:00:00Z", "ITR_OPS");
        fixture.payment(1, invalidDraft, "PLATFORM", "CONFIRMED", 99_999, "2026-10-04T03:00:00Z");
        fixture.refund(1, platformOrder, customer, "SUCCEEDED", "SUCCEEDED", 2_000, "2026-10-05T03:00:00Z");
        fixture.refund(1, institutionOrder, customer, "REQUESTED", "SUCCEEDED", 4_000, "2026-10-05T04:00:00Z");
        fixture.followup(1, customer, 2, "2026-10-01T00:30:00Z", false);
        fixture.followup(1, customer, 2, "2026-10-01T01:30:00Z", true);
        fixture.redemption(1, platformOrder, platformItem, catalog, customer, 2,
                "2026-10-06T03:00:00Z", 2);
        long outsideRange = fixture.order(1, customer, 2, "PAID", "PLATFORM",
                80_000, "2026-10-31T16:00:00Z", "ITR_OPS");
        fixture.payment(1, outsideRange, "PLATFORM", "CONFIRMED", 80_000, "2026-10-31T16:00:00Z");

        JsonNode data = data(withRange(get("/api/v1/reports/operations"), "ITR_OPS")
                .param("customerId", Long.toString(customer))
                .param("drillDownType", "PAYMENT")
                .param("drillDownLimit", "1")
                .cookie(login("admin").cookies()));

        assertThat(data.at("/from").asText()).isEqualTo(FROM);
        assertThat(data.at("/to").asText()).isEqualTo(TO);
        assertThat(data.at("/fromUtc").asText()).isEqualTo("2026-09-30T16:00:00Z");
        assertThat(data.at("/toUtcExclusive").asText()).isEqualTo("2026-10-31T16:00:00Z");
        assertThat(data.at("/timeZone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(data.at("/sourceSystem").asText()).isEqualTo("ITR_OPS");
        assertThat(data.at("/receiverType").asText()).isEqualTo("ALL");
        assertThat(data.at("/metricVersion").asText()).isEqualTo("P2_FACT_V1");
        assertThat(data.at("/sourceWatermark").asText()).isEqualTo("2026-10-06T03:00:00Z");
        assertThat(data.at("/metrics/contactCustomerCount").asLong()).isEqualTo(1);
        assertThat(data.at("/metrics/contactEventCount").asLong()).isEqualTo(1);
        assertThat(data.at("/metrics/paidOrderCount").asLong()).isEqualTo(2);
        assertThat(data.at("/metrics/paidFen").asLong()).isEqualTo(15_000);
        assertThat(data.at("/metrics/platformPaidFen").asLong()).isEqualTo(10_000);
        assertThat(data.at("/metrics/institutionPaidFen").asLong()).isEqualTo(5_000);
        assertThat(data.at("/metrics/refundFen").asLong()).isEqualTo(2_000);
        assertThat(data.at("/metrics/netPaidFen").asLong()).isEqualTo(13_000);
        assertThat(data.at("/metrics/confirmedAppointmentCount").asLong()).isEqualTo(1);
        assertThat(data.at("/metrics/checkedInCustomerCount").asLong()).isEqualTo(1);
        assertThat(data.at("/metrics/checkedInAppointmentCount").asLong()).isEqualTo(1);
        assertThat(data.at("/metrics/serviceCompletedCount").asLong()).isEqualTo(1);
        assertThat(data.at("/metrics/redemptionBatchCount").asLong()).isEqualTo(1);
        assertThat(data.at("/metrics/redeemedCustomerCount").asLong()).isEqualTo(1);
        assertThat(data.at("/metrics/redeemedCount").asLong()).isEqualTo(2);
        assertThat(data.at("/conversionRates/contactToPaid/value").isNull()).isTrue();
        assertThat(data.at("/conversionRates/contactToPaid/status").asText()).isEqualTo("PENDING_DEFINITION");
        assertThat(data.at("/conversionRates/contactToPaid/questionCode").asText()).isEqualTo("Q13");
        assertThat(data.at("/drillDown")).hasSize(1);
        assertThat(data.at("/drillDown/0/factType").asText()).isEqualTo("PAYMENT");
        assertThat(data.at("/drillDown/0/amountFen").asLong()).isEqualTo(5_000);

        JsonNode platformOnly = data(withRange(get("/api/v1/reports/operations"), "ITR_OPS")
                .param("customerId", Long.toString(customer)).param("receiverType", "PLATFORM")
                .cookie(login("admin").cookies()));
        assertThat(platformOnly.at("/receiverType").asText()).isEqualTo("PLATFORM");
        assertThat(platformOnly.at("/metrics/paidFen").asLong()).isEqualTo(10_000);
        assertThat(platformOnly.at("/metrics/institutionPaidFen").asLong()).isZero();
        assertThat(platformOnly.at("/metrics/refundFen").asLong()).isEqualTo(2_000);
        assertThat(platformOnly.at("/metrics/netPaidFen").asLong()).isEqualTo(8_000);
    }

    @Test
    void performanceUsesSaleSnapshotsAndSuccessfulRefundsIndependently() throws Exception {
        long customer = fixture.customer(1, 2, 1);
        long attributedChannel = fixture.channel(1, 6L);
        long unconfiguredChannel = fixture.channel(1, null);
        long managerOrder = fixture.order(1, customer, 3, "PAID", "PLATFORM",
                10_000, "2026-10-08T03:00:00Z", "ITR_PERF",
                new Fixture.SaleAttribution(null, null, attributedChannel, 6L, null, null, null, null));
        fixture.payment(1, managerOrder, "PLATFORM", "CONFIRMED", 10_000, "2026-10-08T03:00:00Z");
        fixture.refund(1, managerOrder, customer, "SUCCEEDED", "SUCCEEDED", 1_500, "2026-10-09T03:00:00Z");
        long noChannelOrder = fixture.order(1, customer, 2, "PAID", "INSTITUTION",
                4_000, "2026-10-08T04:00:00Z", "ITR_PERF",
                new Fixture.SaleAttribution(null, null, null, 6L, null, null, null, null));
        fixture.payment(1, noChannelOrder, "INSTITUTION", "CONFIRMED", 4_000, "2026-10-08T04:00:00Z");
        long unconfiguredOrder = fixture.order(1, customer, 2, "PAID", "PLATFORM",
                7_000, "2026-10-08T05:00:00Z", "ITR_PERF",
                new Fixture.SaleAttribution(null, null, unconfiguredChannel, null, null, null, null, null));
        fixture.payment(1, unconfiguredOrder, "PLATFORM", "CONFIRMED", 7_000, "2026-10-08T05:00:00Z");

        JsonNode manager = data(withRange(get("/api/v1/reports/performance"), "ITR_PERF")
                .param("performanceRole", "MANAGER").cookie(login("admin").cookies()));
        JsonNode ownerAtSale = row(manager, "userId", 3);
        assertThat(ownerAtSale.at("/paidFen").asLong()).isEqualTo(10_000);
        assertThat(ownerAtSale.at("/refundFen").asLong()).isEqualTo(1_500);
        assertThat(ownerAtSale.at("/netPaidFen").asLong()).isEqualTo(8_500);
        assertThat(ownerAtSale.at("/platformNetPaidFen").asLong()).isEqualTo(8_500);
        JsonNode currentOwner = row(manager, "userId", 2);
        assertThat(currentOwner.at("/paidFen").asLong()).isEqualTo(11_000);
        assertThat(currentOwner.at("/institutionPaidFen").asLong()).isEqualTo(4_000);
        assertThat(currentOwner.at("/platformPaidFen").asLong()).isEqualTo(7_000);
        assertThat(currentOwner.at("/targetFen").isNull()).isTrue();
        assertThat(currentOwner.at("/targetStatus").asText()).isEqualTo("UNCONFIGURED");
        assertThat(currentOwner.at("/commissionFen").isNull()).isTrue();
        assertThat(manager.at("/usageStatus").asText()).isEqualTo("NOT_PAYROLL");
        assertThat(manager.at("/performanceRole").asText()).isEqualTo("MANAGER");
        assertThat(manager.at("/sourceSystem").asText()).isEqualTo("ITR_PERF");
        assertThat(manager.at("/sourceWatermark").asText()).isEqualTo("2026-10-09T03:00:00Z");

        JsonNode firstPage = data(withRange(get("/api/v1/reports/performance"), "ITR_PERF")
                .param("performanceRole", "MANAGER").param("size", "1").cookie(login("admin").cookies()));
        assertThat(firstPage.at("/total").asLong()).isEqualTo(2);
        assertThat(firstPage.at("/items")).hasSize(1);
        assertThat(firstPage.at("/items/0/userId").asLong()).isEqualTo(2);

        JsonNode business = data(withRange(get("/api/v1/reports/performance"), "ITR_PERF")
                .param("performanceRole", "BUSINESS").cookie(login("admin").cookies()));
        JsonNode businessOwner = row(business, "userId", 6);
        assertThat(businessOwner.at("/netPaidFen").asLong()).isEqualTo(8_500);
        JsonNode unconfigured = row(business, "userId", null);
        assertThat(unconfigured.at("/displayName").asText()).isEqualTo("未配置");
        assertThat(unconfigured.at("/attributionStatus").asText()).isEqualTo("UNCONFIGURED");
        assertThat(unconfigured.at("/paidFen").asLong()).isEqualTo(7_000);
        assertThat(business.at("/totals/paidFen").asLong()).isEqualTo(17_000);

        grantManagerReportPermission();
        JsonNode self = data(withRange(get("/api/v1/reports/performance"), "ITR_PERF")
                .param("performanceRole", "MANAGER").cookie(login("manager1").cookies()));
        assertThat(rows(self, "userId", 3)).isEmpty();
        assertThat(row(self, "userId", 2).at("/paidFen").asLong()).isEqualTo(11_000);
    }

    @Test
    void operationsDimensionRowsUseHistoricalFulfillmentFacts() throws Exception {
        long firstStaffId = fixture.employee("报表管家甲", 1);
        long secondStaffId = fixture.employee("报表管家乙", 2);
        long firstCustomer = fixture.customer(1, firstStaffId, 1);
        Fixture.CatalogIds firstCatalog = fixture.catalog("ITR-DETAIL-1", "水光卡", "3次", true);
        long firstOrder = fixture.order(1, firstCustomer, firstStaffId, "PARTIALLY_REDEEMED", "PLATFORM",
                10_000, "2026-10-08T03:00:00Z", "ITR_OPS_DETAIL");
        long firstItem = fixture.orderItem(1, firstOrder, firstCatalog, 1);
        long secondItem = fixture.orderItem(1, firstOrder, firstCatalog, 1);
        fixture.payment(1, firstOrder, "PLATFORM", "CONFIRMED", 10_000, "2026-10-08T03:00:00Z");
        fixture.followup(1, firstCustomer, firstStaffId, "2026-10-08T03:00:00Z", false);
        fixture.redemption(1, firstOrder, firstItem, firstCatalog, firstCustomer, firstStaffId,
                "2026-10-08T03:00:00Z", 2);
        fixture.redemption(1, firstOrder, secondItem, firstCatalog, firstCustomer, firstStaffId,
                "2026-10-08T04:00:00Z", 1);

        long secondCustomer = fixture.customer(1, secondStaffId, 2);
        Fixture.CatalogIds secondCatalog = fixture.catalog("ITR-DETAIL-2", "光子卡", "6次", true);
        long secondOrder = fixture.order(1, secondCustomer, secondStaffId, "PARTIALLY_REDEEMED", "INSTITUTION",
                7_000, "2026-10-08T04:00:00Z", "ITR_OPS_DETAIL");
        long secondOrderItem = fixture.orderItem(1, secondOrder, secondCatalog, 1);
        fixture.payment(1, secondOrder, "INSTITUTION", "CONFIRMED", 7_000, "2026-10-08T04:00:00Z");
        fixture.followup(1, secondCustomer, secondStaffId, "2026-10-08T04:00:00Z", false);
        long verifiedRedemption = fixture.redemption(1, secondOrder, secondOrderItem, secondCatalog, secondCustomer,
                secondStaffId, "2026-10-08T05:00:00Z", 1);
        fixture.verifyRedemptionSettlement(secondCatalog.institutionId(), verifiedRedemption);

        JsonNode data = data(get("/api/v1/reports/operations")
                .param("from", "2026-10-08").param("to", "2026-10-08")
                .param("timeZone", "Asia/Shanghai").param("sourceSystem", "ITR_OPS_DETAIL")
                .cookie(login("admin").cookies()));

        assertThat(data.at("/metrics/serviceCompletedCount").asLong()).isEqualTo(3);
        assertThat(data.at("/metrics/redemptionBatchCount").asLong()).isEqualTo(3);
        assertThat(data.at("/metrics/redeemedCustomerCount").asLong()).isEqualTo(2);
        assertThat(data.at("/metrics/redeemedCount").asLong()).isEqualTo(4);
        assertThat(data.at("/staffRows"))
                .filteredOn(row -> java.util.Set.of(firstStaffId, secondStaffId).contains(row.at("/staffUserId").asLong()))
                .hasSize(2);
        assertThat(data.at("/institutionRows"))
                .filteredOn(row -> java.util.Set.of(firstCatalog.institutionId(), secondCatalog.institutionId())
                        .contains(row.at("/institutionId").asLong()))
                .hasSize(2);

        JsonNode firstStaff = arrayRow(data.at("/staffRows"), "staffUserId", firstStaffId);
        assertThat(firstStaff.at("/staffName").asText()).isEqualTo("报表管家甲");
        assertThat(firstStaff.at("/contactCustomerCount").asLong()).isEqualTo(1);
        assertThat(firstStaff.at("/contactEventCount").asLong()).isEqualTo(1);
        assertThat(firstStaff.at("/paidOrderCount").asLong()).isEqualTo(1);
        assertThat(firstStaff.at("/paidFen").asLong()).isEqualTo(10_000);
        assertThat(firstStaff.at("/confirmedAppointmentCount").asLong()).isEqualTo(2);
        assertThat(firstStaff.at("/serviceCompletedCount").asLong()).isEqualTo(2);
        assertThat(firstStaff.at("/checkedInCustomerCount").asLong()).isEqualTo(1);
        assertThat(firstStaff.at("/checkedInAppointmentCount").asLong()).isEqualTo(2);
        assertThat(firstStaff.at("/redemptionBatchCount").asLong()).isEqualTo(2);
        assertThat(firstStaff.at("/redeemedCustomerCount").asLong()).isEqualTo(1);
        assertThat(firstStaff.at("/redeemedCount").asLong()).isEqualTo(3);
        assertThat(firstStaff.at("/conversionRates/contactToPaid/value").isNull()).isTrue();
        assertThat(firstStaff.at("/conversionRates/contactToPaid/status").asText()).isEqualTo("PENDING_DEFINITION");
        JsonNode secondStaff = arrayRow(data.at("/staffRows"), "staffUserId", secondStaffId);
        assertThat(secondStaff.at("/staffName").asText()).isEqualTo("报表管家乙");
        assertThat(secondStaff.at("/paidFen").asLong()).isEqualTo(7_000);
        assertThat(secondStaff.at("/serviceCompletedCount").asLong()).isEqualTo(1);

        JsonNode firstInstitution = arrayRow(data.at("/institutionRows"), "institutionId", firstCatalog.institutionId());
        assertThat(firstInstitution.at("/institutionName").asText())
                .isEqualTo("报表测试机构" + firstCatalog.institutionId());
        assertThat(firstInstitution.at("/confirmedAppointmentCount").asLong()).isEqualTo(2);
        assertThat(firstInstitution.at("/serviceCompletedCount").asLong()).isEqualTo(2);
        assertThat(firstInstitution.at("/checkedInCustomerCount").asLong()).isEqualTo(1);
        assertThat(firstInstitution.at("/checkedInAppointmentCount").asLong()).isEqualTo(2);
        assertThat(firstInstitution.at("/redemptionBatchCount").asLong()).isEqualTo(2);
        assertThat(firstInstitution.at("/redeemedCustomerCount").asLong()).isEqualTo(1);
        assertThat(firstInstitution.at("/redeemedCount").asLong()).isEqualTo(3);
        assertThat(firstInstitution.at("/pendingReconciliationFen").isNull()).isTrue();
        assertThat(firstInstitution.at("/pendingReconciliationStatus").asText()).isEqualTo("PENDING_VERIFICATION");
        JsonNode secondInstitution = arrayRow(data.at("/institutionRows"), "institutionId", secondCatalog.institutionId());
        assertThat(secondInstitution.at("/pendingReconciliationFen").asLong()).isEqualTo(1_000);
        assertThat(secondInstitution.at("/pendingReconciliationStatus").asText()).isEqualTo("VERIFIED");
    }

    @Test
    void performanceDailyItemsUseRequestZoneAndApprovedTargetsUseFullResult() throws Exception {
        long customer = fixture.customer(1, 2, 1);
        long attributedChannel = fixture.channel(1, 6L);
        long unconfiguredChannel = fixture.channel(1, null);
        long attributedOrder = fixture.order(1, customer, 3, "PAID", "PLATFORM",
                10_000, "2026-10-08T03:00:00Z", "ITR_DAILY",
                new Fixture.SaleAttribution(null, null, attributedChannel, 6L, null, null, null, null));
        fixture.payment(1, attributedOrder, "PLATFORM", "CONFIRMED", 10_000, "2026-10-08T03:00:00Z");
        fixture.refund(1, attributedOrder, customer, "SUCCEEDED", "SUCCEEDED", 1_500, "2026-10-09T03:00:00Z");
        long institutionOrder = fixture.order(1, customer, 2, "PAID", "INSTITUTION",
                4_000, "2026-10-08T04:00:00Z", "ITR_DAILY");
        fixture.payment(1, institutionOrder, "INSTITUTION", "CONFIRMED", 4_000, "2026-10-08T04:00:00Z");
        long unconfiguredOrder = fixture.order(1, customer, 2, "PAID", "PLATFORM",
                7_000, "2026-10-08T05:00:00Z", "ITR_DAILY",
                new Fixture.SaleAttribution(null, null, unconfiguredChannel, null, null, null, null, null));
        fixture.payment(1, unconfiguredOrder, "PLATFORM", "CONFIRMED", 7_000, "2026-10-08T05:00:00Z");
        long nextLocalDayOrder = fixture.order(1, customer, 3, "PAID", "PLATFORM",
                2_000, "2026-10-09T16:30:00Z", "ITR_DAILY");
        fixture.payment(1, nextLocalDayOrder, "PLATFORM", "CONFIRMED", 2_000, "2026-10-09T16:30:00Z");

        JsonNode manager = data(withRange(get("/api/v1/reports/performance"), "ITR_DAILY")
                .param("performanceRole", "MANAGER").cookie(login("admin").cookies()));
        assertThat(manager.at("/dailyItems")).hasSize(3);
        assertDaily(manager.at("/dailyItems/0"), "2026-10-08", 3, 0, 21_000, 0, 21_000);
        assertDaily(manager.at("/dailyItems/1"), "2026-10-09", 0, 1, 0, 1_500, -1_500);
        assertDaily(manager.at("/dailyItems/2"), "2026-10-10", 1, 0, 2_000, 0, 2_000);
        assertTeamTargetUnconfigured(manager);

        fixture.approvedPerformanceTarget(2, "MANAGER", 10_000);
        JsonNode partialTargets = data(withRange(get("/api/v1/reports/performance"), "ITR_DAILY")
                .param("performanceRole", "MANAGER").cookie(login("admin").cookies()));
        assertTeamTargetUnconfigured(partialTargets);
        fixture.approvedPerformanceTarget(3, "MANAGER", 20_000);
        JsonNode approved = data(withRange(get("/api/v1/reports/performance"), "ITR_DAILY")
                .param("performanceRole", "MANAGER").param("size", "1").cookie(login("admin").cookies()));
        assertThat(approved.at("/items")).hasSize(1);
        assertThat(approved.at("/total").asLong()).isEqualTo(2);
        assertThat(approved.at("/dailyItems")).hasSize(3);
        assertThat(approved.at("/teamTargetFen").asLong()).isEqualTo(30_000);
        assertThat(approved.at("/teamTargetStatus").asText()).isEqualTo("APPROVED");
        assertThat(approved.at("/teamTargetCompletionRate").decimalValue()).isEqualByComparingTo("71.6667");
        assertThat(approved.at("/teamTargetCompletionStatus").asText()).isEqualTo("CALCULATED");

        JsonNode business = data(withRange(get("/api/v1/reports/performance"), "ITR_DAILY")
                .param("performanceRole", "BUSINESS").cookie(login("admin").cookies()));
        assertThat(business.at("/dailyItems")).hasSize(2);
        assertDaily(business.at("/dailyItems/0"), "2026-10-08", 2, 0, 17_000, 0, 17_000);
        assertDaily(business.at("/dailyItems/1"), "2026-10-09", 0, 1, 0, 1_500, -1_500);
        assertTeamTargetUnconfigured(business);
    }

    @Test
    void operationsDashboardReusesScopedFactsAndRejectsUnauthorizedAccess() throws Exception {
        long customer = fixture.customer(1, 2, 1);
        Fixture.CatalogIds catalog = fixture.catalog("ITR-DASH", "运营大屏卡", "3次", true);
        Fixture.CampaignIds campaign = fixture.campaign("ITR-DASH-1", "大屏活动");
        long order = fixture.order(1, customer, 2, "PARTIALLY_REDEEMED", "PLATFORM",
                10_000, "2026-10-18T03:00:00Z", "ITR_DASH",
                new Fixture.SaleAttribution(campaign.campaignId(), campaign.firstBatchId(), null, null,
                        "DASH-SNAPSHOT", "大屏成交活动", "DASH-BATCH", "大屏成交批次"));
        long item = fixture.orderItem(1, order, catalog, 1);
        fixture.payment(1, order, "PLATFORM", "CONFIRMED", 10_000, "2026-10-18T03:00:00Z");
        fixture.refund(1, order, customer, "SUCCEEDED", "SUCCEEDED", 1_000, "2026-10-19T04:00:00Z");
        fixture.redemption(1, order, item, catalog, customer, 2, "2026-10-20T03:00:00Z", 2);

        AuthCookies admin = login("admin");
        JsonNode data = data(withRange(get("/api/v1/reports/operations-dashboard"), "ITR_DASH")
                .param("customerId", Long.toString(customer)).cookie(admin.cookies()));

        assertThat(data.at("/metrics/paidFen").asLong()).isEqualTo(10_000);
        assertThat(data.at("/metrics/refundFen").asLong()).isEqualTo(1_000);
        assertThat(data.at("/metrics/netPaidFen").asLong()).isEqualTo(9_000);
        assertThat(data.at("/metrics/paidOrderCount").asLong()).isEqualTo(1);
        assertThat(data.at("/metrics/checkedInCustomerCount").asLong()).isEqualTo(1);
        assertThat(data.at("/metrics/redeemedCount").asLong()).isEqualTo(2);
        assertThat(data.at("/duty/newCustomerCount").asLong()).isZero();
        assertThat(data.at("/duty/pendingTaskCount").isNumber()).isTrue();
        assertThat(data.at("/duty/dueFollowupCount").isNumber()).isTrue();
        assertThat(data.at("/duty/pendingAppointmentCount").isNumber()).isTrue();
        assertThat(data.at("/duty/awaitingRedemptionCount").isNumber()).isTrue();
        assertThat(data.at("/duty/pendingRefundCount").isNumber()).isTrue();
        assertThat(data.at("/sourceWatermark").asText()).isEqualTo("2026-10-20T03:00:00Z");
        assertThat(data.at("/dailyItems")).hasSize(2);
        assertDaily(data.at("/dailyItems/0"), "2026-10-18", 1, 0, 10_000, 0, 10_000);
        assertDaily(data.at("/dailyItems/1"), "2026-10-19", 0, 1, 0, 1_000, -1_000);
        assertThat(data.at("/institutionRows/0/redeemedCount").asLong()).isEqualTo(2);
        assertThat(data.at("/campaignRows/0/campaignName").asText()).isEqualTo("大屏成交活动");
        assertThat(data.at("/campaignRows/0/netPaidFen").asLong()).isEqualTo(9_000);

        mockMvc.perform(withRange(get("/api/v1/reports/operations-dashboard"), "ITR_DASH"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(admin.withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/users"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_dashboard_norole\",\"displayName\":\"大屏无权限用户\","
                                + "\"password\":\"" + DEV_PASSWORD + "\",\"userType\":\"EMPLOYEE\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(withRange(get("/api/v1/reports/operations-dashboard"), "ITR_DASH")
                        .cookie(login("it_dashboard_norole").cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/reports/operations-dashboard").param("from", "2025-01-01")
                        .param("to", "2026-01-02").cookie(admin.cookies()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void operationsDashboardCountsServiceDoneAppointmentsAwaitingRedemption() throws Exception {
        long customer = fixture.customer(1, 2, 1);
        Fixture.CatalogIds catalog = fixture.catalog("ITR-DUTY", "待核销卡", "3次", true);
        fixture.serviceDoneAppointment(1, customer, catalog.institutionId(), 2, "2026-10-21T03:00:00Z");

        JsonNode data = data(withRange(get("/api/v1/reports/operations-dashboard"), "ITR_DUTY")
                .param("customerId", Long.toString(customer)).cookie(login("admin").cookies()));

        assertThat(data.at("/duty/awaitingRedemptionCount").asLong()).isEqualTo(1);
    }

    @Test
    void campaignReportGroupsByCampaignAndBatchWithoutInventingTouches() throws Exception {
        long customer = fixture.customer(1, 2, 1);
        Fixture.CatalogIds catalog = fixture.catalog("ITR-CAMPAIGN", "大促卡", "6次", true);
        Fixture.CampaignIds firstCampaign = fixture.campaign("ITR-CAMPAIGN-1", "当前活动名");
        Fixture.CampaignIds secondCampaign = fixture.campaign("ITR-CAMPAIGN-2", "另一活动");
        long firstOrder = fixture.order(1, customer, 2, "PAID", "PLATFORM",
                10_000, "2026-10-10T03:00:00Z", "ITR_CAMPAIGN",
                new Fixture.SaleAttribution(firstCampaign.campaignId(), firstCampaign.firstBatchId(), null, null,
                        "CAMPAIGN-SNAPSHOT", "成交活动快照", "BATCH-1-SNAPSHOT", "成交批次快照"));
        long firstItem = fixture.orderItem(1, firstOrder, catalog, 1);
        fixture.payment(1, firstOrder, "PLATFORM", "CONFIRMED", 10_000, "2026-10-10T03:00:00Z");
        fixture.refund(1, firstOrder, customer, "SUCCEEDED", "SUCCEEDED", 1_000, "2026-10-11T03:00:00Z");
        fixture.redemption(1, firstOrder, firstItem, catalog, customer, 2,
                "2026-10-12T03:00:00Z", 2);
        long secondOrder = fixture.order(1, customer, 2, "PAID", "PLATFORM",
                4_000, "2026-10-10T04:00:00Z", "ITR_CAMPAIGN",
                new Fixture.SaleAttribution(firstCampaign.campaignId(), firstCampaign.secondBatchId(), null, null,
                        "CAMPAIGN-SNAPSHOT", "成交活动快照", "BATCH-2-SNAPSHOT", "第二批次快照"));
        fixture.payment(1, secondOrder, "PLATFORM", "CONFIRMED", 4_000, "2026-10-10T04:00:00Z");
        long otherOrder = fixture.order(1, customer, 2, "PAID", "PLATFORM",
                9_000, "2026-10-10T05:00:00Z", "ITR_CAMPAIGN",
                new Fixture.SaleAttribution(secondCampaign.campaignId(), secondCampaign.firstBatchId(), null, null,
                        "OTHER-SNAPSHOT", "另一活动", "OTHER-BATCH-SNAPSHOT", "另一批次"));
        fixture.payment(1, otherOrder, "PLATFORM", "CONFIRMED", 9_000, "2026-10-10T05:00:00Z");

        JsonNode data = data(withRange(get("/api/v1/reports/campaigns"), "ITR_CAMPAIGN")
                .param("campaignId", Long.toString(firstCampaign.campaignId()))
                .param("drillDownType", "REDEMPTION").param("drillDownLimit", "1")
                .cookie(login("admin").cookies()));

        assertThat(data.at("/total").asLong()).isEqualTo(2);
        assertThat(data.at("/sourceWatermark").asText()).isEqualTo("2026-10-12T03:00:00Z");
        assertThat(data.at("/totals/paidFen").asLong()).isEqualTo(14_000);
        assertThat(data.at("/totals/refundFen").asLong()).isEqualTo(1_000);
        JsonNode first = row(data, "campaignBatchId", firstCampaign.firstBatchId());
        assertThat(first.at("/campaignCode").asText()).isEqualTo("CAMPAIGN-SNAPSHOT");
        assertThat(first.at("/campaignBatchCode").asText()).isEqualTo("BATCH-1-SNAPSHOT");
        assertThat(first.at("/campaignName").asText()).isEqualTo("成交活动快照");
        assertThat(first.at("/campaignBatchName").asText()).isEqualTo("成交批次快照");
        assertThat(first.at("/paidFen").asLong()).isEqualTo(10_000);
        assertThat(first.at("/netPaidFen").asLong()).isEqualTo(9_000);
        assertThat(first.at("/confirmedAppointmentCount").asLong()).isEqualTo(1);
        assertThat(first.at("/checkedInAppointmentCount").asLong()).isEqualTo(1);
        assertThat(first.at("/redeemedCount").asLong()).isEqualTo(2);
        assertThat(first.at("/touchCustomerCount").isNull()).isTrue();
        assertThat(first.at("/touchStatus").asText()).isEqualTo("SOURCE_NOT_AVAILABLE");
        assertThat(first.at("/conversionRates/contactToPaid/value").isNull()).isTrue();
        assertThat(first.at("/conversionRates/contactToPaid/status").asText()).isEqualTo("PENDING_DEFINITION");
        assertThat(data.at("/drillDown")).hasSize(1);
        assertThat(data.at("/drillDown/0/factType").asText()).isEqualTo("REDEMPTION");
    }

    @Test
    void skuQualityCountsOnlyActuallyPaidLinesAndNeverAutoDisablesSku() throws Exception {
        long customer = fixture.customer(1, 2, 1);
        Fixture.CatalogIds paidSku = fixture.catalog("ITR-SKU-PAID", "光子卡", "3次", true);
        long paidOrder = fixture.order(1, customer, 2, "PAID", "PLATFORM",
                20_000, "2026-10-15T03:00:00Z", "ITR_SKU");
        fixture.orderItem(1, paidOrder, paidSku, 2);
        fixture.payment(1, paidOrder, "PLATFORM", "CONFIRMED", 20_000, "2026-10-15T03:00:00Z");
        long draft = fixture.order(1, customer, 2, "DRAFT", "PLATFORM",
                99_000, null, "ITR_SKU");
        fixture.orderItem(1, draft, paidSku, 99);
        fixture.payment(1, draft, "PLATFORM", "CONFIRMED", 99_000, "2026-10-15T04:00:00Z");
        long pending = fixture.order(1, customer, 2, "PENDING_PAYMENT", "PLATFORM",
                8_000, null, "ITR_SKU");
        fixture.orderItem(1, pending, paidSku, 8);
        fixture.payment(1, pending, "PLATFORM", "PENDING", 8_000, "2026-10-15T05:00:00Z");

        JsonNode data = data(withRange(get("/api/v1/reports/sku-quality"), "ITR_SKU")
                .param("skuId", Long.toString(paidSku.skuId())).param("lowFrequencyThreshold", "3")
                .param("drillDownType", "PAID_ORDER_LINE").param("drillDownLimit", "1")
                .cookie(login("admin").cookies()));
        assertThat(data.at("/sourceWatermark").asText()).isEqualTo("2026-10-15T03:00:00Z");
        JsonNode sku = data.at("/items/0");
        assertThat(sku.at("/salesQuantity").asLong()).isEqualTo(2);
        assertThat(sku.at("/paidOrderCount").asLong()).isEqualTo(1);
        assertThat(sku.at("/paidLineCount").asLong()).isEqualTo(1);
        assertThat(sku.at("/lowFrequency").asBoolean()).isTrue();
        assertThat(sku.at("/automaticAction").asText()).isEqualTo("NONE");
        assertThat(data.at("/lowFrequencyThreshold").asInt()).isEqualTo(3);
        assertThat(data.at("/periodDays").asInt()).isEqualTo(31);
        assertThat(data.at("/drillDown")).hasSize(1);
        assertThat(data.at("/drillDown/0/quantity").asLong()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_sku WHERE id=?",
                String.class, paidSku.skuId())).isEqualTo("ACTIVE");

        JsonNode aboveThreshold = data(withRange(get("/api/v1/reports/sku-quality"), "ITR_SKU")
                .param("skuId", Long.toString(paidSku.skuId())).param("lowFrequencyThreshold", "1")
                .cookie(login("admin").cookies()));
        assertThat(aboveThreshold.at("/items/0/lowFrequency").asBoolean()).isFalse();

        Fixture.CatalogIds brokenSku = fixture.catalog("", "", "", false);
        JsonNode broken = data(withRange(get("/api/v1/reports/sku-quality"), "ITR_SKU")
                .param("skuId", Long.toString(brokenSku.skuId())).param("lowFrequencyThreshold", "0")
                .cookie(login("admin").cookies()));
        assertThat(broken.at("/sourceWatermark").isNull()).isTrue();
        assertThat(broken.at("/items/0/issueCodes").toString()).contains(
                "MISSING_SKU_CODE", "MISSING_STANDARD_NAME", "MISSING_ACTIVE_INSTITUTION", "LOW_FREQUENCY");
        assertThat(broken.at("/items/0/automaticAction").asText()).isEqualTo("NONE");
    }

    @Test
    void tenantTeamAndSelfScopesAndAllQueryLimitsAreEnforced() throws Exception {
        long own = fixture.customer(1, 2, 1);
        long otherTeam = fixture.customer(1, 3, 2);
        long otherTenant = fixture.customer(2, 2, 1);
        scopedPayment(own, 1, 1_000);
        scopedPayment(otherTeam, 1, 2_000);
        scopedPayment(otherTenant, 2, 4_000);

        JsonNode all = data(withRange(get("/api/v1/reports/operations"), "ITR_SCOPE")
                .cookie(login("admin").cookies()));
        assertThat(all.at("/metrics/paidFen").asLong()).isEqualTo(3_000);
        grantManagerReportPermission();
        JsonNode self = data(withRange(get("/api/v1/reports/operations"), "ITR_SCOPE")
                .cookie(login("manager1").cookies()));
        assertThat(self.at("/metrics/paidFen").asLong()).isEqualTo(1_000);
        JsonNode teamOne = data(withRange(get("/api/v1/reports/operations"), "ITR_SCOPE")
                .cookie(login("lead1").cookies()));
        assertThat(teamOne.at("/metrics/paidFen").asLong()).isEqualTo(1_000);
        JsonNode teamTwo = data(withRange(get("/api/v1/reports/operations"), "ITR_SCOPE")
                .cookie(login("lead2").cookies()));
        assertThat(teamTwo.at("/metrics/paidFen").asLong()).isEqualTo(2_000);
        JsonNode dashboardAll = data(withRange(get("/api/v1/reports/operations-dashboard"), "ITR_SCOPE")
                .cookie(login("admin").cookies()));
        assertThat(dashboardAll.at("/metrics/paidFen").asLong()).isEqualTo(3_000);
        JsonNode dashboardSelf = data(withRange(get("/api/v1/reports/operations-dashboard"), "ITR_SCOPE")
                .cookie(login("manager1").cookies()));
        assertThat(dashboardSelf.at("/metrics/paidFen").asLong()).isEqualTo(1_000);

        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/reports/operations").param("from", "2025-01-01")
                        .param("to", "2026-01-02").cookie(admin.cookies()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withRange(get("/api/v1/reports/operations"), "ALL")
                        .param("timeZone", "Mars/Base").cookie(admin.cookies()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withRange(get("/api/v1/reports/performance"), "ALL")
                        .param("size", "101").cookie(admin.cookies()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withRange(get("/api/v1/reports/campaigns"), "ALL")
                        .param("page", "10001").cookie(admin.cookies()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withRange(get("/api/v1/reports/operations"), "ALL")
                        .param("drillDownLimit", "51").cookie(admin.cookies()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withRange(get("/api/v1/reports/sku-quality"), "ALL")
                        .param("lowFrequencyThreshold", "1001").cookie(admin.cookies()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withRange(get("/api/v1/reports/operations"), "ALL")
                        .param("metricVersion", "UNKNOWN").cookie(admin.cookies()))
                .andExpect(status().isBadRequest());
    }

    private void scopedPayment(long customer, long tenant, long amount) {
        long order = fixture.order(tenant, customer, 2, "PAID", "PLATFORM",
                amount, "2026-10-20T03:00:00Z", "ITR_SCOPE");
        fixture.payment(tenant, order, "PLATFORM", "CONFIRMED", amount, "2026-10-20T03:00:00Z");
    }

    private void grantManagerReportPermission() {
        jdbcTemplate.update("INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id) "
                + "SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id "
                + "WHERE r.tenant_id=1 AND r.role_code='MANAGER' AND p.permission_code='report:read'");
    }

    private MockHttpServletRequestBuilder withRange(MockHttpServletRequestBuilder builder, String sourceSystem) {
        return builder.param("from", FROM).param("to", TO).param("timeZone", "Asia/Shanghai")
                .param("sourceSystem", sourceSystem);
    }

    private JsonNode data(MockHttpServletRequestBuilder builder) throws Exception {
        String response = mockMvc.perform(builder).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private JsonNode arrayRow(JsonNode array, String field, Number value) {
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode item : array) {
            JsonNode candidate = item.get(field);
            if (candidate != null && candidate.canConvertToLong() && candidate.asLong() == value.longValue()) {
                matches.add(item);
            }
        }
        assertThat(matches).as("row %s=%s in %s", field, value, array).hasSize(1);
        return matches.getFirst();
    }

    private void assertDaily(JsonNode daily, String statDate, long paidOrders, long refunds,
                             long paidFen, long refundFen, long netFen) {
        assertThat(daily.at("/statDate").asText()).isEqualTo(statDate);
        assertThat(daily.at("/paidOrderCount").asLong()).isEqualTo(paidOrders);
        assertThat(daily.at("/refundCount").asLong()).isEqualTo(refunds);
        assertThat(daily.at("/paidFen").asLong()).isEqualTo(paidFen);
        assertThat(daily.at("/refundFen").asLong()).isEqualTo(refundFen);
        assertThat(daily.at("/netPaidFen").asLong()).isEqualTo(netFen);
    }

    private void assertTeamTargetUnconfigured(JsonNode response) {
        assertThat(response.at("/teamTargetFen").isNull()).isTrue();
        assertThat(response.at("/teamTargetStatus").asText()).isEqualTo("UNCONFIGURED");
        assertThat(response.at("/teamTargetCompletionRate").isNull()).isTrue();
        assertThat(response.at("/teamTargetCompletionStatus").asText()).isEqualTo("UNCONFIGURED");
    }

    private JsonNode row(JsonNode response, String field, Number value) {
        List<JsonNode> matches = rows(response, field, value);
        assertThat(matches).as("row %s=%s in %s", field, value, response).hasSize(1);
        return matches.getFirst();
    }

    private List<JsonNode> rows(JsonNode response, String field, Number value) {
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode item : response.at("/items")) {
            JsonNode candidate = item.get(field);
            if ((value == null && candidate != null && candidate.isNull())
                    || (value != null && candidate != null && candidate.canConvertToLong()
                    && candidate.asLong() == value.longValue())) {
                matches.add(item);
            }
        }
        return matches;
    }

    private static final class Fixture {
        private static final AtomicLong IDS = new AtomicLong(900_000_000_000L);
        private final JdbcTemplate jdbc;

        private Fixture(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        long employee(String displayName, long team) {
            long id = id();
            jdbc.update("INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,user_type,status) "
                    + "VALUES (?,1,1,?,?,?,'EMPLOYEE','ACTIVE')", id, team, "ITR-STAFF-" + id, displayName);
            return id;
        }

        long customer(long tenant, long owner, long team) {
            long id = id();
            jdbc.update("INSERT INTO crm_customer (id,tenant_id,owner_staff_id,team_id,customer_no,nickname) "
                            + "VALUES (?,?,?,?,?,?)", id, tenant, owner, team, "ITR-C-" + id, "报表测试" + id);
            return id;
        }

        CatalogIds catalog(String codePrefix, String packageName, String specName, boolean withInstitution) {
            long project = id();
            long packageId = id();
            long version = id();
            long sku = id();
            long institution = id();
            String unique = codePrefix.isEmpty() ? "EMPTY-" + sku : codePrefix + "-" + sku;
            jdbc.update("INSERT INTO crm_project (id,tenant_id,project_code,project_name,list_price_fen,status) "
                    + "VALUES (?,1,?,?,1000,'ACTIVE')", project, unique, packageName);
            jdbc.update("INSERT INTO crm_package (id,tenant_id,package_code,package_name,status) "
                    + "VALUES (?,1,?,?,'ACTIVE')", packageId, unique, packageName);
            jdbc.update("INSERT INTO crm_package_version (id,tenant_id,package_id,version_no,selection_mode,choice_count,status) "
                    + "VALUES (?,1,?,1,'FIXED',1,'ACTIVE')", version, packageId);
            jdbc.update("INSERT INTO crm_sku (id,tenant_id,sku_code,package_version_id,spec_name,sale_price_fen,status) "
                    + "VALUES (?,1,?,?,?,1000,'ACTIVE')", sku, codePrefix.isEmpty() ? "" : unique, version, specName);
            jdbc.update("INSERT INTO crm_institution (id,tenant_id,institution_no,full_name,status,settlement_type) "
                    + "VALUES (?,1,?,?,'ACTIVE','MONTHLY')", institution, "ITR-I-" + institution, "报表测试机构" + institution);
            if (withInstitution) {
                jdbc.update("INSERT INTO crm_sku_institution (id,tenant_id,sku_id,institution_id,status) "
                        + "VALUES (?,1,?,?,'ACTIVE')", id(), sku, institution);
            }
            return new CatalogIds(sku, version, project, institution);
        }

        long order(long tenant, long customer, long ownerAtSale, String status, String receiver,
                   long amount, String paidAt, String sourceSystem) {
            return order(tenant, customer, ownerAtSale, status, receiver, amount, paidAt, sourceSystem,
                    SaleAttribution.empty());
        }

        long order(long tenant, long customer, long ownerAtSale, String status, String receiver,
                   long amount, String paidAt, String sourceSystem, SaleAttribution attribution) {
            long id = id();
            jdbc.update("INSERT INTO crm_order (id,tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                            + "source_system,order_type_code,campaign_id,campaign_batch_id,campaign_code_snapshot,"
                            + "campaign_name_snapshot,batch_code_snapshot,batch_name_snapshot,channel_id,business_owner_at_sale_id,"
                            + "status,receiver_type,list_amount_fen,discount_amount_fen,payable_fen,paid_fen,refunded_fen,"
                            + "ordered_at,paid_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, tenant, "ITR-O-" + id, customer, 2, ownerAtSale, sourceSystem, "PROMOTION",
                    attribution.campaignId(), attribution.campaignBatchId(), attribution.campaignCode(),
                    attribution.campaignName(), attribution.batchCode(), attribution.batchName(), attribution.channelId(),
                    attribution.businessOwnerId(), status, receiver, amount, 0, amount, 0, 0,
                    ts("2026-10-01T00:00:00Z"), paidAt == null ? null : ts(paidAt));
            return id;
        }

        record SaleAttribution(Long campaignId, Long campaignBatchId, Long channelId, Long businessOwnerId,
                               String campaignCode, String campaignName, String batchCode, String batchName) {
            static SaleAttribution empty() {
                return new SaleAttribution(null, null, null, null, null, null, null, null);
            }
        }

        long orderItem(long tenant, long order, CatalogIds catalog, int quantity) {
            long id = id();
            jdbc.update("INSERT INTO crm_order_item (id,tenant_id,order_id,sku_id,package_version_id,sku_code_snapshot,"
                            + "sku_name_snapshot,purchase_quantity,list_amount_fen,paid_allocated_fen) VALUES (?,?,?,?,?,?,?,?,?,0)",
                    id, tenant, order, catalog.skuId(), catalog.packageVersionId(), "ITR-SKU-SNAPSHOT",
                    "销售时SKU快照", quantity, quantity * 1000L);
            return id;
        }

        void payment(long tenant, long order, String receiver, String status, long amount, String confirmedAt) {
            long id = id();
            jdbc.update("INSERT INTO crm_payment_txn (id,tenant_id,order_id,payment_channel,receiver_type,external_trade_no,"
                            + "amount_fen,status,confirmed_at,verification_method) VALUES (?,?,?,'BANK',?,?,?,?,?,'MANUAL_REVIEW')",
                    id, tenant, order, receiver, "ITR-PAY-" + id, amount, status, ts(confirmedAt));
        }

        void refund(long tenant, long order, long customer, String refundStatus, String paymentStatus,
                    long amount, String succeededAt) {
            long refund = id();
            long payment = id();
            jdbc.update("INSERT INTO crm_refund (id,tenant_id,refund_no,order_id,customer_id,requested_fen,approved_fen,"
                            + "succeeded_fen,reason_code,status,requested_by,succeeded_at) VALUES (?,?,?,?,?,?,?,?,?,?,2,?)",
                    refund, tenant, "ITR-R-" + refund, order, customer, amount, amount,
                    "SUCCEEDED".equals(refundStatus) ? amount : 0, "ITR", refundStatus, ts(succeededAt));
            jdbc.update("INSERT INTO crm_refund_payment (id,tenant_id,refund_id,provider,external_refund_no,amount_fen,"
                            + "status,verification_method,succeeded_at) VALUES (?,?,?,'BANK',?,?,?,'MANUAL_REVIEW',?)",
                    payment, tenant, refund, "ITR-RP-" + payment, amount, paymentStatus, ts(succeededAt));
        }

        void followup(long tenant, long customer, long owner, String contactAt, boolean deleted) {
            jdbc.update("INSERT INTO crm_followup (id,tenant_id,customer_id,owner_staff_id,channel_code,contact_at,created_by,deleted) "
                    + "VALUES (?,?,?,?,'WECOM',?,2,?)", id(), tenant, customer, owner, ts(contactAt), deleted);
        }

        void serviceDoneAppointment(long tenant, long customer, long institution, long owner, String scheduledAt) {
            long appointment = id();
            jdbc.update("INSERT INTO crm_appointment (id,tenant_id,appointment_no,customer_id,institution_id,owner_staff_id,"
                            + "team_id,scheduled_at,status,created_by) VALUES (?,?,?,?,?,?,1,?,'SERVICE_DONE',2)",
                    appointment, tenant, "ITR-A-" + appointment, customer, institution, owner, ts(scheduledAt));
            history(tenant, appointment, "PENDING_INSTITUTION", "CONFIRMED",
                    Instant.parse(scheduledAt).minusSeconds(7200).toString());
            history(tenant, appointment, "CONFIRMED", "CHECKED_IN",
                    Instant.parse(scheduledAt).minusSeconds(3600).toString());
            history(tenant, appointment, "CHECKED_IN", "SERVICE_DONE", scheduledAt);
        }

        long redemption(long tenant, long order, long orderItem, CatalogIds catalog, long customer, long owner,
                        String postedAt, int count) {
            long entitlement = id();
            long appointment = id();
            long appointmentItem = id();
            long redemption = id();
            jdbc.update("INSERT INTO crm_entitlement (id,tenant_id,customer_id,order_item_id,project_id,granted_count,"
                            + "available_count,version_no,status) VALUES (?,?,?,?,?,?,0,0,'AVAILABLE')",
                    entitlement, tenant, customer, orderItem, catalog.projectId(), count);
            jdbc.update("INSERT INTO crm_appointment (id,tenant_id,appointment_no,customer_id,institution_id,owner_staff_id,"
                            + "team_id,scheduled_at,status,created_by) VALUES (?,?,?,?,?,?,1,?,'REDEEMED',2)",
                    appointment, tenant, "ITR-A-" + appointment, customer, catalog.institutionId(), owner, ts(postedAt));
            jdbc.update("INSERT INTO crm_appointment_item (id,tenant_id,appointment_id,entitlement_id,project_id,planned_count) "
                    + "VALUES (?,?,?,?,?,?)", appointmentItem, tenant, appointment, entitlement, catalog.projectId(), count);
            history(tenant, appointment, "PENDING_INSTITUTION", "CONFIRMED",
                    Instant.parse(postedAt).minusSeconds(7200).toString());
            history(tenant, appointment, "CONFIRMED", "CHECKED_IN",
                    Instant.parse(postedAt).minusSeconds(3600).toString());
            history(tenant, appointment, "CHECKED_IN", "SERVICE_DONE",
                    Instant.parse(postedAt).minusSeconds(1800).toString());
            jdbc.update("INSERT INTO crm_redemption (id,tenant_id,appointment_id,customer_id,institution_id,owner_staff_id,"
                            + "team_id,service_at,evidence_ref,status,idempotency_key,posted_by,posted_at) "
                            + "VALUES (?,?,?,?,?,?,1,?,'ITR-EVIDENCE','POSTED',?,10,?)",
                    redemption, tenant, appointment, customer, catalog.institutionId(), owner, ts(postedAt),
                    "ITR-REDEMPTION-" + redemption, ts(postedAt));
            jdbc.update("INSERT INTO crm_redemption_line (id,tenant_id,redemption_id,appointment_item_id,entitlement_id,project_id,count) "
                    + "VALUES (?,?,?,?,?,?,?)", id(), tenant, redemption, appointmentItem, entitlement, catalog.projectId(), count);
            return redemption;
        }

        void verifyRedemptionSettlement(long institution, long redemption) {
            long batch = id();
            jdbc.update("INSERT INTO crm_settlement_batch (id,tenant_id,institution_id,settlement_type,period_key,status,"
                            + "amount_status,gross_fen,commission_fen,net_payable_fen,paid_fen,rule_version,verification_evidence) "
                            + "VALUES (?,1,?,'PER_REDEMPTION',?,'CONFIRMED','VERIFIED',1000,0,1000,0,'ITR-RULE','ITR-VERIFIED')",
                    batch, institution, "REDEMPTION:" + redemption);
            jdbc.update("INSERT INTO crm_settlement_line (id,tenant_id,settlement_batch_id,redemption_id,amount_status,"
                            + "gross_fen,commission_fen,payable_fen,rate_snapshot,pending_reason) "
                            + "VALUES (?,1,?,?,'PENDING_VERIFICATION',0,0,0,'{}','ITR')", id(), batch, redemption);
        }

        void approvedPerformanceTarget(long staff, String role, long target) {
            jdbc.update("INSERT INTO crm_performance_snapshot (id,tenant_id,period_key,staff_user_id,performance_role,"
                            + "gross_fen,refund_fen,net_fen,target_fen,rule_version,status,approved_by,source_watermark,created_by) "
                            + "VALUES (?,1,'2026-10',?,?,0,0,0,?,'P2_FACT_V1','APPROVED',1,UTC_TIMESTAMP(3),1)",
                    id(), staff, role, target);
        }

        void history(long tenant, long appointment, String from, String to, String createdAt) {
            jdbc.update("INSERT INTO crm_appointment_history (id,tenant_id,appointment_id,from_status,to_status,actor_user_id,created_at) "
                    + "VALUES (?,?,?,?,?,10,?)", id(), tenant, appointment, from, to, ts(createdAt));
        }

        CampaignIds campaign(String code, String name) {
            long campaign = id();
            long firstBatch = id();
            long secondBatch = id();
            jdbc.update("INSERT INTO crm_campaign (id,tenant_id,campaign_code,campaign_name,campaign_type,status) "
                    + "VALUES (?,1,?,?,'PROMOTION','ACTIVE')", campaign, code + "-" + campaign, name);
            jdbc.update("INSERT INTO crm_campaign_batch (id,tenant_id,campaign_id,batch_code,batch_name) VALUES (?,1,?,?,?)",
                    firstBatch, campaign, "B1-" + firstBatch, "当前批次一");
            jdbc.update("INSERT INTO crm_campaign_batch (id,tenant_id,campaign_id,batch_code,batch_name) VALUES (?,1,?,?,?)",
                    secondBatch, campaign, "B2-" + secondBatch, "当前批次二");
            return new CampaignIds(campaign, firstBatch, secondBatch);
        }

        long channel(long tenant, Long businessOwner) {
            long id = id();
            jdbc.update("INSERT INTO crm_partner_channel (id,tenant_id,channel_no,name,business_owner_user_id,status) "
                    + "VALUES (?,?,?,?,?,'ACTIVE')", id, tenant, "ITR-CH-" + id, "当前渠道名" + id, businessOwner);
            return id;
        }

        private long id() {
            return IDS.getAndIncrement();
        }

        private Timestamp ts(String value) {
            return Timestamp.from(Instant.parse(value));
        }

        record CatalogIds(long skuId, long packageVersionId, long projectId, long institutionId) {
        }

        record CampaignIds(long campaignId, long firstBatchId, long secondBatchId) {
        }
    }
}
