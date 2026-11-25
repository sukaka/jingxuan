package com.whale.crm.customer;

import com.whale.crm.customer.api.dto.CreateMergeCaseRequest;
import com.whale.crm.customer.api.dto.MergeCandidateItemResponse;
import com.whale.crm.customer.api.dto.MergeReversalActionRequest;
import com.whale.crm.customer.api.dto.MergeReversalResponse;
import com.whale.crm.customer.application.MergeApplicationService;
import com.whale.crm.customer.application.MergeReversalApplicationService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.api.dto.CreateOrderRequest;
import com.whale.crm.trading.api.dto.OrderItemRequest;
import com.whale.crm.trading.application.OrderApplicationService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C02 查重合并：同手机号两客户 → 扫描生成 SUGGESTED 候选（幂等）；未确认不迁移任何关联；
 * 确认后同事务迁移 identity/tag/followup/task、从客户 deleted=1；状态机与跨范围防护。
 */
class CustomerMergeApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MergeApplicationService mergeService;
    @Autowired
    private MergeReversalApplicationService mergeReversalService;
    @Autowired
    private OrderApplicationService orderService;
    @Autowired
    private CurrentUserService currentUserService;
    @Autowired
    private DataSource dataSource;

    @Test
    void c02_sameMobileProducesCandidate_confirmMigratesAndArchives() throws Exception {
        AuthCookies admin = login("admin");

        long primary = createCustomer(admin, "ITC02-主客户", "13900001111");
        long secondary = createCustomer(admin, "ITC02-从客户", "13900001111");

        // 从客户挂身份/标签/跟进/任务
        jdbcTemplate.update(
                "INSERT INTO crm_customer_identity (tenant_id, customer_id, identity_type, source_app, external_id, status)"
                        + " VALUES (1, ?, 'WECOM', 'corp-it', 'wx-c02-001', 'LINKED')", secondary);
        mockMvc.perform(admin.withAuth(put("/api/v1/customers/" + primary + "/tags"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tagCodes\":[\"VIP\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.withAuth(put("/api/v1/customers/" + secondary + "/tags"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tagCodes\":[\"VIP\",\"RISK\"]}"))
                .andExpect(status().isOk());
        long followupId = createFollowup(admin, secondary);
        long taskId = createTask(admin, secondary);

        // 无 customer:merge 权限的管家不能扫描/查看候选
        AuthCookies manager1 = login("manager1");
        mockMvc.perform(manager1.withAuth(post("/api/v1/customer-merge-candidates/scan")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customer-merge-candidates").cookie(manager1.cookies()))
                .andExpect(status().isForbidden());

        // 扫描生成候选；证据只含脱敏信息
        String scan = mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-candidates/scan")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var scanNode = objectMapper.readTree(scan).at("/data");
        assertThat(scanNode.get("created").asInt()).isGreaterThanOrEqualTo(1);
        long caseId = -1;
        for (var item : scanNode.get("items")) {
            if (item.get("primaryCustomerId").asLong() == primary
                    && item.get("secondaryCustomerId").asLong() == secondary) {
                caseId = item.get("id").asLong();
                assertThat(item.get("status").asText()).isEqualTo("SUGGESTED");
                assertThat(item.get("matchEvidence").toString()).contains("MOBILE_HASH").contains("139****1111");
                assertThat(item.get("matchEvidence").toString()).doesNotContain("13900001111");
            }
        }
        assertThat(caseId).as("扫描应生成 (primary,secondary) 候选").isPositive();

        // 重复扫描幂等：不再生成同主从候选
        String rescan = mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-candidates/scan")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (var item : objectMapper.readTree(rescan).at("/data/items")) {
            assertThat(item.get("secondaryCustomerId").asLong()).isNotEqualTo(secondary);
        }

        // 未确认：任何关联不迁移、从客户不归档
        assertThat(jdbcTemplate.queryForObject(
                "SELECT customer_id FROM crm_customer_identity WHERE external_id='wx-c02-001'",
                Long.class)).isEqualTo(secondary);
        assertThat(jdbcTemplate.queryForObject("SELECT customer_id FROM crm_followup WHERE id=?",
                Long.class, followupId)).isEqualTo(secondary);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id=?",
                Integer.class, secondary)).isZero();

        // 分页列表能查到该候选
        mockMvc.perform(get("/api/v1/customer-merge-candidates?status=SUGGESTED").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // SUGGESTED 不能直接确认；先进入人工审核，再确认合并
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"跳过审核\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/review"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEWING"));
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"同手机号人工确认\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // 迁移断言：identity/tag/followup/task 归主客户；VIP 标签去重不冲突；从客户 deleted=1
        assertThat(jdbcTemplate.queryForObject(
                "SELECT customer_id FROM crm_customer_identity WHERE external_id='wx-c02-001'",
                Long.class)).isEqualTo(primary);
        assertThat(jdbcTemplate.queryForObject("SELECT customer_id FROM crm_followup WHERE id=?",
                Long.class, followupId)).isEqualTo(primary);
        assertThat(jdbcTemplate.queryForObject("SELECT customer_id FROM crm_task WHERE id=?",
                Long.class, taskId)).isEqualTo(primary);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_tag WHERE customer_id=? AND tag_code='VIP'",
                Integer.class, primary)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_tag WHERE customer_id=? AND tag_code='RISK'",
                Integer.class, primary)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_tag WHERE customer_id=?", Integer.class, secondary)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id=?",
                Integer.class, secondary)).isEqualTo(1);

        // 从客户对外 404（软归档后不可见）
        mockMvc.perform(get("/api/v1/customers/" + secondary).cookie(admin.cookies()))
                .andExpect(status().isNotFound());
        // 主客户详情带迁移后的身份（掩码）与标签
        mockMvc.perform(get("/api/v1/customers/" + primary).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagCodes.length()").value(2))
                .andExpect(jsonPath("$.data.identities[0].identityType").value("WECOM"))
                .andExpect(jsonPath("$.data.identities[0].externalIdMasked").value("w***1"));

        // 终态不可再流转（当前 version=2）
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2}"))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2,\"reason\":\"已确认\"}"))
                .andExpect(status().isConflict());

        // 审计留痕
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE action_code='MERGE_CASE_CONFIRM' AND target_id=?",
                Integer.class, Long.toString(caseId));
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void reviewThenReject_stateMachine() throws Exception {
        AuthCookies admin = login("admin");
        long a = createCustomer(admin, "ITC02-拒A", "13900002221");
        long b = createCustomer(admin, "ITC02-拒B", "13900002222");

        String created = mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryCustomerId\":" + a + ",\"secondaryCustomerId\":" + b
                                + ",\"reason\":\"人工登记\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SUGGESTED"))
                .andReturn().getResponse().getContentAsString();
        long caseId = objectMapper.readTree(created).at("/data/id").asLong();

        // 重复登记同一对客户 → 409
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryCustomerId\":" + a + ",\"secondaryCustomerId\":" + b + "}"))
                .andExpect(status().isConflict());

        // review → REVIEWING；旧 version → 409
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/review"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEWING"));
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/review"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict());

        // reject → REJECTED，双方客户不受影响
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"并非同一人\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id=?",
                Integer.class, b)).isZero();
        mockMvc.perform(get("/api/v1/customers/" + b).cookie(admin.cookies()))
                .andExpect(status().isOk());
    }

    @Test
    void confirmRefreshesNextFollowupAtOnPrimaryAndClearsSecondaryWithoutBumpingPrimaryVersion() throws Exception {
        AuthCookies admin = login("admin");
        String prefix = "ITNFM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long primary = createCustomer(admin, prefix + "-主", "13988881111");
        long secondary = createCustomer(admin, prefix + "-从", "13988881111");

        createFollowupWithNextAt(admin, primary, "主客户跟进", "2026-07-01T00:00:00Z");
        createFollowupWithNextAt(admin, secondary, "从客户跟进", "2026-11-01T00:00:00Z");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_customer WHERE id=?", Long.class, primary))
                .isZero();

        String created = mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryCustomerId\":" + primary + ",\"secondaryCustomerId\":" + secondary
                                + ",\"reason\":\"P0-A4合并测试\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long caseId = objectMapper.readTree(created).at("/data/id").asLong();

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/review"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"确认合并\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_followup_at FROM crm_customer WHERE id=?", java.sql.Timestamp.class, primary)
                .toInstant()).isEqualTo(java.time.Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_followup_at FROM crm_customer WHERE id=?", java.sql.Timestamp.class, secondary))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_customer WHERE id=?", Long.class, primary))
                .isZero();
        mockMvc.perform(get("/api/v1/customers?keyword=" + prefix).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(primary))
                .andExpect(jsonPath("$.data.items[0].nextFollowupAt").value("2026-11-01T00:00:00Z"));
    }

    private void createFollowupWithNextAt(AuthCookies actor, long customerId, String summary, String nextFollowupAt)
            throws Exception {
        mockMvc.perform(actor.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", "it-merge-followup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"WECOM\",\"summary\":\""
                                + summary + "\",\"nextFollowupAt\":\"" + nextFollowupAt + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void confirm_outOfScopeCaseIs404() throws Exception {
        AuthCookies admin = login("admin");
        // 二组客户 → lead1（TEAM 1）不可见
        long a = createCustomer(admin, "ITC02-范A", "13900003331", 3);
        long b = createCustomer(admin, "ITC02-范B", "13900003332", 3);
        String created = mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryCustomerId\":" + a + ",\"secondaryCustomerId\":" + b + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long caseId = objectMapper.readTree(created).at("/data/id").asLong();

        AuthCookies lead1 = login("lead1");
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isNotFound());
        // lead1 列表也看不到该候选
        String list = mockMvc.perform(get("/api/v1/customer-merge-candidates?size=100")
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (var item : objectMapper.readTree(list).at("/data/items")) {
            assertThat(item.get("id").asLong()).isNotEqualTo(caseId);
        }
    }

    @Test
    void scanRespectsTeamDataScopeAndSkipsOtherTeamAndCrossTeamDuplicates() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies lead1 = login("lead1");

        long t1a = createCustomer(admin, "IT-SCAN-T1A", "13955551111", 2);
        long t1b = createCustomer(admin, "IT-SCAN-T1B", "13955551111", 2);
        long t2a = createCustomer(admin, "IT-SCAN-T2A", "13955552222", 3);
        long t2b = createCustomer(admin, "IT-SCAN-T2B", "13955552222", 3);
        long cross1 = createCustomer(admin, "IT-SCAN-X1", "13955553333", 2);
        long cross2 = createCustomer(admin, "IT-SCAN-X2", "13955553333", 3);

        long t2Id1 = createCustomer(admin, "IT-SCAN-ID-T2A", "13955554441", 3);
        long t2Id2 = createCustomer(admin, "IT-SCAN-ID-T2B", "13955554442", 3);
        long crossId1 = createCustomer(admin, "IT-SCAN-ID-X1", "13955555551", 2);
        long crossId2 = createCustomer(admin, "IT-SCAN-ID-X2", "13955555552", 3);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String t2Ext = "wx-scan-t2-" + suffix;
        String crossExt = "wx-scan-cross-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO crm_customer_identity (tenant_id,customer_id,identity_type,source_app,external_id,status) "
                        + "VALUES (1,?,'WECOM','corp-scan-a',?,'LINKED'),"
                        + "(1,?,'WECOM','corp-scan-b',?,'LINKED'),"
                        + "(1,?,'WECOM','corp-scan-a',?,'LINKED'),"
                        + "(1,?,'WECOM','corp-scan-b',?,'LINKED')",
                t2Id1, t2Ext, t2Id2, t2Ext, crossId1, crossExt, crossId2, crossExt);

        String scanBody = mockMvc.perform(lead1.withAuth(post("/api/v1/customer-merge-candidates/scan")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var items = objectMapper.readTree(scanBody).at("/data/items");
        List<Long> returnedPairs = new ArrayList<>();
        for (var item : items) {
            returnedPairs.add(item.get("primaryCustomerId").asLong());
            returnedPairs.add(item.get("secondaryCustomerId").asLong());
        }
        assertThat(returnedPairs).contains(t1a, t1b);
        assertThat(returnedPairs).doesNotContain(t2a, t2b, cross1, cross2, t2Id1, t2Id2, crossId1, crossId2);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_merge_case WHERE tenant_id=1 AND primary_customer_id IN (?,?,?,?)",
                Integer.class, t2a, cross1, t2Id1, crossId1)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_merge_case WHERE tenant_id=1 AND primary_customer_id=? AND secondary_customer_id=?",
                Integer.class, t1a, t1b)).isEqualTo(1);
    }

    @Test
    void concurrentMergeConfirmBurstDoesNotStarveHikariPool() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(16);

        AuthCookies admin = login("admin");
        CurrentUser adminUser = currentUserService.loadById(1).orElseThrow();
        int burstCount = 20;
        List<long[]> pairs = new ArrayList<>(burstCount);
        for (int i = 0; i < burstCount; i++) {
            long primary = createCustomer(admin, "IT-POOL-主-" + i, "1391000" + String.format("%04d", i));
            long secondary = createCustomer(admin, "IT-POOL-从-" + i, "1392000" + String.format("%04d", i));
            long orderId = orderService.create(1L, adminUser, new CreateOrderRequest(
                    secondary, "PLATFORM", "REGULAR", 1L, 1L, 181100L,
                    LocalDate.of(2027, 12, 31), List.of(new OrderItemRequest(301L, 1)))).order().order().id();
            long caseId = mergeService.createCase(1L, adminUser,
                    new CreateMergeCaseRequest(primary, secondary, "连接池并发压测-" + i)).id();
            mergeService.review(1L, adminUser, caseId, 0L, "连接池并发压测复核-" + i);
            pairs.add(new long[]{caseId, primary, secondary, orderId});
        }

        CountDownLatch ready = new CountDownLatch(burstCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MergeCandidateItemResponse>> futures = new ArrayList<>(burstCount);
        try (var executor = Executors.newFixedThreadPool(burstCount)) {
            for (long[] pair : pairs) {
                long caseId = pair[0];
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("pool burst start latch timed out");
                    }
                    return mergeService.confirm(1L, adminUser, caseId, 1L, "并发连接池压测");
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<MergeCandidateItemResponse> future : futures) {
                assertThat(future.get(25, TimeUnit.SECONDS).status()).isEqualTo("CONFIRMED");
            }
        }

        for (long[] pair : pairs) {
            long caseId = pair[0];
            long primary = pair[1];
            long secondary = pair[2];
            long orderId = pair[3];
            assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id=?",
                    Integer.class, secondary)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT customer_id FROM crm_order WHERE id=?",
                    Long.class, orderId)).isEqualTo(primary);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM crm_merge_resource_snapshot WHERE merge_case_id=? AND resource_type='ORDER' AND resource_key=?",
                    Integer.class, caseId, Long.toString(orderId))).isEqualTo(1);
        }
        if (hikari.getHikariPoolMXBean() != null) {
            assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(hikari.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
        }
    }

    @Test
    void concurrentMergeReversalApproveAndMergeConfirmSerializeWithoutDeadlock() throws Exception {
        AuthCookies admin = login("admin");
        CurrentUser adminUser = currentUserService.loadById(1).orElseThrow();
        for (int round = 0; round < 10; round++) {
            long primary = createCustomer(admin, "IT-MR-主-" + round, "1393000" + String.format("%04d", round));
            long secondary = createCustomer(admin, "IT-MR-从-" + round, "1394000" + String.format("%04d", round));
            long orderId = orderService.create(1L, adminUser, new CreateOrderRequest(
                    secondary, "PLATFORM", "REGULAR", 1L, 1L, 181100L,
                    LocalDate.of(2027, 12, 31), List.of(new OrderItemRequest(301L, 1)))).order().order().id();
            long caseId = mergeService.createCase(1L, adminUser,
                    new CreateMergeCaseRequest(primary, secondary, "并发更正合并-" + round)).id();
            mergeService.review(1L, adminUser, caseId, 0L, "并发更正合并复核-" + round);
            mergeService.confirm(1L, adminUser, caseId, 1L, "并发更正合并确认-" + round);
            long reversalId = mergeReversalService.request(adminUser, caseId,
                    new MergeReversalActionRequest("并发更正申请-" + round, null),
                    "it-mr-req-" + round + "-" + UUID.randomUUID()).id();

            String approveKey = "it-mr-app-" + round + "-" + UUID.randomUUID();
            MergeReversalActionRequest approveReq = new MergeReversalActionRequest("并发更正审批-" + round, 0L);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Outcome<MergeReversalResponse> approveOutcome;
            Outcome<MergeCandidateItemResponse> confirmOutcome;
            String confirmReason = "并发重复确认-" + round;
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<Outcome<MergeReversalResponse>> approveFuture = executor.submit(() ->
                        runWithLatch(ready, start, () ->
                                mergeReversalService.approve(adminUser, reversalId, approveReq, approveKey)));
                Future<Outcome<MergeCandidateItemResponse>> confirmFuture = executor.submit(() ->
                        runWithLatch(ready, start, () ->
                                mergeService.confirm(1L, adminUser, caseId, 2L, confirmReason)));
                assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                approveOutcome = approveFuture.get(25, TimeUnit.SECONDS);
                confirmOutcome = confirmFuture.get(25, TimeUnit.SECONDS);
            }

            assertThat(approveOutcome.error()).isNull();
            assertThat(approveOutcome.value().status()).isEqualTo("EXECUTED");
            assertThat(confirmOutcome.error())
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_merge_reversal WHERE id=?",
                    String.class, reversalId)).isEqualTo("EXECUTED");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_merge_case WHERE id=?",
                    String.class, caseId)).isEqualTo("REVERSED");
            assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id=?",
                    Integer.class, secondary)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT customer_id FROM crm_order WHERE id=?",
                    Long.class, orderId)).isEqualTo(secondary);
        }
    }

    private <T> Outcome<T> runWithLatch(CountDownLatch ready, CountDownLatch start, Callable<T> action) {
        ready.countDown();
        try {
            if (!start.await(15, TimeUnit.SECONDS)) {
                return new Outcome<>(null, new IllegalStateException("start latch timed out"));
            }
            return new Outcome<>(action.call(), null);
        } catch (Throwable ex) {
            return new Outcome<>(null, ex);
        }
    }

    private record Outcome<T>(T value, Throwable error) {
    }

    private long createCustomer(AuthCookies actor, String nickname, String mobile) throws Exception {
        return createCustomer(actor, nickname, mobile, 2);
    }

    private long createCustomer(AuthCookies actor, String nickname, String mobile, long ownerId) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\",\"mobile\":\"" + mobile
                                + "\",\"ownerStaffId\":" + ownerId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private long createFollowup(AuthCookies actor, long customerId) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", "it-merge-followup-" + java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"WECOM\","
                                + "\"summary\":\"ITC02 跟进\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private long createTask(AuthCookies actor, long customerId) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"taskType\":\"FOLLOWUP\","
                                + "\"title\":\"ITC02 任务\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }
}
