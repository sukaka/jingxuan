package com.whale.crm.platform.support;

import com.whale.crm.followup.domain.FollowupAutomationRepository;
import com.whale.crm.followup.domain.FollowupBatch;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

class FollowupAutomationReliabilityIT extends AbstractMysqlIT {
    @Autowired
    private FollowupAutomationRepository repository;
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
