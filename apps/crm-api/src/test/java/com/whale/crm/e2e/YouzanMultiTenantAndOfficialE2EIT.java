package com.whale.crm.e2e;

import com.whale.crm.integration.youzan.adapter.RealYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEnvelope;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanOrderLine;
import com.whale.crm.integration.youzan.adapter.YouzanPullPage;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.application.YouzanEventService;
import com.whale.crm.integration.youzan.application.YouzanOfficialMessageService;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanOrderMap;
import com.whale.crm.integration.youzan.domain.YouzanSkuMapping;
import com.whale.crm.integration.youzan.domain.YouzanSyncCursor;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import com.whale.crm.integration.youzan.infrastructure.MybatisYouzanRepository;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F11 有赞多租户写隔离与官方/拉取同秒 ORDER_PAID 端到端闭环：
 * - C-02：清空 SecurityContextHolder (TenantContext == 1L) 时，显式传入 tenantId=2L 调用 MybatisYouzanRepository
 *   全部读写改方法，确认仅写入租户2且租户1为0；并跑通租户2 YouzanEventService.ingest + process 全链路
 * - H-BIZ-06：PULL sync (POST /api/v1/integrations/youzan/sync) 与官方回调 (POST /api/v1/webhooks/youzan/official)
 *   均正确处理同秒 WAIT_PAY -> ORDER_PAID 并仅授予一次权益
 */
@SpringBootTest(properties = {
        "app.outbox.relay.enabled=false",
        "app.exports.worker.enabled=false"
})
class YouzanMultiTenantAndOfficialE2EIT extends AbstractE2EIT {

    @Autowired
    private MybatisYouzanRepository youzanRepository;

    @Autowired
    private YouzanEventService youzanEventService;

    @Autowired
    private YouzanOfficialMessageService officialMessageService;

    @Autowired
    private AesGcmCipher cipher;

    @MockitoBean
    private RealYouzanAdapter realAdapter;

