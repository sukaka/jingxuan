package com.whale.crm.platform.support;

import com.whale.crm.followup.application.FollowupNotificationScheduler;
import com.whale.crm.followup.domain.DueFollowupTask;
import com.whale.crm.followup.domain.FollowupAutomationRepository;
import com.whale.crm.followup.domain.FollowupBatch;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import com.mysql.cj.jdbc.MysqlDataSource;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.followup.automation.scheduler-enabled=false")
class FollowupAutomationReliabilityIT extends AbstractMysqlIT {
    @Autowired
    private FollowupAutomationRepository repository;
    @Autowired
    private FollowupNotificationScheduler notificationScheduler;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void batchLeaseHasOneWinnerRecoversAfterExpiryAndAttemptIsAppendOnly() throws Exception {
        String month = "2099-01";
        repository.insertBatch(1, month, 1);
        FollowupBatch batch = repository.findBatchByMonth(1, month).orElseThrow();
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                String owner = "followup-batch-" + index;
                results.add(executor.submit(() -> {
                    start.await();
                    Instant now = Instant.now();
                    return repository.claimBatch(1, batch.id(), batch.version(), batch.status(), owner,
                            now, now.plusSeconds(60));
                }));
            }
            start.countDown();
            int claims = 0;
            for (Future<Integer> result : results) {
                claims += result.get();
            }
            assertThat(claims).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        FollowupBatch claimed = repository.findBatchById(1, batch.id()).orElseThrow();
        assertThat(claimed.status()).isEqualTo("RUNNING");
        assertThat(repository.claimBatch(1, batch.id(), claimed.version(), claimed.status(), "active-thief",
                Instant.now(), Instant.now().plusSeconds(60))).isZero();
        jdbcTemplate.update("UPDATE crm_followup_batch SET lease_expires_at=? WHERE tenant_id=1 AND id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), batch.id());
        assertThat(repository.claimBatch(1, batch.id(), claimed.version(), claimed.status(), "recovery-owner",
                Instant.now(), Instant.now().plusSeconds(60))).isEqualTo(1);
        assertThatThrownBy(() -> repository.insertBatch(1, month, 1))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        repository.insertAttempt(1, batch.id(), 1, "FAILED", 1, "合成失败");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE crm_followup_batch_attempt SET error_masked='篡改' WHERE tenant_id=1 AND batch_id=?", batch.id()))
                .hasMessageContaining("Followup batch attempt is append only");
    }

    @Test
    void dueTaskQuerySkipsDoneActiveRunningAndBackoffFailedWithoutStarvingBeyondBatchSize() {
        String prefix = "HBUG09-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,nickname) "
                + "VALUES (1,2,1,?,'到期通知测试客户')", prefix + "-C");
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?", Long.class, prefix + "-C");

        Instant now = Instant.now();
        while (!repository.findDueTasks(1, now, 20).isEmpty()) {
            notificationScheduler.runOnce();
            now = Instant.now();
        }

