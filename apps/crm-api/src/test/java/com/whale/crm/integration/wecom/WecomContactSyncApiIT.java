package com.whale.crm.integration.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.integration.wecom.adapter.MockWecomAdapter;
import com.whale.crm.integration.wecom.application.WecomCallbackService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.files.enabled=false", "app.outbox.relay.enabled=false",
        "app.wecom.official.callback-delay-ms=600000"})
class WecomContactSyncApiIT extends AbstractMysqlIT {
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WecomCallbackService callbacks;
    private AuthCookies admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin");
        jdbcTemplate.update("DELETE FROM crm_wecom_callback_event WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_wecom_contact_sync_run WHERE tenant_id=1");
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) VALUES (1,?,'staff-2',2,1),(1,?,'staff-3',3,1) ON DUPLICATE KEY UPDATE enabled=1", MockWecomAdapter.CORP_ID, MockWecomAdapter.CORP_ID);
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

    private JsonNode sync(String type) throws Exception {
        String response = mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/contact-sync"))
                        .header("Idempotency-Key", "it-wecom-contact-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"" + type + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }
}
