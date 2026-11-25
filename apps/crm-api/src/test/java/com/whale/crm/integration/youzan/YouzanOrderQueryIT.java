package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.adapter.MockYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanOrderLine;
import com.whale.crm.integration.youzan.application.YouzanEventService;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.outbox.relay.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YouzanOrderQueryIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockYouzanAdapter adapter;
    @Autowired
    private AesGcmCipher cipher;
    @Autowired
    private YouzanRepository youzanRepository;
    @Autowired
    private YouzanEventService youzanEventService;

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
    void mappingFailureAndMappedOrderExposeRawAndInternalFieldsWithoutSecrets() throws Exception {
        String shop = shop("fields");
        String externalCustomer = "IT-YZ-Q-CUSTOMER-" + suffix();
        seedSkuAndCustomer(shop, externalCustomer, true);
        String failedOrder = "IT-YZ-Q-FAILED-" + suffix();
        String paidOrder = "IT-YZ-Q-PAID-" + suffix();
        YouzanOrderEvent failed = event(shop, "IT-YZ-Q-FAILED-EVENT-" + suffix(), failedOrder,
                externalCustomer, "IT-YZ-Q-FAILED-TRADE-" + suffix(), "YZ-SKU-MISSING", 181100,
                Instant.parse("2026-10-24T04:00:00Z"));
        YouzanOrderEvent paid = event(shop, "IT-YZ-Q-PAID-EVENT-" + suffix(), paidOrder,
                externalCustomer, "IT-YZ-Q-PAID-TRADE-" + suffix(), "YZ-SKU-301", 181100,
                Instant.parse("2026-10-24T05:00:00Z"));
        String mobile = "13900000000";
        String nickname = "IT-YZ-Q-FICTIONAL-NICKNAME";
        String address = "IT-YZ-Q-FICTIONAL-ADDRESS";
        ObjectNode failedBody = objectMapper.valueToTree(failed);
        failedBody.put("mobile", mobile);
        failedBody.put("nickname", nickname);
        failedBody.putObject("receiver").put("contactName", nickname).put("address", address);

        postWebhook(json(failedBody));
        long failedRawId = awaitEvent(shop, failed.eventId(), "MAPPING_FAILED");
        String encrypted = queryString("SELECT payload_cipher FROM crm_youzan_event WHERE tenant_id=1 AND id=?", failedRawId);
        postWebhook(json(paid));
        awaitEvent(shop, paid.eventId(), "PROCESSED");

        JsonNode page = orders(admin, shop, 1, 20, null);
        assertThat(page.at("/total").asLong()).isEqualTo(2);
        assertThat(page.at("/items").size()).isEqualTo(2);
        JsonNode failedView = item(page, failedOrder);
        assertThat(failedView.at("/eventId").asText()).isEqualTo(failed.eventId());
        assertThat(failedView.at("/mapId").isNull()).isTrue();
        assertThat(failedView.at("/orderId").isNull()).isTrue();
        assertThat(failedView.at("/status").asText()).isEqualTo("MAPPING_FAILED");
        assertThat(failedView.at("/mappingStatus").asText()).isEqualTo("MAPPING_FAILED");
        assertThat(failedView.at("/orderStatus").isNull()).isTrue();
        assertThat(failedView.at("/sourceUpdatedAt").asText()).isEqualTo("2026-10-24T04:00:00Z");
        assertThat(failedView.at("/externalAmountFen").asLong()).isEqualTo(181100);
        assertThat(failedView.at("/paidFen").isNull()).isTrue();
        assertThat(failedView.at("/verificationMethod").isNull()).isTrue();
        assertThat(failedView.at("/sourceSystem").asText()).isEqualTo("WEBHOOK");
        assertThat(failedView.at("/externalShopId").asText()).isEqualTo(shop);
        assertThat(failedView.at("/errorReason").asText()).contains("YZ-SKU-MISSING");
        assertThat(failedView.at("/payloadMaskedJson").asText())
                .contains("\"mobile\":\"***\"", "\"nickname\":\"***\"",
                        "\"contactName\":\"***\"", "\"address\":\"***\"")
                .doesNotContain(mobile, nickname, address);
        assertThat(page.toString()).doesNotContain(encrypted, mobile, nickname, address);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", shop, failedOrder)).isZero();

        JsonNode paidView = item(page, paidOrder);
        assertThat(paidView.at("/mapId").asLong()).isPositive();
        assertThat(paidView.at("/orderId").asLong()).isPositive();
        assertThat(paidView.at("/status").asText()).isEqualTo("PROCESSED");
        assertThat(paidView.at("/mappingStatus").asText()).isEqualTo("PROCESSED");
        assertThat(paidView.at("/orderStatus").asText()).isEqualTo("PAID");
        assertThat(paidView.at("/sourceUpdatedAt").asText()).isEqualTo("2026-10-24T05:00:00Z");
        assertThat(paidView.at("/externalAmountFen").asLong()).isEqualTo(181100);
        assertThat(paidView.at("/paidFen").asLong()).isEqualTo(181100);
        assertThat(paidView.at("/verificationMethod").asText()).isEqualTo("PROVIDER_CALLBACK");
        assertThat(paidView.at("/sourceSystem").asText()).isEqualTo("YOUZAN");
        assertThat(paidView.at("/externalShopId").asText()).isEqualTo(shop);

        JsonNode detail = data(manager1.withAuth(get("/api/v1/integrations/youzan/orders/{id}", failedRawId)));
        assertThat(detail.at("/eventId").asText()).isEqualTo(failed.eventId());
        assertThat(detail.at("/mappingStatus").asText()).isEqualTo("MAPPING_FAILED");
        assertThat(detail.at("/orderId").isNull()).isTrue();
        assertThat(detail.at("/payloadMaskedJson").isNull()).isTrue();
        assertThat(detail.toString()).doesNotContain(encrypted, mobile, nickname, address);
        JsonNode managerPage = orders(manager1, shop, 1, 20, null);
        assertThat(managerPage.at("/total").asLong()).isEqualTo(2);
        assertThat(item(managerPage, failedOrder).at("/payloadMaskedJson").isNull()).isTrue();
        JsonNode manager2Page = orders(manager2, shop, 1, 20, null);
        assertThat(manager2Page.at("/items")).isEmpty();
        assertThat(manager2Page.at("/total").asLong()).isZero();
        mockMvc.perform(get("/api/v1/integrations/youzan/orders/{id}", failedRawId)
                        .cookie(manager2.cookies()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void dataScopeEnforcedAcrossReportIssuesLinksForTeamSelfNoneAndInstitution() throws Exception {
        String shop = shop("scope");
        String externalCustomer = "IT-YZ-SCOPE-CUST-" + suffix();
        seedSkuAndCustomer(shop, externalCustomer, true);
        String failedOrder = "IT-YZ-SCOPE-FAIL-" + suffix();
        YouzanOrderEvent failed = event(shop, "IT-YZ-SCOPE-EV-" + suffix(), failedOrder,
                externalCustomer, "IT-YZ-SCOPE-TR-" + suffix(), "YZ-SKU-MISSING", 181100,
                Instant.parse("2026-10-24T04:00:00Z"));
        postWebhook(json(failed));
        long failedRawId = awaitEvent(shop, failed.eventId(), "MAPPING_FAILED");

        AuthCookies lead1 = login("lead1");
        JsonNode leadOrders = orders(lead1, shop, 1, 20, null);
        assertThat(leadOrders.at("/total").asLong()).isEqualTo(1);
        JsonNode leadReport = data(lead1.withAuth(get("/api/v1/integrations/youzan/reconcile-report").param("shopId", shop)));
        assertThat(leadReport.at("/totalEvents").asLong()).isEqualTo(1);
        JsonNode leadLinks = data(lead1.withAuth(get("/api/v1/integrations/youzan/customer-links").param("shopId", shop)));
        assertThat(leadLinks.size()).isEqualTo(1);

        AuthCookies lead2 = login("lead2");
        JsonNode lead2Orders = orders(lead2, shop, 1, 20, null);
        assertThat(lead2Orders.at("/items")).isEmpty();
        assertThat(lead2Orders.at("/total").asLong()).isZero();
        JsonNode lead2Report = data(lead2.withAuth(get("/api/v1/integrations/youzan/reconcile-report").param("shopId", shop)));
        assertThat(lead2Report.at("/totalEvents").asLong()).isZero();

        JsonNode m2Report = data(manager2.withAuth(get("/api/v1/integrations/youzan/reconcile-report").param("shopId", shop)));
        assertThat(m2Report.at("/totalEvents").asLong()).isZero();
        assertThat(m2Report.at("/internalOrders").asLong()).isZero();
        JsonNode m2Links = data(manager2.withAuth(get("/api/v1/integrations/youzan/customer-links").param("shopId", shop)));
        assertThat(m2Links).isEmpty();
        JsonNode m2Issues = data(manager2.withAuth(get("/api/v1/integrations/youzan/sync-issues").param("size", "100")));
        m2Issues.at("/items").forEach(item ->
                assertThat(item.at("/detailMasked").asText()).doesNotContain(failedOrder));

        AuthCookies business1 = login("business1");
        mockMvc.perform(get("/api/v1/integrations/youzan/orders").param("shopId", shop)
                        .cookie(business1.cookies()))
                .andExpect(status().isOk());
        JsonNode businessPage = orders(business1, shop, 1, 20, null);
        assertThat(businessPage.at("/items")).isEmpty();
        assertThat(businessPage.at("/total").asLong()).isZero();
        JsonNode businessReport = data(business1.withAuth(
                get("/api/v1/integrations/youzan/reconcile-report").param("shopId", shop)));
        assertThat(businessReport.at("/totalEvents").asLong()).isZero();
        assertThat(businessReport.at("/internalOrders").asLong()).isZero();
        assertThat(businessReport.at("/openIssues").asLong()).isZero();
        assertThat(businessReport.at("/todayEvents").asLong()).isZero();
        assertThat(businessReport.at("/campaignOrders").asLong()).isZero();
        JsonNode businessIssues = data(business1.withAuth(
                get("/api/v1/integrations/youzan/sync-issues").param("size", "20")));
        assertThat(businessIssues.at("/items")).isEmpty();
        assertThat(businessIssues.at("/total").asLong()).isZero();
        JsonNode businessLinks = data(business1.withAuth(
                get("/api/v1/integrations/youzan/customer-links").param("shopId", shop)));
        assertThat(businessLinks).isEmpty();
        mockMvc.perform(get("/api/v1/integrations/youzan/orders/{id}", failedRawId)
                        .cookie(business1.cookies()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        AuthCookies operator1 = login("operator1");
        mockMvc.perform(get("/api/v1/integrations/youzan/orders").param("shopId", shop)
                        .cookie(operator1.cookies()))
                .andExpect(status().isForbidden());
    }

    @Test
    void mappedOrderOfSoftDeletedCustomerIsOmittedFromListAnd404OnDetailForAllAndSelfScope() throws Exception {
        Map<String, Object> managerRow = jdbcTemplate.queryForMap(
                "SELECT id,team_id FROM sys_user WHERE tenant_id=1 AND username='manager1'");
        long manager1Id = ((Number) managerRow.get("id")).longValue();
        long manager1TeamId = ((Number) managerRow.get("team_id")).longValue();

        long activeCustomerId = insertCustomer(manager1Id, manager1TeamId, "YZ-ACTIVE-" + suffix());
        long deletedCustomerId = insertCustomer(manager1Id, manager1TeamId, "YZ-DELETED-" + suffix());
        String mixedShop = shop("deleted-mixed");
        seedSku(mixedShop);
        String activeExtCust = "IT-YZ-ACT-CUST-" + suffix();
        String deletedExtCust = "IT-YZ-DEL-CUST-" + suffix();
        linkCustomer(mixedShop, activeExtCust, activeCustomerId);
        linkCustomer(mixedShop, deletedExtCust, deletedCustomerId);

        String activeOrder = "IT-YZ-ACT-ORD-" + suffix();
        String deletedOrder = "IT-YZ-DEL-ORD-" + suffix();
        YouzanOrderEvent activeEv = event(mixedShop, "IT-YZ-ACT-EV-" + suffix(), activeOrder, activeExtCust,
                "IT-YZ-ACT-TR-" + suffix(), "YZ-SKU-301", 181100, Instant.parse("2026-10-24T08:00:00Z"));
        YouzanOrderEvent deletedEv = event(mixedShop, "IT-YZ-DEL-EV-" + suffix(), deletedOrder, deletedExtCust,
                "IT-YZ-DEL-TR-" + suffix(), "YZ-SKU-301", 181100, Instant.parse("2026-10-24T08:01:00Z"));
        postWebhook(json(activeEv));
        awaitEvent(mixedShop, activeEv.eventId(), "PROCESSED");
        postWebhook(json(deletedEv));
        long deletedRawId = awaitEvent(mixedShop, deletedEv.eventId(), "PROCESSED");

        jdbcTemplate.update("UPDATE crm_customer SET deleted=1 WHERE tenant_id=1 AND id=?", deletedCustomerId);

        for (AuthCookies actor : List.of(admin, manager1)) {
            JsonNode mixedPage = orders(actor, mixedShop, 1, 20, null);
            assertThat(mixedPage.at("/total").asLong()).isEqualTo(1L);
            assertThat(mixedPage.at("/items").size()).isEqualTo(1);
            assertThat(item(mixedPage, activeOrder).at("/eventId").asText()).isEqualTo(activeEv.eventId());
            mockMvc.perform(get("/api/v1/integrations/youzan/orders/{id}", deletedRawId)
                            .cookie(actor.cookies()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        long onlyDeletedCustomerId = insertCustomer(manager1Id, manager1TeamId, "YZ-ONLY-DEL-" + suffix());
        String deletedOnlyShop = shop("deleted-only");
        seedSku(deletedOnlyShop);
        String onlyDeletedExtCust = "IT-YZ-ONLY-DEL-CUST-" + suffix();
        linkCustomer(deletedOnlyShop, onlyDeletedExtCust, onlyDeletedCustomerId);
        String onlyDeletedOrder = "IT-YZ-ONLY-DEL-ORD-" + suffix();
        YouzanOrderEvent onlyDeletedEv = event(deletedOnlyShop, "IT-YZ-ONLY-DEL-EV-" + suffix(), onlyDeletedOrder,
                onlyDeletedExtCust, "IT-YZ-ONLY-DEL-TR-" + suffix(), "YZ-SKU-301", 181100,
                Instant.parse("2026-10-24T08:02:00Z"));
        postWebhook(json(onlyDeletedEv));
        long onlyDeletedRawId = awaitEvent(deletedOnlyShop, onlyDeletedEv.eventId(), "PROCESSED");

        jdbcTemplate.update("UPDATE crm_customer SET deleted=1 WHERE tenant_id=1 AND id=?", onlyDeletedCustomerId);

        for (AuthCookies actor : List.of(admin, manager1)) {
            JsonNode emptyPage = orders(actor, deletedOnlyShop, 1, 20, null);
            assertThat(emptyPage.at("/items")).isEmpty();
            assertThat(emptyPage.at("/total").asLong()).isZero();
            mockMvc.perform(get("/api/v1/integrations/youzan/orders/{id}", onlyDeletedRawId)
                            .cookie(actor.cookies()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void unlinkedRawIsVisibleToAllButHiddenFromSelfScope() throws Exception {
        String shop = shop("unlinked");
        String externalCustomer = "IT-YZ-Q-UNLINKED-CUSTOMER-" + suffix();
        seedSkuAndCustomer(shop, externalCustomer, false);
        String externalOrder = "IT-YZ-Q-UNLINKED-ORDER-" + suffix();
        YouzanOrderEvent event = event(shop, "IT-YZ-Q-UNLINKED-EVENT-" + suffix(), externalOrder,
                externalCustomer, "IT-YZ-Q-UNLINKED-TRADE-" + suffix(), "YZ-SKU-301", 181100,
                Instant.parse("2026-10-24T06:00:00Z"));

        postWebhook(json(event));
        long rawId = awaitEvent(shop, event.eventId(), "CUSTOMER_UNRESOLVED");

        JsonNode adminPage = orders(admin, shop, 1, 20, null);
        JsonNode adminView = item(adminPage, externalOrder);
        assertThat(adminView.at("/mappingStatus").asText()).isEqualTo("CUSTOMER_UNRESOLVED");
        assertThat(adminView.at("/orderId").isNull()).isTrue();
        assertThat(data(admin.withAuth(get("/api/v1/integrations/youzan/orders/{id}", rawId)))
                .at("/eventId").asText()).isEqualTo(event.eventId());
        JsonNode managerPage = orders(manager1, shop, 1, 20, null);
        assertThat(managerPage.at("/items")).isEmpty();
        assertThat(managerPage.at("/total").asLong()).isZero();
        mockMvc.perform(get("/api/v1/integrations/youzan/orders/{id}", rawId).cookie(manager1.cookies()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void latestRawPerExternalOrderIsPagedWithTenantIsolation() throws Exception {
        String shop = shop("paging");
        String orderA = "IT-YZ-Q-PAGE-A-" + suffix();
        String orderB = "IT-YZ-Q-PAGE-B-" + suffix();
        String customer = "IT-YZ-Q-PAGE-CUSTOMER-" + suffix();
        YouzanOrderEvent oldA = event(shop, "IT-YZ-Q-PAGE-OLD-A-" + suffix(), orderA, customer,
                null, "YZ-SKU-MISSING", 100, Instant.parse("2026-10-24T07:00:00Z"));
        YouzanOrderEvent latestA = event(shop, "IT-YZ-Q-PAGE-LATEST-A-" + suffix(), orderA, customer,
                null, "YZ-SKU-MISSING", 200, Instant.parse("2026-10-24T07:01:00Z"));
        YouzanOrderEvent latestB = event(shop, "IT-YZ-Q-PAGE-LATEST-B-" + suffix(), orderB, customer,
                null, "YZ-SKU-MISSING", 300, Instant.parse("2026-10-24T07:02:00Z"));
        insertRaw(1, shop, oldA, "MAPPING_FAILED", "old error");
        insertRaw(1, shop, latestA, "CUSTOMER_UNRESOLVED", "latest error");
        insertRaw(1, shop, latestB, "RETRY", "retry error");
        String foreignOrder = "IT-YZ-Q-FOREIGN-" + suffix();
        YouzanOrderEvent foreign = event(shop, "IT-YZ-Q-FOREIGN-EVENT-" + suffix(), foreignOrder,
                customer, null, "YZ-SKU-MISSING", 400, Instant.parse("2026-10-24T07:03:00Z"));
        long foreignRawId = insertRaw(999, shop, foreign, "RETRY", "foreign error");

        JsonNode first = orders(admin, shop, 1, 1, "999");
        assertThat(first.at("/total").asLong()).isEqualTo(2);
        assertThat(first.at("/items").size()).isEqualTo(1);
        assertThat(first.at("/items/0/externalOrderNo").asText()).isEqualTo(orderB);
        assertThat(first.at("/items/0/eventId").asText()).isEqualTo(latestB.eventId());
        JsonNode second = orders(admin, shop, 2, 1, "999");
        assertThat(second.at("/total").asLong()).isEqualTo(2);
        assertThat(second.at("/items").size()).isEqualTo(1);
        assertThat(second.at("/items/0/externalOrderNo").asText()).isEqualTo(orderA);
        assertThat(second.at("/items/0/eventId").asText()).isEqualTo(latestA.eventId());
        assertThat(second.at("/items/0/mappingStatus").asText()).isEqualTo("CUSTOMER_UNRESOLVED");
        assertThat(second.at("/items/0/errorReason").asText()).isEqualTo("latest error");
        assertThat(second.at("/items/0/externalAmountFen").asLong()).isEqualTo(200);
        assertThat(second.at("/items/0/sourceUpdatedAt").asText()).isEqualTo("2026-10-24T07:01:00Z");
        assertThat(first.toString() + second).doesNotContain(foreignOrder, oldA.eventId());
        mockMvc.perform(get("/api/v1/integrations/youzan/orders/{id}", foreignRawId)
                        .cookie(admin.cookies()).header("X-Tenant-Id", "999"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void todayEventsUsesAsiaShanghaiClosedOpenUtcWindowAcrossUtcMidnight() throws Exception {
        Map<String, Object> manager1Row = jdbcTemplate.queryForMap(
                "SELECT id,team_id FROM sys_user WHERE tenant_id=1 AND username='manager1'");
        long manager1Id = ((Number) manager1Row.get("id")).longValue();
        long manager1TeamId = ((Number) manager1Row.get("team_id")).longValue();
        long manager2Id = queryLong("SELECT id FROM sys_user WHERE tenant_id=1 AND username='manager2'");
        long manager1CustomerId = insertCustomer(manager1Id, manager1TeamId, "YZ-TZ-" + suffix());

        // 1. 固定业务日 2026-10-25 (Asia/Shanghai) = [2026-10-24T16:00:00Z, 2026-10-25T16:00:00Z)
        // 验证跨 UTC 0 点（北京时间 00:30 = UTC 前一日 16:30）、闭开区间边界及租户/店铺/数据范围隔离
        String fixedShop = shop("tz-fixed");
        String otherShop = shop("tz-other");
        String fixedExtCustomer = "IT-YZ-TZ-CUST-" + suffix();
        linkCustomer(fixedShop, fixedExtCustomer, manager1CustomerId);
        Instant fixedStartUtc = Instant.parse("2026-10-24T16:00:00Z");
        Instant fixedEndUtcExclusive = Instant.parse("2026-10-25T16:00:00Z");

        insertRawAt(1, fixedShop, event(fixedShop, "IT-YZ-TZ-BEFORE-" + suffix(), "ORD-TZ-1-" + suffix(),
                fixedExtCustomer, null, "YZ-SKU-MISSING", 100, fixedStartUtc), Instant.parse("2026-10-24T15:59:59.999Z"));
        insertRawAt(1, fixedShop, event(fixedShop, "IT-YZ-TZ-START-" + suffix(), "ORD-TZ-2-" + suffix(),
                fixedExtCustomer, null, "YZ-SKU-MISSING", 100, fixedStartUtc), Instant.parse("2026-10-24T16:00:00.000Z"));
        insertRawAt(1, fixedShop, event(fixedShop, "IT-YZ-TZ-0030-" + suffix(), "ORD-TZ-3-" + suffix(),
                fixedExtCustomer, null, "YZ-SKU-MISSING", 100, fixedStartUtc), Instant.parse("2026-10-24T16:30:00.000Z"));
        insertRawAt(1, fixedShop, event(fixedShop, "IT-YZ-TZ-END-MS-" + suffix(), "ORD-TZ-4-" + suffix(),
                fixedExtCustomer, null, "YZ-SKU-MISSING", 100, fixedStartUtc), Instant.parse("2026-10-25T15:59:59.999Z"));
        insertRawAt(1, fixedShop, event(fixedShop, "IT-YZ-TZ-NEXT-" + suffix(), "ORD-TZ-5-" + suffix(),
                fixedExtCustomer, null, "YZ-SKU-MISSING", 100, fixedStartUtc), Instant.parse("2026-10-25T16:00:00.000Z"));
        insertRawAt(2, fixedShop, event(fixedShop, "IT-YZ-TZ-T2-" + suffix(), "ORD-TZ-6-" + suffix(),
                fixedExtCustomer, null, "YZ-SKU-MISSING", 100, fixedStartUtc), Instant.parse("2026-10-24T16:30:00.000Z"));
        insertRawAt(1, otherShop, event(otherShop, "IT-YZ-TZ-SHOP-" + suffix(), "ORD-TZ-7-" + suffix(),
                fixedExtCustomer, null, "YZ-SKU-MISSING", 100, fixedStartUtc), Instant.parse("2026-10-24T16:30:00.000Z"));

        assertThat(youzanRepository.countTodayEvents(1L, fixedShop, fixedStartUtc, fixedEndUtcExclusive))
                .isEqualTo(3L);
        assertThat(youzanRepository.countScopedTodayEvents(1L, fixedShop,
                new CustomerScopeSupport.ScopeQuery("SELF", List.of(), manager1Id), fixedStartUtc, fixedEndUtcExclusive))
                .isEqualTo(3L);
        assertThat(youzanRepository.countScopedTodayEvents(1L, fixedShop,
                new CustomerScopeSupport.ScopeQuery("SELF", List.of(), manager2Id), fixedStartUtc, fixedEndUtcExclusive))
                .isZero();

        // 2. 端到端 HTTP /reconcile-report 校验当前 Asia/Shanghai 业务日 [todayStartUtc, todayEndUtcExclusive)
        String liveShop = shop("tz-live");
        String liveExtCustomer = "IT-YZ-TZ-LIVE-CUST-" + suffix();
        linkCustomer(liveShop, liveExtCustomer, manager1CustomerId);
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        LocalDate businessToday = LocalDate.now(shanghai);
        Instant todayStartUtc = businessToday.atStartOfDay(shanghai).toInstant();
        Instant todayEndUtcExclusive = businessToday.plusDays(1).atStartOfDay(shanghai).toInstant();

        insertRawAt(1, liveShop, event(liveShop, "IT-YZ-LIVE-YESTERDAY-" + suffix(), "ORD-LIVE-1-" + suffix(),
                liveExtCustomer, null, "YZ-SKU-MISSING", 100, todayStartUtc), todayStartUtc.minusMillis(1));
        insertRawAt(1, liveShop, event(liveShop, "IT-YZ-LIVE-START-" + suffix(), "ORD-LIVE-2-" + suffix(),
                liveExtCustomer, null, "YZ-SKU-MISSING", 100, todayStartUtc), todayStartUtc);
        insertRawAt(1, liveShop, event(liveShop, "IT-YZ-LIVE-0030-" + suffix(), "ORD-LIVE-3-" + suffix(),
                liveExtCustomer, null, "YZ-SKU-MISSING", 100, todayStartUtc), todayStartUtc.plus(30, ChronoUnit.MINUTES));
        insertRawAt(1, liveShop, event(liveShop, "IT-YZ-LIVE-TOMORROW-" + suffix(), "ORD-LIVE-4-" + suffix(),
                liveExtCustomer, null, "YZ-SKU-MISSING", 100, todayStartUtc), todayEndUtcExclusive);

        JsonNode adminReport = data(admin.withAuth(
                get("/api/v1/integrations/youzan/reconcile-report").param("shopId", liveShop)));
        assertThat(adminReport.at("/totalEvents").asLong()).isEqualTo(4L);
        assertThat(adminReport.at("/todayEvents").asLong()).isEqualTo(2L);

        JsonNode manager1Report = data(manager1.withAuth(
                get("/api/v1/integrations/youzan/reconcile-report").param("shopId", liveShop)));
        assertThat(manager1Report.at("/totalEvents").asLong()).isEqualTo(4L);
        assertThat(manager1Report.at("/todayEvents").asLong()).isEqualTo(2L);

        JsonNode manager2Report = data(manager2.withAuth(
                get("/api/v1/integrations/youzan/reconcile-report").param("shopId", liveShop)));
        assertThat(manager2Report.at("/totalEvents").asLong()).isZero();
        assertThat(manager2Report.at("/todayEvents").asLong()).isZero();
    }

    @Test
    void scopedCampaignOrderCountsAndOrderLatestGreatestMonotonicityAndPayloadBackfillWork() throws Exception {
        Map<String, Object> m1Row = jdbcTemplate.queryForMap(
                "SELECT id,team_id FROM sys_user WHERE tenant_id=1 AND username='manager1'");
        Map<String, Object> m2Row = jdbcTemplate.queryForMap(
                "SELECT id,team_id FROM sys_user WHERE tenant_id=1 AND username='manager2'");
        long m1Id = ((Number) m1Row.get("id")).longValue();
        long m1Team = ((Number) m1Row.get("team_id")).longValue();
        long m2Id = ((Number) m2Row.get("id")).longValue();
        long m2Team = ((Number) m2Row.get("team_id")).longValue();

        long cust1 = insertCustomer(m1Id, m1Team, "YZ-CAMP-M1-" + suffix());
        long cust2 = insertCustomer(m2Id, m2Team, "YZ-CAMP-M2-" + suffix());
        String shop = shop("campaign-latest");
        String extCust1 = "IT-YZ-CAMP-CUST1-" + suffix();
        String extCust2 = "IT-YZ-CAMP-CUST2-" + suffix();
        linkCustomer(shop, extCust1, cust1);
        linkCustomer(shop, extCust2, cust2);

        String ord1 = "IT-YZ-CAMP-ORD1-" + suffix();
        String ord2 = "IT-YZ-CAMP-ORD2-" + suffix();
        String ord3 = "IT-YZ-CAMP-ORD3-" + suffix();
        String ord4 = "IT-YZ-CAMP-ORD4-" + suffix();
        Instant t0 = Instant.parse("2026-10-25T10:00:00Z");

        YouzanOrderEvent ord1Ev1 = new YouzanOrderEvent("IT-YZ-CAMP-E1-" + suffix(), "WAIT_PAY", shop, ord1,
                extCust1, null, "WAIT_PAY", 100L, null, t0, "REGULAR", null, null, "PLATFORM", null,
                List.of(new YouzanOrderLine("YZ-SKU-MISSING", 1)));
        YouzanOrderEvent ord1Ev2 = new YouzanOrderEvent("IT-YZ-CAMP-E2-" + suffix(), "ORDER_PAID", shop, ord1,
                extCust1, "TR-C1", "PAID", 100L, t0, t0.plusSeconds(10), "REGULAR", 101L, 1L, "PLATFORM", null,
                List.of(new YouzanOrderLine("YZ-SKU-MISSING", 1)));
        YouzanOrderEvent ord2Ev = new YouzanOrderEvent("IT-YZ-CAMP-E3-" + suffix(), "ORDER_PAID", shop, ord2,
                extCust1, "TR-C2", "PAID", 100L, t0, t0, "REGULAR", null, null, "PLATFORM", null,
                List.of(new YouzanOrderLine("YZ-SKU-MISSING", 1)));
        YouzanOrderEvent ord3Ev = new YouzanOrderEvent("IT-YZ-CAMP-E4-" + suffix(), "ORDER_PAID", shop, ord3,
                extCust2, "TR-C3", "PAID", 100L, t0, t0, "REGULAR", 202L, 1L, "PLATFORM", null,
                List.of(new YouzanOrderLine("YZ-SKU-MISSING", 1)));

        long ev1Id = insertRaw(1L, shop, ord1Ev1, "MAPPING_FAILED", "err");
        long ev2Id = insertRaw(1L, shop, ord1Ev2, "MAPPING_FAILED", "err");
        insertRaw(1L, shop, ord2Ev, "MAPPING_FAILED", "err");
        insertRaw(1L, shop, ord3Ev, "MAPPING_FAILED", "err");

        assertThat(youzanRepository.countCampaignOrders(1L, shop)).isEqualTo(2L);
        assertThat(youzanRepository.countScopedCampaignOrders(1L, shop,
                new CustomerScopeSupport.ScopeQuery("SELF", List.of(), m1Id))).isEqualTo(1L);
        assertThat(youzanRepository.countScopedCampaignOrders(1L, shop,
                new CustomerScopeSupport.ScopeQuery("SELF", List.of(), m2Id))).isEqualTo(1L);

        // Out-of-order / replay upsertOrderLatest must preserve GREATEST(latest_event_id)
        youzanRepository.upsertOrderLatest(1L, shop, ord1, ev1Id);
        assertThat(queryLong("SELECT latest_event_id FROM crm_youzan_order_latest "
                + "WHERE tenant_id=1 AND shop_id=? AND external_order_no=?", shop, ord1)).isEqualTo(ev2Id);
        JsonNode m1Orders = orders(manager1, shop, 1, 20, null);
        assertThat(m1Orders.at("/total").asLong()).isEqualTo(2L);
        assertThat(item(m1Orders, ord1).at("/eventId").asText()).isEqualTo(ord1Ev2.eventId());

        // Official-style placeholder ingest -> replacePayloadAndProcess backfills external_customer_id & campaign_id
        YouzanOrderEvent placeholder = new YouzanOrderEvent("IT-YZ-CAMP-E5-" + suffix(), "trade_TradePaid", shop,
                ord4, null, null, "NOTIFICATION", null, null, Instant.EPOCH, "REGULAR", null, null,
                "PLATFORM", null, List.of());
        var ingested = youzanEventService.ingest(1L, placeholder, "OFFICIAL_WEBHOOK", "{\"tid\":\"" + ord4 + "\"}");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT external_customer_id FROM crm_youzan_event WHERE tenant_id=1 AND id=?",
                String.class, ingested.id())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT campaign_id FROM crm_youzan_event WHERE tenant_id=1 AND id=?",
                Long.class, ingested.id())).isNull();

        YouzanOrderEvent fullDetail = new YouzanOrderEvent(placeholder.eventId(), "ORDER_PAID", shop, ord4,
                extCust1, "TR-C4", "PAID", 100L, t0, t0.plusSeconds(30), "REGULAR", 303L, 1L, "PLATFORM", null,
                List.of(new YouzanOrderLine("YZ-SKU-MISSING", 1)));
        youzanEventService.replacePayloadAndProcess(1L, ingested.id(), fullDetail, json(fullDetail));

        assertThat(queryString("SELECT external_customer_id FROM crm_youzan_event WHERE tenant_id=1 AND id=?",
                ingested.id())).isEqualTo(extCust1);
        assertThat(queryLong("SELECT campaign_id FROM crm_youzan_event WHERE tenant_id=1 AND id=?",
                ingested.id())).isEqualTo(303L);
        assertThat(queryLong("SELECT latest_event_id FROM crm_youzan_order_latest "
                + "WHERE tenant_id=1 AND shop_id=? AND external_order_no=?", shop, ord4)).isEqualTo(ingested.id());
        assertThat(youzanRepository.countScopedCampaignOrders(1L, shop,
                new CustomerScopeSupport.ScopeQuery("SELF", List.of(), m1Id))).isEqualTo(2L);
    }

    private long insertRawAt(long tenantId, String shop, YouzanOrderEvent event, Instant receivedAtUtc) throws Exception {
        String raw = json(event);
        String receivedAtSql = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneOffset.UTC).format(receivedAtUtc);
        String extCustomerId = event.externalCustomerId() == null || event.externalCustomerId().isBlank()
                ? null : event.externalCustomerId().strip();
        Long campaignId = event.campaignId() != null && event.campaignId() > 0 ? event.campaignId() : null;
        jdbcTemplate.update("INSERT INTO crm_youzan_event (tenant_id,shop_id,event_id,event_type,external_order_no,"
                        + "external_customer_id,campaign_id,payload_masked_json,payload_cipher,signature_valid,"
                        + "source_system,process_status,process_attempts,last_error,received_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,1,'PULL','MAPPING_FAILED',1,'err',?)",
                tenantId, shop, event.eventId(), event.eventType(), event.externalOrderNo(), extCustomerId, campaignId,
                raw, cipher.encrypt(raw), receivedAtSql);
        long id = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=? AND shop_id=? AND event_id=?",
                tenantId, shop, event.eventId());
        if (event.externalOrderNo() != null && !event.externalOrderNo().isBlank()) {
            jdbcTemplate.update("INSERT INTO crm_youzan_order_latest "
                            + "(tenant_id,shop_id,external_order_no,latest_event_id) VALUES (?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE latest_event_id=GREATEST(latest_event_id,VALUES(latest_event_id))",
                    tenantId, shop, event.externalOrderNo(), id);
        }
        return id;
    }

    private void seedSkuAndCustomer(String shop, String externalCustomer, boolean linked) {
        seedSku(shop);
        if (linked) {
            linkCustomer(shop, externalCustomer, 1010L);
        }
    }

    private void seedSku(String shop) {
        jdbcTemplate.update("INSERT INTO crm_youzan_sku_map (tenant_id,shop_id,external_sku_id,crm_sku_id,status) "
                + "VALUES (1,?,'YZ-SKU-301',301,'ACTIVE')", shop);
    }

    private void linkCustomer(String shop, String externalCustomer, long customerId) {
        jdbcTemplate.update("INSERT INTO crm_youzan_customer_link (tenant_id,shop_id,external_customer_id,"
                        + "customer_id,link_type,confidence,reason,created_by) VALUES (1,?,?,?,'MANUAL','HIGH',?,1)",
                shop, externalCustomer, customerId, "集成测试虚构客户映射");
    }

    private long insertCustomer(long ownerStaffId, long teamId, String customerNo) {
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,?,?,?,'有赞测试客户')", ownerStaffId, teamId, customerNo);
        return queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?", customerNo);
    }

    private YouzanOrderEvent event(String shop, String eventId, String externalOrder, String externalCustomer,
                                   String tradeNo, String sku, long amountFen, Instant updatedAt) {
        return new YouzanOrderEvent(eventId, "ORDER_PAID", shop, externalOrder, externalCustomer, tradeNo,
                "PAID", amountFen, updatedAt, updatedAt, "REGULAR", 1L, 1L,
                "PLATFORM", LocalDate.parse("2027-12-31"), List.of(new YouzanOrderLine(sku, 1)));
    }

    private long insertRaw(long tenantId, String shop, YouzanOrderEvent event, String status, String error)
            throws Exception {
        String raw = json(event);
        String extCustomerId = event.externalCustomerId() == null || event.externalCustomerId().isBlank()
                ? null : event.externalCustomerId().strip();
        Long campaignId = event.campaignId() != null && event.campaignId() > 0 ? event.campaignId() : null;
        jdbcTemplate.update("INSERT INTO crm_youzan_event (tenant_id,shop_id,event_id,event_type,external_order_no,"
                        + "external_customer_id,campaign_id,payload_masked_json,payload_cipher,signature_valid,"
                        + "source_system,process_status,process_attempts,last_error,received_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,1,'PULL',?,1,?,NOW(3))",
                tenantId, shop, event.eventId(), event.eventType(), event.externalOrderNo(), extCustomerId, campaignId,
                raw, cipher.encrypt(raw), status, error);
        long id = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=? AND shop_id=? AND event_id=?",
                tenantId, shop, event.eventId());
        if (event.externalOrderNo() != null && !event.externalOrderNo().isBlank()) {
            jdbcTemplate.update("INSERT INTO crm_youzan_order_latest "
                            + "(tenant_id,shop_id,external_order_no,latest_event_id) VALUES (?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE latest_event_id=GREATEST(latest_event_id,VALUES(latest_event_id))",
                    tenantId, shop, event.externalOrderNo(), id);
        }
        return id;
    }

    private void postWebhook(String raw) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        mockMvc.perform(post("/api/v1/webhooks/youzan").contentType(MediaType.APPLICATION_JSON).content(raw)
                        .header("X-Youzan-Mock-Signature", adapter.signMock(raw, timestamp))
                        .header("X-Youzan-Mock-Timestamp", timestamp))
                .andExpect(status().isAccepted());
    }

    private long awaitEvent(String shop, String eventId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Map<String, Object> latest = null;
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,process_status,last_error "
                    + "FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?", shop, eventId);
            if (rows.size() == 1) {
                latest = rows.get(0);
                String actual = (String) latest.get("process_status");
                if (expected.equals(actual)) {
                    return ((Number) latest.get("id")).longValue();
                }
                if (!"RECEIVED".equals(actual)) {
                    assertThat(actual).as("有赞查询测试事件 %s；lastError=%s", eventId, latest.get("last_error"))
                            .isEqualTo(expected);
                }
            }
            Thread.sleep(50);
        }
        assertThat(latest).as("等待有赞查询测试事件 %s 进入 %s", eventId, expected).isNotNull();
        assertThat(latest.get("process_status")).isEqualTo(expected);
        return ((Number) latest.get("id")).longValue();
    }

    private JsonNode orders(AuthCookies actor, String shop, int page, int size, String spoofedTenant) throws Exception {
        MockHttpServletRequestBuilder request = actor.withAuth(get("/api/v1/integrations/youzan/orders")
                .param("shopId", shop).param("page", Integer.toString(page)).param("size", Integer.toString(size)));
        if (spoofedTenant != null) {
            request.header("X-Tenant-Id", spoofedTenant);
        }
        return data(request);
    }

    private JsonNode item(JsonNode page, String externalOrderNo) {
        for (JsonNode item : page.at("/items")) {
            if (externalOrderNo.equals(item.at("/externalOrderNo").asText())) {
                return item;
            }
        }
        throw new AssertionError("分页缺少外部订单 " + externalOrderNo + ": " + page);
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
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

    private String shop(String prefix) {
        return "IT-YZ-Q-" + prefix + "-" + suffix();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