        List<Long> batchTaskIds = new ArrayList<>();
        List<String> expectedJobKeys = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Instant dueAt = i % 2 == 0
                    ? Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i)
                    : Instant.parse("2025-01-01T00:00:00.123Z").plusSeconds(i);
            String title = prefix + "-T-" + i;
            jdbcTemplate.update("INSERT INTO crm_task (tenant_id,customer_id,task_type,priority,source_type,title,"
                            + "assignee_user_id,due_at,status) VALUES (1,?,'FOLLOWUP','MEDIUM','FOLLOWUP',?,2,?,'OPEN')",
                    customerId, title, Timestamp.from(dueAt));
            long taskId = jdbcTemplate.queryForObject(
                    "SELECT id FROM crm_task WHERE tenant_id=1 AND customer_id=? AND title=?",
                    Long.class, customerId, title);
            batchTaskIds.add(taskId);
            expectedJobKeys.add(taskId + ":" + dueAt);
        }

        int firstRun = notificationScheduler.runOnce();
        assertThat(firstRun).isEqualTo(20);
        int secondRun = notificationScheduler.runOnce();
        assertThat(secondRun).isEqualTo(5);

        for (String jobKey : expectedJobKeys) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM crm_followup_schedule_job WHERE tenant_id=1 "
                            + "AND job_type='TASK_DUE_NOTIFICATION' AND job_key=?",
                    String.class, jobKey);
            assertThat(status).isEqualTo("DONE");
        }
        List<Long> remainingIds = repository.findDueTasks(1, Instant.now(), 50).stream()
                .map(DueFollowupTask::id).toList();
        assertThat(remainingIds).doesNotContainAnyElementsOf(batchTaskIds);

        // Verify RUNNING/FAILED/due_at-update semantics directly
        Instant baseDue = Instant.parse("2025-02-01T01:02:03.450Z");
        Instant checkNow = Instant.parse("2025-02-02T00:00:00Z");
        long activeRunningTask = insertDueTask(customerId, prefix + "-RUN-ACT", baseDue);
        long expiredRunningTask = insertDueTask(customerId, prefix + "-RUN-EXP", baseDue);
        long backoffFailedTask = insertDueTask(customerId, prefix + "-FAIL-BO", baseDue);
        long dueFailedTask = insertDueTask(customerId, prefix + "-FAIL-DUE", baseDue);
        long doneThenRescheduledTask = insertDueTask(customerId, prefix + "-DONE-RESCHED", baseDue);

        insertScheduleJob(activeRunningTask, baseDue, "RUNNING", null, checkNow.plusSeconds(60));
        insertScheduleJob(expiredRunningTask, baseDue, "RUNNING", null, checkNow.minusSeconds(1));
        insertScheduleJob(backoffFailedTask, baseDue, "FAILED", checkNow.plusSeconds(60), null);
        insertScheduleJob(dueFailedTask, baseDue, "FAILED", checkNow.minusSeconds(1), null);
        insertScheduleJob(doneThenRescheduledTask, baseDue, "DONE", null, null);

        List<Long> selected = repository.findDueTasks(1, checkNow, 50).stream()
                .map(DueFollowupTask::id).toList();
        assertThat(selected).contains(expiredRunningTask, dueFailedTask);
        assertThat(selected).doesNotContain(activeRunningTask, backoffFailedTask, doneThenRescheduledTask);
        assertThat(repository.findDueTaskTenantIds(checkNow)).contains(1L);

        // Rescheduling a DONE task's due_at produces a new job_key and makes it eligible again
        jdbcTemplate.update("UPDATE crm_task SET due_at=? WHERE tenant_id=1 AND id=?",
                Timestamp.from(baseDue.plusSeconds(30)), doneThenRescheduledTask);
        List<Long> afterReschedule = repository.findDueTasks(1, checkNow, 50).stream()
                .map(DueFollowupTask::id).toList();
        assertThat(afterReschedule).contains(doneThenRescheduledTask);
    }

    private long insertDueTask(long customerId, String title, Instant dueAt) {
        jdbcTemplate.update("INSERT INTO crm_task (tenant_id,customer_id,task_type,priority,source_type,title,"
                        + "assignee_user_id,due_at,status) VALUES (1,?,'FOLLOWUP','MEDIUM','FOLLOWUP',?,2,?,'OPEN')",
                customerId, title, Timestamp.from(dueAt));
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_task WHERE tenant_id=1 AND customer_id=? AND title=?",
                Long.class, customerId, title);
        assertThat(id).isNotNull();
        return id;
    }

    private void insertScheduleJob(long taskId, Instant dueAt, String status, Instant nextRetryAt,
                                   Instant leaseExpiresAt) {
        jdbcTemplate.update("INSERT INTO crm_followup_schedule_job "
                        + "(tenant_id,job_type,job_key,status,next_retry_at,lease_expires_at) "
                        + "VALUES (1,'TASK_DUE_NOTIFICATION',?,?,?,?)",
                taskId + ":" + dueAt, status,
                nextRetryAt == null ? null : Timestamp.from(nextRetryAt),
                leaseExpiresAt == null ? null : Timestamp.from(leaseExpiresAt));
    }

    @Test
    void rollbackV30RemovesOnlyAutomationArtifacts() throws Exception {
        String schema = "followup_rollback_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = dataSource().getConnection(); java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        MysqlDataSource rollbackDataSource = new MysqlDataSource();
        rollbackDataSource.setURL(MYSQL.getJdbcUrl().replace("/medical_crm?", "/" + schema + "?"));
        rollbackDataSource.setUser("root");
        rollbackDataSource.setPassword(MYSQL.getPassword());
        try {
            Flyway.configure().dataSource(rollbackDataSource).schemas(schema)
                    .locations("classpath:db/migration").target("30").baselineOnMigrate(true).load().migrate();
            try (Connection connection = rollbackDataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource("db/rollback/V30__followup_automation_rollback.sql"));
            }
            JdbcTemplate verification = new JdbcTemplate(rollbackDataSource);
            assertThat(verification.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=? AND table_name IN ('crm_followup_policy','crm_followup_intent_policy',"
                    + "'crm_followup_batch','crm_followup_batch_item','crm_followup_batch_order',"
                    + "'crm_followup_batch_attempt','crm_followup_schedule_job')", Long.class, schema)).isZero();
            assertThat(verification.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                    + "WHERE table_schema=? AND index_name IN ('idx_order_followup_candidate','idx_task_followup_due')",
                    Long.class, schema)).isZero();
            assertThat(verification.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=? AND table_name IN ('crm_followup','crm_task','crm_order')",
                    Long.class, schema)).isEqualTo(3);
        } finally {
            try (Connection connection = rollbackDataSource.getConnection();
                 java.sql.Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private javax.sql.DataSource dataSource() {
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }
}
