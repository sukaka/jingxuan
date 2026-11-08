package com.whale.crm.customer;

import com.whale.crm.customer.api.dto.PointsAdjustmentRequest;
import com.whale.crm.customer.application.CustomerApplicationService;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
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

/**
 * 积分账本：只追加流水同事务刷新 points_cached；余额不得为负；幂等键回放/冲突；权限与范围。
 */
class CustomerPointsApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomerApplicationService customerService;

    @Autowired
    private CurrentUserService currentUserService;

    @Test
    void adjust_appendsLedgerAndRefreshesCachedBalance() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies lead1 = login("lead1");

        long customerId = createCustomer(manager1, "IT-积分");

        // MANAGER 无 customer:points_adjust → 403
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":100,\"reasonCode\":\"MANUAL\"}"))
                .andExpect(status().isForbidden());

        // lead1（同组主管）调整 +100
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":100,\"reasonCode\":\"COMPENSATE\",\"reason\":\"服务补偿\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(100))
                .andExpect(jsonPath("$.data.ledger.length()").value(1))
                .andExpect(jsonPath("$.data.ledger[0].delta").value(100))
                .andExpect(jsonPath("$.data.ledger[0].sourceType").value("MANUAL_ADJUST"));

        // 负向调整 -30 → 70
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-30,\"reasonCode\":\"CORRECT\",\"reason\":\"录入更正\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(70));

        // 余额不得为负 → 409，且无新流水
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-100,\"reasonCode\":\"CORRECT\"}"))
                .andExpect(status().isConflict());

        // 缓存与账本一致；delta=0 拒绝
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pointsCached").value(70));
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(delta),0) FROM crm_points_ledger WHERE customer_id=?",
                Long.class, customerId);
        assertThat(sum).isEqualTo(70L);
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":0,\"reasonCode\":\"X\"}"))
                .andExpect(status().isBadRequest());

        // 审计含 before/after 余额
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE action_code='CUSTOMER_POINTS_ADJUST' AND target_id=?",
                Integer.class, Long.toString(customerId));
        assertThat(audits).isEqualTo(2);
    }

    @Test
    void adjust_withIdempotencyKey_replaysAndRejectsDifferentBody() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies lead1 = login("lead1");
        long customerId = createCustomer(manager1, "IT-积分幂等");

        String body = "{\"delta\":50,\"reasonCode\":\"WELCOME\"}";
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .header("Idempotency-Key", "it-pts-001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(50));

        // 回放同键同请求体 → 不再追加流水
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .header("Idempotency-Key", "it-pts-001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(50))
                .andExpect(jsonPath("$.data.ledger.length()").value(1));

        // 同键不同体 → 409
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .header("Idempotency-Key", "it-pts-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":60,\"reasonCode\":\"WELCOME\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void points_outOfScopeCustomerIs404() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        AuthCookies lead2 = login("lead2");
        long customerId = createCustomer(manager1, "IT-积分隔离");
        mockMvc.perform(get("/api/v1/customers/" + customerId + "/points").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0))
                .andExpect(jsonPath("$.data.ledger.length()").value(0));
        mockMvc.perform(get("/api/v1/customers/" + customerId + "/points").cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(lead2.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":10,\"reasonCode\":\"OUT_OF_SCOPE\"}"))
                .andExpect(status().isNotFound());
        Integer entries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_points_ledger WHERE tenant_id=1 AND customer_id=?",
                Integer.class, customerId);
        assertThat(entries).isZero();
    }

    @Test
    void points_otherTenantIsNotFoundAndDoesNotAppend() throws Exception {
        long customerId = createCustomer(login("manager1"), "IT-积分跨租户");
        var admin = currentUserService.loadById(1L).orElseThrow();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> customerService.points(2L, admin, customerId))
                .isInstanceOf(BusinessException.class)
                .hasMessage("客户不存在或不可见");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> customerService.adjustPoints(2L, admin, customerId,
                        new PointsAdjustmentRequest(10, "TENANT_ISOLATION", null), "it-pts-cross-tenant"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("客户不存在或不可见");
        Integer entries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_points_ledger WHERE tenant_id=2 AND customer_id=?",
                Integer.class, customerId);
        assertThat(entries).isZero();
    }

    @Test
    void concurrentDebits_onlyOneSucceedsAndNeverCreatesNegativeBalance() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies lead1 = login("lead1");
        long customerId = createCustomer(manager1, "IT-积分并发扣减");
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .header("Idempotency-Key", "it-pts-concurrent-seed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":10,\"reasonCode\":\"SEED\"}"))
                .andExpect(status().isOk());

        List<Integer> statuses = concurrentAdjustments(lead1, customerId, -10,
                "it-pts-concurrent-debit", false);

        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        mockMvc.perform(get("/api/v1/customers/" + customerId + "/points").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0))
                .andExpect(jsonPath("$.data.ledger.length()").value(2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(delta),0) FROM crm_points_ledger WHERE tenant_id=1 AND customer_id=?",
                Long.class, customerId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT points_cached FROM crm_customer WHERE tenant_id=1 AND id=?",
                Integer.class, customerId)).isZero();
    }

    @Test
    void concurrentSameIdempotencyKey_allRequestsReplaySingleLedgerEntry() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies lead1 = login("lead1");
        long customerId = createCustomer(manager1, "IT-积分并发幂等");

        List<Integer> statuses = concurrentAdjustments(lead1, customerId, 25,
                "it-pts-concurrent-replay", true);

        assertThat(statuses).containsOnly(200);
        mockMvc.perform(get("/api/v1/customers/" + customerId + "/points").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(25))
                .andExpect(jsonPath("$.data.ledger.length()").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT points_cached FROM crm_customer WHERE tenant_id=1 AND id=?",
                Integer.class, customerId)).isEqualTo(25);
    }

    private List<Integer> concurrentAdjustments(AuthCookies actor, long customerId, int delta,
                                                String keyPrefix, boolean sharedKey) throws Exception {
        int requestCount = 12;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < requestCount; i++) {
                int requestNumber = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    String idempotencyKey = sharedKey ? keyPrefix : keyPrefix + "-" + requestNumber;
                    return mockMvc.perform(actor.withAuth(
                                    post("/api/v1/customers/" + customerId + "/points-adjustments"))
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"delta\":" + delta + ",\"reasonCode\":\"CONCURRENT\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long createCustomer(AuthCookies actor, String nickname) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }
}
