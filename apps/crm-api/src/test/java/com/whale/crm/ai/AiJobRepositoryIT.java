package com.whale.crm.ai;

import com.whale.crm.ai.domain.AiInsight;
import com.whale.crm.ai.domain.AiJob;
import com.whale.crm.ai.domain.AiJobRepository;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"app.ai.worker.enabled=false", "app.outbox.relay.enabled=false"})
class AiJobRepositoryIT extends AbstractMysqlIT {
    @Autowired AiJobRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM crm_ai_insight WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_ai_job WHERE tenant_id=1");
    }

    @Test
    void migrationClaimInsightAndQualityWorkAgainstMysql() {
        Instant now = Instant.now();
        repository.insert(new AiJob(null, 1L, 1010L, "CONV", 2L, "[1]", "hash", "repo-key", "QUEUED",
                null, null, 0, null, null, null, null, null, null, null, null, null, null, null, null, null));
        AiJob created = repository.findByIdempotency(1, 2, "repo-key").orElseThrow();
        assertThat(created.customerId()).isEqualTo(1010L);
        assertThat(repository.claim(1, created.id(), created.version(), "test-worker", now, now.plusSeconds(30))).isEqualTo(1);
        AiJob running = repository.findById(1, created.id()).orElseThrow();
        assertThat(running.status()).isEqualTo("RUNNING");
        repository.insertInsight(new AiInsight(null, 1L, created.id(), "SERVICE_COMPLETION_CLAIM", "[]", null,
                null, null, "[\"1\"]", new BigDecimal("0.72"), "[\"VERIFY_SERVICE_RECORD\"]", "UNVERIFIED",
                false, "待核实", "", null, "[\"SERVICE_CLAIM\"]", null));
        assertThat(repository.markProposed(1, created.id(), running.version(), "mock", "mock-rules-v1")).isEqualTo(1);
        AiJobRepository.AiQualityCount quality = repository.qualityCount(1, "ALL", List.of(), 1);
        assertThat(quality.pendingReview()).isEqualTo(1);
        assertThat(quality.riskSuggested()).isEqualTo(1);
        assertThat(repository.findInsight(1, created.id()).orElseThrow().allowFinancialWrite()).isFalse();
    }
}