    @Test
    void repositoryMethodsAndYouzanEventServicePersistStrictlyInTenantTwoWithClearedSecurityContext()
            throws Exception {
        SecurityContextHolder.clearContext();
        assertThat(TenantContext.currentTenantId()).isEqualTo(1L);

        String suffix = shortUuid("yz-t2");
        String shopId = "E2E-YZ-T2-SHOP-" + suffix;
        String extOrderNo = "E2E-YZ-T2-ORD-" + suffix;
        String eventId = "E2E-YZ-T2-EVT-" + suffix;
        String extCustId = "E2E-YZ-T2-CUST-" + suffix;
        String extSkuId = "E2E-YZ-T2-SKU-" + suffix;

        // 1. Direct MybatisYouzanRepository write/update/query with explicit tenantId=2L
        Instant t0 = Instant.parse("2026-10-25T10:00:00Z");
        youzanRepository.insertEvent(2L, new YouzanEvent(null, shopId, eventId, "WAIT_PAY", extOrderNo,
                "{}", "cipher-1", true, "WEBHOOK", "RECEIVED", 0, null, null, t0, null, null,
                "{}", "p-cipher-1", 0L));
        YouzanEvent persistedEvent = youzanRepository.findEventByKey(2L, shopId, eventId).orElseThrow();
        assertThat(youzanRepository.findEventByKey(1L, shopId, eventId)).isEmpty();

        assertThat(youzanRepository.updateEventPayload(2L, persistedEvent.id(), persistedEvent.version(),
                extOrderNo, extCustId, 123L, "ORDER_PAID", "{\"k\":1}", "cipher-2", "{\"k\":1}", "p-cipher-2"))
                .isEqualTo(1);
        YouzanEvent updatedPayload = youzanRepository.findEvent(2L, persistedEvent.id()).orElseThrow();
        assertThat(updatedPayload.eventType()).isEqualTo("ORDER_PAID");
        assertThat(updatedPayload.externalCustomerId()).isEqualTo(extCustId);
        assertThat(updatedPayload.campaignId()).isEqualTo(123L);
        assertThat(youzanRepository.updateEventStatus(2L, updatedPayload.id(), updatedPayload.version(),
                "RETRY", "transient", t0.plusSeconds(60), null, null)).isEqualTo(1);

        youzanRepository.insertOrderMap(2L, new YouzanOrderMap(null, shopId, extOrderNo, 777001L, t0, t0, 0L));
        YouzanOrderMap orderMap = youzanRepository.findOrderMap(2L, shopId, extOrderNo).orElseThrow();
        assertThat(youzanRepository.findOrderMap(1L, shopId, extOrderNo)).isEmpty();
        assertThat(youzanRepository.updateOrderMapTime(2L, orderMap.id(), orderMap.version(),
                t0.plusSeconds(10))).isEqualTo(1);

        youzanRepository.insertSkuMapping(2L, new YouzanSkuMapping(null, shopId, extSkuId, 301L,
                "ACTIVE", t0, 0L));
        YouzanSkuMapping skuMap = youzanRepository.findSkuMapping(2L, shopId, extSkuId).orElseThrow();
        assertThat(youzanRepository.findSkuMapping(1L, shopId, extSkuId)).isEmpty();
        assertThat(youzanRepository.updateSkuMapping(2L, skuMap.id(), skuMap.version(), 301L, "INACTIVE"))
                .isEqualTo(1);

        youzanRepository.insertCustomerLink(2L, new YouzanCustomerLink(null, shopId, extCustId, 888001L,
                "MANUAL", "HIGH", "initial", 1L, t0, 0L));
        YouzanCustomerLink custLink = youzanRepository.findCustomerLink(2L, shopId, extCustId).orElseThrow();
        assertThat(youzanRepository.findCustomerLink(1L, shopId, extCustId)).isEmpty();
        assertThat(youzanRepository.updateCustomerLink(2L, custLink.id(), custLink.version(), 888002L, "updated"))
                .isEqualTo(1);

        youzanRepository.insertIssue(2L, new YouzanSyncIssue(null, persistedEvent.id(), "RETRY", "OPEN",
                "masked", 1, "err", t0.plusSeconds(60), t0, null, null, 0L));
        YouzanSyncIssue issue = youzanRepository.findIssueByEventAndType(2L, persistedEvent.id(), "RETRY")
                .orElseThrow();
        assertThat(youzanRepository.findIssue(1L, issue.id())).isEmpty();
        assertThat(youzanRepository.updateIssueStatus(2L, issue.id(), issue.version(), "RESOLVED", 1,
                null, null, t0.plusSeconds(5), 1L)).isEqualTo(1);

        youzanRepository.insertCursor(2L, new YouzanSyncCursor(null, shopId, "c1", t0, t0, t0, 0L));
        YouzanSyncCursor cursor = youzanRepository.findCursor(2L, shopId).orElseThrow();
        assertThat(youzanRepository.findCursor(1L, shopId)).isEmpty();
        assertThat(youzanRepository.updateCursor(2L, cursor.id(), cursor.version(), "c2", t0, t0.plusSeconds(30),
                t0.plusSeconds(30), t0.plusSeconds(20))).isEqualTo(1);

        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=?", shopId)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=?", shopId))
                .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_sku_map WHERE tenant_id=1 AND shop_id=?", shopId))
                .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=?", shopId))
                .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_sync_cursor WHERE tenant_id=1 AND shop_id=?", shopId))
                .isZero();

        // 2. Full YouzanEventService.ingest + process for tenantId=2L -> crm_order/payment/entitlement/outbox in tenant 2
        long t2AdminId = ensureTenantTwoAdminAndOrderDict();
        String procSuffix = shortUuid("t2proc");
        String procShop = "E2E-YZ-T2P-" + procSuffix;
        String procExtSku = "E2E-T2-SKU-" + procSuffix;
        String procExtCust = "E2E-T2-CUST-" + procSuffix;
        String procExtOrder = "E2E-T2-ORD-" + procSuffix;
        String procEventId = "E2E-T2-EVT-" + procSuffix;
        String procTradeNo = "E2E-T2-TRD-" + procSuffix;

        long t2SkuId = createTenantTwoActiveFixedSku(procSuffix);
        long t2CustomerId = createTenantTwoCustomer(t2AdminId, procSuffix);
        youzanRepository.insertSkuMapping(2L, new YouzanSkuMapping(null, procShop, procExtSku, t2SkuId,
                "ACTIVE", Instant.now(), 0L));
        youzanRepository.insertCustomerLink(2L, new YouzanCustomerLink(null, procShop, procExtCust, t2CustomerId,
                "MANUAL", "HIGH", "E2E租户2客户绑定", t2AdminId, Instant.now(), 0L));

        SecurityContextHolder.clearContext();
        YouzanOrderEvent t2Paid = new YouzanOrderEvent(procEventId, "ORDER_PAID", procShop, procExtOrder,
                procExtCust, procTradeNo, "PAID", 12000L, Instant.parse("2026-10-25T11:00:00Z"),
                Instant.parse("2026-10-25T11:00:00Z"), "REGULAR", null, null, "PLATFORM",
                LocalDate.now().plusYears(1), List.of(new YouzanOrderLine(procExtSku, 1)));
        YouzanEventResponse ingested = youzanEventService.ingest(2L, t2Paid, "WEBHOOK", json(t2Paid));
        YouzanEventResponse processed = youzanEventService.process(2L, ingested.id());
        assertThat(processed.processStatus()).as("lastError=%s", processed.lastError()).isEqualTo("PROCESSED");
        long t2OrderId = processed.orderId();
        assertThat(queryLong("SELECT tenant_id FROM crm_order WHERE id=?", t2OrderId)).isEqualTo(2L);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_payment_txn WHERE tenant_id=2 AND order_id=?", t2OrderId))
                .isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement WHERE tenant_id=2 AND customer_id=?", t2CustomerId))
                .isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=2 AND event_id=?",
                "order.paid.v1:" + t2OrderId)).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_order WHERE tenant_id=1 AND id=?", t2OrderId)).isZero();
    }

    @Test
    void sameSecondWaitPayToOrderPaidViaPullSyncAndOfficialWebhook() throws Exception {
        AuthCookies admin = login("admin");
        String suffix = shortUuid("yz-samesec");
        String shopId = "E2E-REAL-SHOP-" + suffix;
        String clientId = "E2E-CLIENT-" + suffix;
        String secret = "E2E-SECRET-" + suffix;

        jdbcTemplate.update("INSERT INTO crm_integration_runtime_config "
                + "(tenant_id,youzan_real_enabled,wecom_archive_real_enabled,wecom_official_enabled) "
                + "VALUES (1,1,0,0) ON DUPLICATE KEY UPDATE youzan_real_enabled=1");
        try {
            jdbcTemplate.update("INSERT INTO crm_youzan_shop_config "
                            + "(tenant_id,shop_id,shop_name,client_id,client_secret_cipher,connection_status,sync_enabled) "
                            + "VALUES (1,?,?,?,?,'CONNECTED',1)",
                    shopId, "E2E有赞官方店", clientId, cipher.encrypt(secret));
            jdbcTemplate.update("INSERT INTO crm_youzan_sku_map (tenant_id,shop_id,external_sku_id,crm_sku_id,status) "
                    + "VALUES (1,?,'YZ-SKU-301',301,'ACTIVE')", shopId);
            jdbcTemplate.update("INSERT INTO crm_youzan_customer_link "
                    + "(tenant_id,shop_id,external_customer_id,customer_id,link_type,confidence,created_by) "
                    + "VALUES (1,?,'YZ-DEMO-CUSTOMER-1010',1010,'MANUAL','HIGH',1)", shopId);

            // 1. H-BIZ-06 via PULL POST /api/v1/integrations/youzan/sync
            Instant pullSameSecond = Instant.parse("2026-10-25T10:15:30Z");
            String pullOrderNo = "E2E-PULL-ORD-" + suffix;
            String pullTradeNo = "E2E-PULL-TRD-" + suffix;
            YouzanOrderEvent pullWait = new YouzanOrderEvent("E2E-PULL-WAIT-" + suffix, "WAIT_PAY", shopId,
                    pullOrderNo, "YZ-DEMO-CUSTOMER-1010", null, "WAIT_PAY", 181100L, null, pullSameSecond,
                    "REGULAR", null, null, "PLATFORM", LocalDate.now().plusYears(1),
                    List.of(new YouzanOrderLine("YZ-SKU-301", 1)));
            YouzanOrderEvent pullPaid = new YouzanOrderEvent("E2E-PULL-PAID-" + suffix, "ORDER_PAID", shopId,
                    pullOrderNo, "YZ-DEMO-CUSTOMER-1010", pullTradeNo, "PAID", 181100L, pullSameSecond,
                    pullSameSecond, "REGULAR", null, null, "PLATFORM", LocalDate.now().plusYears(1),
                    List.of(new YouzanOrderLine("YZ-SKU-301", 1)));
            when(realAdapter.pullOrders(eq(1L), eq(shopId), anyString(), anyInt()))
                    .thenAnswer(inv -> new YouzanPullPage(List.of(pullWait, pullPaid), inv.getArgument(2), false));

            mockMvc.perform(admin.withAuth(post("/api/v1/integrations/youzan/sync"))
                            .header("Idempotency-Key", uniqueKey("e2e-yz-pull"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("shopId", shopId, "limit", 10))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.received").value(2))
                    .andExpect(jsonPath("$.data.processed").value(2))
                    .andExpect(jsonPath("$.data.issues").value(0));

            long pullOrderId = queryLong("SELECT crm_order_id FROM crm_youzan_order_map "
                    + "WHERE tenant_id=1 AND shop_id=? AND external_order_no=?", shopId, pullOrderNo);
            assertThat(queryString("SELECT status FROM crm_order WHERE tenant_id=1 AND id=?", pullOrderId))
                    .isEqualTo("PAID");
            assertThat(queryInt("SELECT COUNT(*) FROM crm_payment_txn WHERE tenant_id=1 AND order_id=?", pullOrderId))
                    .isEqualTo(1);

            // 2. H-BIZ-06 via POST /api/v1/webhooks/youzan/official + YouzanOfficialMessageService.refreshAndProcess
            Instant offSameSecond = Instant.parse("2026-10-25T10:20:30Z");
            String offOrderNo = "E2E-OFF-ORD-" + suffix;
            String offTradeNo = "E2E-OFF-TRD-" + suffix;
            String waitMsgId = "e2e-off-wait-" + suffix;
            String paidMsgId = "e2e-off-paid-" + suffix;

            String waitBody = json(Map.of("msgId", waitMsgId, "kdtId", shopId, "msg", Map.of("tid", offOrderNo)));
            String paidBody = json(Map.of("msgId", paidMsgId, "kdtId", shopId, "msg", Map.of("tid", offOrderNo)));

            when(realAdapter.fetchOrder(1L, shopId, waitMsgId, offOrderNo))
                    .thenReturn(new YouzanOrderEnvelope(
                            new YouzanOrderEvent(waitMsgId, "WAIT_PAY", shopId, offOrderNo, "YZ-DEMO-CUSTOMER-1010",
                                    null, "WAIT_PAY", 181100L, null, offSameSecond, "REGULAR", null, null,
                                    "PLATFORM", LocalDate.now().plusYears(1),
                                    List.of(new YouzanOrderLine("YZ-SKU-301", 1))),
                            waitBody));
            when(realAdapter.fetchOrder(1L, shopId, paidMsgId, offOrderNo))
                    .thenReturn(new YouzanOrderEnvelope(
                            new YouzanOrderEvent(paidMsgId, "ORDER_PAID", shopId, offOrderNo, "YZ-DEMO-CUSTOMER-1010",
                                    offTradeNo, "PAID", 181100L, offSameSecond, offSameSecond, "REGULAR", null, null,
                                    "PLATFORM", LocalDate.now().plusYears(1),
                                    List.of(new YouzanOrderLine("YZ-SKU-301", 1))),
                            paidBody));

            mockMvc.perform(post("/api/v1/webhooks/youzan/official")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Client-Id", clientId)
                            .header("Event-Sign", md5HexUtf8(clientId + waitBody + secret))
                            .header("Event-Type", "trade_TradeCreate")
                            .content(waitBody.getBytes(StandardCharsets.UTF_8)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.msg").value("success"));

            long waitDbEventId = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?",
                    shopId, waitMsgId);
            officialMessageService.refreshAndProcess(1L, waitDbEventId);

            long offOrderId = queryLong("SELECT crm_order_id FROM crm_youzan_order_map "
                    + "WHERE tenant_id=1 AND shop_id=? AND external_order_no=?", shopId, offOrderNo);
            assertThat(queryString("SELECT status FROM crm_order WHERE tenant_id=1 AND id=?", offOrderId))
                    .isEqualTo("DRAFT");

            mockMvc.perform(post("/api/v1/webhooks/youzan/official")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Client-Id", clientId)
                            .header("Event-Sign", md5HexUtf8(clientId + paidBody + secret))
                            .header("Event-Type", "trade_TradePaid")
                            .content(paidBody.getBytes(StandardCharsets.UTF_8)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.msg").value("success"));

            long paidDbEventId = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?",
                    shopId, paidMsgId);
            officialMessageService.refreshAndProcess(1L, paidDbEventId);

            assertThat(queryString("SELECT status FROM crm_order WHERE tenant_id=1 AND id=?", offOrderId))
                    .isEqualTo("PAID");
            assertThat(queryInt("SELECT COUNT(*) FROM crm_payment_txn WHERE tenant_id=1 AND order_id=?", offOrderId))
                    .isEqualTo(1);
            assertThat(queryInt("SELECT COUNT(*) FROM crm_entitlement e JOIN crm_order_item oi "
                    + "ON oi.id=e.order_item_id WHERE e.tenant_id=1 AND oi.order_id=?", offOrderId)).isEqualTo(1);
        } finally {
            jdbcTemplate.update("DELETE FROM crm_integration_runtime_config WHERE tenant_id=1");
        }
    }

    private long ensureTenantTwoAdminAndOrderDict() {
        jdbcTemplate.update("INSERT IGNORE INTO sys_org (id,tenant_id,org_code,org_name,status) "
                + "VALUES (2,2,'E2E_ORG_2','二号租户组织','ACTIVE')");
        String adminUser = shortUuid("e2e_t2_admin");
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                        + "SELECT 2,2,?,'二租户有赞操作员',password_hash,'EMPLOYEE','ACTIVE' FROM sys_user WHERE id=1",
                adminUser);
        long userId = queryLong("SELECT id FROM sys_user WHERE tenant_id=2 AND username=?", adminUser);
        jdbcTemplate.update("INSERT IGNORE INTO sys_role (tenant_id,role_code,role_name,status) "
                + "VALUES (2,'ADMIN','系统管理员','ACTIVE')");
        long roleId = queryLong("SELECT id FROM sys_role WHERE tenant_id=2 AND role_code='ADMIN'");
        jdbcTemplate.update("INSERT IGNORE INTO sys_user_role (tenant_id,user_id,role_id) VALUES (2,?,?)",
                userId, roleId);
        jdbcTemplate.update("INSERT IGNORE INTO sys_data_scope (tenant_id,user_id,resource,scope_type) "
                + "VALUES (2,?,'customer','ALL')", userId);
        jdbcTemplate.update("INSERT IGNORE INTO sys_dictionary_type (tenant_id,dict_code,dict_name,status) "
                + "VALUES (2,'orderTypes','订单类型','ACTIVE')");
        long dictTypeId = queryLong("SELECT id FROM sys_dictionary_type WHERE tenant_id=2 AND dict_code='orderTypes'");
        jdbcTemplate.update("INSERT IGNORE INTO sys_dictionary_item "
                + "(tenant_id,dict_type_id,option_code,option_label,sort_no,status) "
                + "VALUES (2,?,'REGULAR','普通订单',1,'ACTIVE')", dictTypeId);
        return userId;
    }

    private long createTenantTwoActiveFixedSku(String suffix) {
        String code = "T2-P-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_institution (tenant_id,institution_no,full_name,status,settlement_type) "
                + "VALUES (2,?,'租户2合作机构','ACTIVE','MONTHLY')", code);
        long institutionId = queryLong("SELECT id FROM crm_institution WHERE tenant_id=2 AND institution_no=?", code);
        jdbcTemplate.update("INSERT INTO crm_project (tenant_id,project_code,project_name,list_price_fen,status) "
                + "VALUES (2,?,'租户2项目',12000,'ACTIVE')", code);
        long projectId = queryLong("SELECT id FROM crm_project WHERE tenant_id=2 AND project_code=?", code);
        jdbcTemplate.update("INSERT INTO crm_package (tenant_id,package_code,package_name,status) "
                + "VALUES (2,?,'租户2套餐','ACTIVE')", code);
        long pkgId = queryLong("SELECT id FROM crm_package WHERE tenant_id=2 AND package_code=?", code);
        jdbcTemplate.update("INSERT INTO crm_package_version (tenant_id,package_id,version_no,selection_mode,"
                + "choice_count,status) VALUES (2,?,1,'FIXED',0,'ACTIVE')", pkgId);
        long verId = queryLong("SELECT id FROM crm_package_version WHERE tenant_id=2 AND package_id=?", pkgId);
        jdbcTemplate.update("INSERT INTO crm_package_project (tenant_id,package_version_id,project_id,grant_count,"
                + "selection_weight,allocation_price_fen,option_group) VALUES (2,?,?,1,0,12000,'')", verId, projectId);
        jdbcTemplate.update("INSERT INTO crm_sku (tenant_id,sku_code,package_version_id,spec_name,sale_price_fen,status) "
                + "VALUES (2,?,?,'租户2规格',12000,'ACTIVE')", code, verId);
        long skuId = queryLong("SELECT id FROM crm_sku WHERE tenant_id=2 AND sku_code=?", code);
        jdbcTemplate.update("INSERT INTO crm_sku_institution (tenant_id,sku_id,institution_id,status) "
                + "VALUES (2,?,?,'ACTIVE')", skuId, institutionId);
        return skuId;
    }

    private long createTenantTwoCustomer(long ownerStaffId, String suffix) {
        String custNo = "T2-CUST-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,customer_no,nickname) "
                + "VALUES (2,?,?,'租户2有赞客户')", ownerStaffId, custNo);
        return queryLong("SELECT id FROM crm_customer WHERE tenant_id=2 AND customer_no=?", custNo);
    }

    private static String md5HexUtf8(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
