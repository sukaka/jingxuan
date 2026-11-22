package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.adapter.MockWecomAdapter;
import com.whale.crm.integration.wecom.application.WecomCallbackService;
import com.whale.crm.integration.wecom.application.WecomContactSyncService;
import com.whale.crm.integration.wecom.infrastructure.mapper.WecomCallbackMapper;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"app.files.enabled=false", "app.outbox.relay.enabled=false",
        "app.scheduling.enabled=false", "app.wecom.official.callback-delay-ms=600000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WecomCallbackConcurrencyIT extends AbstractMysqlIT {
    @Autowired WecomCallbackService callbacks;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean WecomContactSyncService contacts;
    @Autowired WecomCallbackMapper callbackMapper;

    @BeforeEach
    void setUp() {
        when(contacts.corpId(1L)).thenReturn(MockWecomAdapter.CORP_ID);
        jdbcTemplate.update("DELETE FROM crm_wecom_callback_event WHERE tenant_id=1");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM crm_wecom_callback_event WHERE tenant_id=1");
    }

    @Test
    void staleSnapshotCannotBypassRetryBackoff() {
        callbacks.accept("<xml><CreateTime>1792990000</CreateTime>"
                + "<Event><![CDATA[change_external_contact]]></Event>"
                + "<ChangeType><![CDATA[add_external_contact]]></ChangeType>"
                + "<UserID><![CDATA[staff-2]]></UserID>"
                + "<ExternalUserID><![CDATA[WECOM-EXT-K01]]></ExternalUserID></xml>");
        List<com.whale.crm.integration.wecom.domain.WecomCallbackEvent> first =
                callbackMapper.selectPending(1L, Instant.now(), 50);
        List<com.whale.crm.integration.wecom.domain.WecomCallbackEvent> second =
                callbackMapper.selectPending(1L, Instant.now(), 50);
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        long eventId = first.getFirst().id();

        assertThat(callbackMapper.claim(1L, eventId, first.getFirst().attempts(), Instant.now())).isEqualTo(1);
        Instant retryAt = Instant.now().plusSeconds(60);
        callbackMapper.finish(1L, eventId, "RETRYABLE", retryAt, "合成瞬时失败", null);

        assertThat(callbackMapper.claim(1L, eventId, second.getFirst().attempts(), Instant.now())).isZero();
        assertThat(callbackMapper.claim(1L, eventId, 1, retryAt.plusMillis(1))).isEqualTo(1);

        callbackMapper.finish(1L, eventId, "SUCCEEDED", null, null, Instant.now());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_callback_event",
                String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM crm_wecom_callback_event",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void recoversExpiredProcessingLeasesForRetryableAndMaxAttemptEvents() {
        callbacks.accept("<xml><CreateTime>1792990001</CreateTime>"
                + "<Event><![CDATA[change_external_contact]]></Event>"
                + "<ChangeType><![CDATA[add_external_contact]]></ChangeType>"
                + "<UserID><![CDATA[staff-2]]></UserID>"
                + "<ExternalUserID><![CDATA[WECOM-EXT-LEASE-1]]></ExternalUserID></xml>");
        callbacks.accept("<xml><CreateTime>1792990002</CreateTime>"
                + "<Event><![CDATA[change_external_contact]]></Event>"
                + "<ChangeType><![CDATA[add_external_contact]]></ChangeType>"
                + "<UserID><![CDATA[staff-2]]></UserID>"
                + "<ExternalUserID><![CDATA[WECOM-EXT-LEASE-MAX]]></ExternalUserID></xml>");

        Long retryableId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_wecom_callback_event WHERE tenant_id=1 AND external_userid='WECOM-EXT-LEASE-1'",
                Long.class);
        Long maxAttemptId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_wecom_callback_event WHERE tenant_id=1 AND external_userid='WECOM-EXT-LEASE-MAX'",
                Long.class);
        jdbcTemplate.update("UPDATE crm_wecom_callback_event SET status='PROCESSING',attempts=1,"
                + "lease_until=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 2 MINUTE) WHERE id=?", retryableId);
        jdbcTemplate.update("UPDATE crm_wecom_callback_event SET status='PROCESSING',attempts=5,"
                + "lease_until=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 2 MINUTE) WHERE id=?", maxAttemptId);

        callbacks.processPending();

        verify(contacts).refreshContact(1L, "WECOM-EXT-LEASE-1");
        verify(contacts, never()).refreshContact(1L, "WECOM-EXT-LEASE-MAX");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_callback_event WHERE id=?",
                String.class, retryableId)).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("SELECT lease_until FROM crm_wecom_callback_event WHERE id=?",
                Object.class, retryableId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_callback_event WHERE id=?",
                String.class, maxAttemptId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT processed_at FROM crm_wecom_callback_event WHERE id=?",
                Object.class, maxAttemptId)).isNotNull();
    }
}
