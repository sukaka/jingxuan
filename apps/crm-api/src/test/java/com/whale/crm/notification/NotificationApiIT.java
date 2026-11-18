package com.whale.crm.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.notification.application.NotificationDeliveryWorker;
import com.whale.crm.notification.application.NotificationEventConsumer;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.notification.infrastructure.wecom.OfficialWecomMessageClient;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.outbox.relay.enabled=false", "app.notification.kafka.enabled=false",
        "app.notification.delivery.worker-enabled=false", "app.wecom.message.mode=MOCK",
        "app.wecom.message.corp-id=ww-notification-api-it"
})
class NotificationApiIT extends AbstractMysqlIT {
    @Autowired NotificationRequestService requestService;
    @Autowired NotificationRepository repository;
    @Autowired NotificationDeliveryWorker worker;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean OfficialWecomMessageClient officialClient;

    @Test
    void completeNotificationFlowIsIsolatedIdempotentAuditedAndNeverCallsOfficialWecom() throws Exception {
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                        + "VALUES (1,'ww-notification-api-it',?,2,0) ON DUPLICATE KEY UPDATE staff_user_id=VALUES(staff_user_id)",
                "synthetic-staff-" + suffix);
        NotificationRequest request = new NotificationRequest(1L, 2L, "FOLLOWUP_DUE", "合成跟进提醒",
                "仅合成脱敏正文", "FOLLOWUP", suffix, "followup:" + suffix, "/followups/" + suffix,
                Set.of(NotificationChannel.IN_APP, NotificationChannel.WECOM_APP));
        Notification created = requestService.request(request);
        Notification replayed = requestService.request(request);
        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(count("crm_notification_delivery", created.id())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 "
                + "AND aggregate_type='notification' AND aggregate_id=?", Long.class, created.id().toString())).isEqualTo(1);

        AuthCookies manager = login("manager1");
        AuthCookies otherManager = login("manager2");
        AuthCookies admin = login("admin");
        mockMvc.perform(manager.withAuth(get("/api/v1/notifications").param("unreadOnly", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == " + created.id() + ")].title").value("合成跟进提醒"))
                .andExpect(jsonPath("$.data.items[?(@.id == " + created.id() + ")].deliveries").isNotEmpty());
        mockMvc.perform(manager.withAuth(get("/api/v1/notifications/unread-count")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").isNumber());

        String readKey = "read-" + suffix;
        mockMvc.perform(post("/api/v1/notifications/{id}/read", created.id()).cookie(manager.cookies())
                        .header("Idempotency-Key", readKey).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mockMvc.perform(otherManager.withAuth(post("/api/v1/notifications/{id}/read", created.id()))
                        .header("Idempotency-Key", "other-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(manager.withAuth(post("/api/v1/notifications/{id}/read", created.id()))
                        .header("Idempotency-Key", readKey).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.readAt").isNotEmpty());
        mockMvc.perform(manager.withAuth(post("/api/v1/notifications/{id}/read", created.id()))
                        .header("Idempotency-Key", readKey).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(manager.withAuth(post("/api/v1/notifications/{id}/read", created.id()))
                        .header("Idempotency-Key", readKey).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(manager.withAuth(post("/api/v1/notifications/{id}/read", created.id()))
                        .header("Idempotency-Key", "empty-body-" + suffix).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mockMvc.perform(manager.withAuth(post("/api/v1/notifications/{id}/read", created.id()))
                        .header("Idempotency-Key", "missing-version-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(manager.withAuth(post("/api/v1/notifications/{id}/read", created.id()))
                        .header("Idempotency-Key", "current-version-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.channels.length()").value(2))
                .andExpect(jsonPath("$.data.deliveries.length()").value(2))
                .andExpect(jsonPath("$.data.deliveries[0].channel").isNotEmpty())
                .andExpect(jsonPath("$.data.deliveries[0].status").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND action_code='NOTIFICATION_READ' AND target_id=?", Long.class, created.id().toString())).isEqualTo(1);

        mockMvc.perform(manager.withAuth(get("/api/v1/notification-deliveries"))).andExpect(status().isForbidden());
        mockMvc.perform(admin.withAuth(get("/api/v1/notification-deliveries").param("notificationId",
                        created.id().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));

        String eventId = "notification.requested.v1:" + created.id();
        String envelope = """
                {"event_id":"%s","event_type":"notification.requested.v1","event_version":1,
                "tenant_id":1,"aggregate_type":"notification","aggregate_id":"%s",
                "occurred_at":"2026-01-01T00:00:00Z","correlation_id":"%s","payload":{"notificationId":%d}}
                """.formatted(eventId, created.id(), eventId, created.id());
        NotificationEventConsumer consumer = new NotificationEventConsumer(repository, jdbcTemplate, objectMapper,
                transactionManager);
        consumer.consume(envelope);
        consumer.consume(envelope);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 "
                + "AND consumer_group='crm-notification-request' AND event_id=?", Long.class, eventId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_notification_delivery "
                + "WHERE notification_id=? AND status='PENDING'", Long.class, created.id())).isEqualTo(2);

        assertThat(worker.runOnce()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_notification_delivery "
                + "WHERE notification_id=? AND channel='IN_APP'", String.class, created.id())).isEqualTo("SUCCEEDED");
        Map<String, Object> wecom = jdbcTemplate.queryForMap("SELECT id,status,provider,version FROM crm_notification_delivery "
                + "WHERE notification_id=? AND channel='WECOM_APP'", created.id());
        assertThat(wecom).containsEntry("status", "MOCKED").containsEntry("provider", "MOCK");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_notification_attempt WHERE notification_id=?",
                Long.class, created.id())).isEqualTo(2);
        verifyNoInteractions(officialClient);

        long deliveryId = ((Number) wecom.get("id")).longValue();
        long completedVersion = ((Number) wecom.get("version")).longValue();
        jdbcTemplate.update("UPDATE crm_notification_delivery SET status='UNKNOWN',last_error_masked='SYNTHETIC_UNKNOWN' "
                + "WHERE tenant_id=1 AND id=?", deliveryId);
        mockMvc.perform(admin.withAuth(post("/api/v1/notification-deliveries/{id}/retry", deliveryId))
                        .header("Idempotency-Key", "missing-reason-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", completedVersion))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin.withAuth(post("/api/v1/notification-deliveries/{id}/retry", deliveryId))
                        .header("Idempotency-Key", "stale-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", completedVersion - 1,
                                "reason", "旧版本不允许重试"))))
                .andExpect(status().isConflict());
        String retryKey = "retry-" + suffix;
        String retryBody = objectMapper.writeValueAsString(Map.of("version", completedVersion,
                "reason", "人工核实未收到，允许一次合成重试"));
        mockMvc.perform(admin.withAuth(post("/api/v1/notification-deliveries/{id}/retry", deliveryId))
                        .header("Idempotency-Key", retryKey).contentType(MediaType.APPLICATION_JSON).content(retryBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.version").value(completedVersion + 1));
        mockMvc.perform(admin.withAuth(post("/api/v1/notification-deliveries/{id}/retry", deliveryId))
                        .header("Idempotency-Key", retryKey).contentType(MediaType.APPLICATION_JSON).content(retryBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(completedVersion + 1));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND action_code='NOTIFICATION_DELIVERY_RETRY' AND target_id=?", Long.class,
                Long.toString(deliveryId))).isEqualTo(1);
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT trigger_type FROM crm_notification_attempt "
                + "WHERE delivery_id=? ORDER BY attempt_no DESC LIMIT 1", String.class, deliveryId)).isEqualTo("MANUAL");
        verifyNoInteractions(officialClient);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_notification SET title='禁止覆盖' WHERE id=?", created.id()))
                .isInstanceOf(DataAccessException.class);
        assertThat(repository.findById(1L, created.id()).orElseThrow().title()).isEqualTo("合成跟进提醒");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_notification_attempt SET error_masked='禁止覆盖' "
                + "WHERE delivery_id=?", deliveryId)).isInstanceOf(DataAccessException.class);
    }

    private long count(String table, long notificationId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=1 AND notification_id=?",
                Long.class, notificationId);
    }
}
