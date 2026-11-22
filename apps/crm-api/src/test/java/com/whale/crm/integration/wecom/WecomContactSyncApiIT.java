package com.whale.crm.integration.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.integration.wecom.adapter.MockWecomAdapter;
import com.whale.crm.integration.wecom.application.WecomCallbackService;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.files.enabled=false", "app.outbox.relay.enabled=false",
        "app.scheduling.enabled=false", "app.wecom.official.callback-delay-ms=600000"})
class WecomContactSyncApiIT extends AbstractMysqlIT {
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WecomCallbackService callbacks;
    @Autowired WecomContactSyncRepository syncRepository;
    @Autowired PlatformTransactionManager transactionManager;
    private AuthCookies admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin");
        jdbcTemplate.update("DELETE FROM crm_wecom_callback_event WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_wecom_contact_sync_run WHERE tenant_id=1");
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) VALUES (1,?,'staff-2',2,1),(1,?,'staff-3',3,1) ON DUPLICATE KEY UPDATE enabled=1", MockWecomAdapter.CORP_ID, MockWecomAdapter.CORP_ID);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM crm_wecom_callback_event WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_wecom_contact_sync_run WHERE tenant_id=1");
    }

    @Test
    void fullSyncUpsertsOfficialFieldsAndPreservesManualCustomerLink() throws Exception {
        JsonNode run = sync("FULL");
        assertThat(run.at("/status").asText()).isEqualTo("SUCCESS");
        assertThat(run.at("/receivedCount").asInt()).isEqualTo(3);
        Map<String, Object> contact = jdbcTemplate.queryForMap("SELECT name,remark,follow_status,customer_id,consent_status FROM crm_wecom_contact WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1010' AND staff_user_id=2", MockWecomAdapter.CORP_ID);
        assertThat(contact.get("name")).isEqualTo("虚构企微客户甲");
        assertThat(contact.get("remark")).isEqualTo("人工备注甲");
        assertThat(contact.get("follow_status")).isEqualTo("ACTIVE");
        assertThat(((Number) contact.get("customer_id")).longValue()).isEqualTo(1010L);
        assertThat(contact.get("consent_status")).isEqualTo("GRANTED");
    }

    @Test
    void callbackRefreshAndDeleteAreIdempotentAndDoNotDeleteHistory() throws Exception {
        sync("FULL");
        String add = "<xml><CreateTime>1792990000</CreateTime><Event><![CDATA[change_external_contact]]></Event><ChangeType><![CDATA[add_external_contact]]></ChangeType><UserID><![CDATA[staff-2]]></UserID><ExternalUserID><![CDATA[WECOM-EXT-1010]]></ExternalUserID></xml>";
        callbacks.accept(add);
        callbacks.accept(add);
        callbacks.processPending();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_wecom_callback_event", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_callback_event", String.class)).isEqualTo("SUCCEEDED");

        String delete = add.replace("1792990000", "1792990001").replace("add_external_contact", "del_external_contact");
        callbacks.accept(delete);
        callbacks.processPending();
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT follow_status,customer_id FROM crm_wecom_contact WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1010' AND staff_user_id=2", MockWecomAdapter.CORP_ID);
        assertThat(row.get("follow_status")).isEqualTo("DELETED_BY_STAFF");
        assertThat(((Number) row.get("customer_id")).longValue()).isEqualTo(1010L);
    }

    @Test
    void reconcileWithoutEnabledStaffFailsWithoutDeactivatingExistingContacts() throws Exception {
        sync("FULL");
        jdbcTemplate.update("UPDATE crm_wecom_staff_account SET enabled=0 WHERE tenant_id=1 AND corp_id=?", MockWecomAdapter.CORP_ID);
        mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/contact-sync"))
                        .header("Idempotency-Key", "it-wecom-contact-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"RECONCILE\"}"))
                .andExpect(status().isUnprocessableEntity());
        Integer active = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_wecom_contact WHERE tenant_id=1 AND corp_id=? AND follow_status='ACTIVE'", Integer.class, MockWecomAdapter.CORP_ID);
        assertThat(active).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_contact_sync_run ORDER BY id DESC LIMIT 1", String.class)).isEqualTo("ERROR");
    }

    @Test
    void expiredRunningLeaseIsRecoveredAndGeneratedKeyMatchesPersistedRun() throws Exception {
        jdbcTemplate.update("INSERT INTO crm_wecom_contact_sync_run (tenant_id,corp_id,run_type,status,started_at,lease_until) "
                + "VALUES (1,?,'FULL','RUNNING',DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 10 MINUTE),DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 5 MINUTE))",
                MockWecomAdapter.CORP_ID);
        Long staleId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM crm_wecom_contact_sync_run WHERE tenant_id=1 AND corp_id=?",
                Long.class, MockWecomAdapter.CORP_ID);

        JsonNode run = sync("FULL");
        assertThat(run.at("/status").asText()).isEqualTo("SUCCESS");
        assertThat(run.at("/id").asLong()).isGreaterThan(staleId);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_contact_sync_run WHERE id=?",
                String.class, staleId)).isEqualTo("ERROR");

        // Active (unexpired) RUNNING lease still blocks concurrent FULL/RECONCILE
        long activeId = syncRepository.startRun(1L, MockWecomAdapter.CORP_ID, "FULL", 1L,
                Instant.now(), Instant.now().plusSeconds(120));
        assertThat(activeId).isGreaterThan(run.at("/id").asLong());
        assertThat(syncRepository.startRun(1L, MockWecomAdapter.CORP_ID, "RECONCILE", 1L,
                Instant.now(), Instant.now().plusSeconds(120))).isZero();

        // If external expiry flips status to ERROR mid-flight, finishRun returns 0 and transaction rolls back
        jdbcTemplate.update("UPDATE crm_wecom_contact_sync_run SET status='ERROR',finished_at=UTC_TIMESTAMP(3) WHERE id=?",
                activeId);
        new TransactionTemplate(transactionManager).executeWithoutResult(txStatus -> {
            jdbcTemplate.update("UPDATE crm_wecom_contact SET follow_status='DELETED_BY_STAFF' "
                    + "WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1010'",
                    MockWecomAdapter.CORP_ID);
            int updated = syncRepository.finishRun(1L, activeId, "SUCCESS", null, 1, 1, 1, null, Instant.now());
            assertThat(updated).isZero();
            txStatus.setRollbackOnly();
        });
        assertThat(jdbcTemplate.queryForObject("SELECT follow_status FROM crm_wecom_contact "
                + "WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1010' AND staff_user_id=2",
                String.class, MockWecomAdapter.CORP_ID)).isEqualTo("ACTIVE");
    }

    @Test
    void concurrentStartRunHasZeroDeadlocksForFullStaleReconcileAndCallback() throws Exception {
        int threads = 16;

        // (a) Concurrent FULL startRun: 1 winner, 15 zeros, 0 deadlocks, API returns 409 CONFLICT
        List<Long> fullIds = runConcurrentStartRuns(threads, "FULL", 1L);
        List<Long> fullWinners = fullIds.stream().filter(id -> id > 0).toList();
        assertThat(fullWinners).hasSize(1);
        assertThat(fullIds.stream().filter(id -> id == 0L).count()).isEqualTo(threads - 1);
        mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/contact-sync"))
                        .header("Idempotency-Key", "it-wecom-contact-conflict-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"FULL\"}"))
                .andExpect(status().isConflict());
        assertThat(syncRepository.finishRun(1L, fullWinners.getFirst(), "SUCCESS", null, 0, 0, 0,
                null, Instant.now())).isEqualTo(1);

        // (b) Concurrent stale-lease RECONCILE startRun: 1 winner, 15 zeros, 0 deadlocks, stale row expired
        jdbcTemplate.update("INSERT INTO crm_wecom_contact_sync_run (tenant_id,corp_id,run_type,status,started_at,lease_until) "
                + "VALUES (1,?,'FULL','RUNNING',DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 10 MINUTE),DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 5 MINUTE))",
                MockWecomAdapter.CORP_ID);
        Long staleId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM crm_wecom_contact_sync_run WHERE tenant_id=1 AND corp_id=?",
                Long.class, MockWecomAdapter.CORP_ID);
        List<Long> reconcileIds = runConcurrentStartRuns(threads, "RECONCILE", 1L);
        List<Long> reconcileWinners = reconcileIds.stream().filter(id -> id > 0).toList();
        assertThat(reconcileWinners).hasSize(1);
        assertThat(reconcileWinners.getFirst()).isGreaterThan(staleId);
        assertThat(reconcileIds.stream().filter(id -> id == 0L).count()).isEqualTo(threads - 1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_contact_sync_run WHERE id=?",
                String.class, staleId)).isEqualTo("ERROR");
        assertThat(syncRepository.finishRun(1L, reconcileWinners.getFirst(), "SUCCESS", null, 0, 0, 0,
                null, Instant.now())).isEqualTo(1);

        // (c) Concurrent CALLBACK startRun: all 16 threads succeed with 16 distinct generated IDs, 0 deadlocks
        List<Long> callbackIds = runConcurrentStartRuns(threads, "CALLBACK", null);
        assertThat(callbackIds).hasSize(threads).allMatch(id -> id > 0);
        Set<Long> distinctCallbackIds = new HashSet<>(callbackIds);
        assertThat(distinctCallbackIds).hasSize(threads);
    }

    private List<Long> runConcurrentStartRuns(int threads, String runType, Long actor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try {
            List<Callable<Long>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        return syncRepository.startRun(1L, MockWecomAdapter.CORP_ID, runType, actor,
                                Instant.now(), Instant.now().plusSeconds(120));
                    } catch (Throwable ex) {
                        errors.add(ex);
                        return -1L;
                    }
                });
            }
            List<Future<Long>> futures = new ArrayList<>(threads);
            for (Callable<Long> task : tasks) {
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Long> results = new ArrayList<>(threads);
            for (Future<Long> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(errors).isEmpty();
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode sync(String type) throws Exception {
        String response = mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/contact-sync"))
                        .header("Idempotency-Key", "it-wecom-contact-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"" + type + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }
}
