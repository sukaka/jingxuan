package com.whale.crm.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CampaignApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void campaignBatchCrudValidatesTimeOwnershipStatusAndVersion() throws Exception {
        AuthCookies admin = login("admin");
        JsonNode campaign = data(postJson("/api/v1/campaigns", admin, campaignBody("P2-CAMPAIGN-CRUD", "P2活动")));
        long campaignId = campaign.at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/campaigns"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(campaignBody("P2-CAMPAIGN-CRUD", "重复编码"))))
                .andExpect(status().isConflict());

        JsonNode batch = data(postJson("/api/v1/campaigns/" + campaignId + "/batches", admin,
                batchBody("WAVE-1", "第一波")));
        long batchId = batch.at("/id").asLong();
        mockMvc.perform(get("/api/v1/campaigns/" + campaignId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campaignCode").value("P2-CAMPAIGN-CRUD"))
                .andExpect(jsonPath("$.data.batches.length()").value(1))
                .andExpect(jsonPath("$.data.batches[0].batchCode").value("WAVE-1"));

        Map<String, Object> invalidTime = campaignBody("P2-CAMPAIGN-TIME", "时间错误");
        invalidTime.put("startsAt", "2030-01-01T00:00:00Z");
        invalidTime.put("endsAt", "2029-01-01T00:00:00Z");
        mockMvc.perform(admin.withAuth(post("/api/v1/campaigns"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(invalidTime)))
                .andExpect(status().isBadRequest());

        long otherCampaignId = data(postJson("/api/v1/campaigns", admin,
                campaignBody("P2-CAMPAIGN-OTHER", "另一活动"))).at("/id").asLong();
        Map<String, Object> invalidOwnership = batchBody("WAVE-1", "越权批次");
        invalidOwnership.put("status", "ACTIVE");
        invalidOwnership.put("version", 0);
        mockMvc.perform(admin.withAuth(patch("/api/v1/campaigns/" + otherCampaignId + "/batches/" + batchId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(invalidOwnership)))
                .andExpect(status().isNotFound());

        Map<String, Object> update = campaignBody("P2-CAMPAIGN-CRUD", "P2活动（改）");
        update.put("status", "ACTIVE");
        update.put("version", 99);
        mockMvc.perform(admin.withAuth(patch("/api/v1/campaigns/" + campaignId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isConflict());
        update.put("version", 0);
        mockMvc.perform(admin.withAuth(patch("/api/v1/campaigns/" + campaignId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campaignName").value("P2活动（改）"))
                .andExpect(jsonPath("$.data.version").value(1));

        Map<String, Object> batchUpdate = batchBody("WAVE-1", "第一波（归档）");
        batchUpdate.put("status", "ARCHIVED");
        batchUpdate.put("version", 0);
        mockMvc.perform(admin.withAuth(patch("/api/v1/campaigns/" + campaignId + "/batches/" + batchId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(batchUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data.version").value(1));

        update.put("status", "ARCHIVED");
        update.put("version", 1);
        mockMvc.perform(admin.withAuth(patch("/api/v1/campaigns/" + campaignId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));
        update.put("status", "ACTIVE");
        update.put("version", 2);
        mockMvc.perform(admin.withAuth(patch("/api/v1/campaigns/" + campaignId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/campaigns?keyword=P2-CAMPAIGN&status=ARCHIVED").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(campaignId));
        mockMvc.perform(get("/api/v1/campaigns").cookie(login("manager1").cookies()))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE target_type='CAMPAIGN' AND target_id=? AND action_code='CAMPAIGN_CREATE'",
                Integer.class, Long.toString(campaignId))).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE target_type='CAMPAIGN' AND target_id=? AND action_code='CAMPAIGN_UPDATE'",
                Integer.class, Long.toString(campaignId))).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE target_type='CAMPAIGN_BATCH' AND target_id=? AND action_code IN ('CAMPAIGN_BATCH_CREATE','CAMPAIGN_BATCH_UPDATE')",
                Integer.class, Long.toString(batchId))).isEqualTo(2);
    }

    @Test
    void campaignReadsAreTenantIsolated() throws Exception {
        AuthCookies admin = login("admin");
        jdbcTemplate.update("INSERT INTO crm_campaign (tenant_id,campaign_code,campaign_name,campaign_type,status) VALUES (2,?,?,?,'ACTIVE')",
                "P2-TENANT-2", "其他租户活动", "REGULAR");
        long id = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_campaign WHERE tenant_id=2 AND campaign_code='P2-TENANT-2'", Long.class);
        mockMvc.perform(get("/api/v1/campaigns/" + id).cookie(admin.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(admin.withAuth(patch("/api/v1/campaigns/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignName\":\"越权修改\",\"campaignType\":\"REGULAR\",\"status\":\"ACTIVE\",\"version\":0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void channelCrudRequiresRealOwnerAndKeepsArchivedRecordReadable() throws Exception {
        AuthCookies admin = login("admin");
        Map<String, Object> create = new HashMap<>();
        create.put("channelNo", "P2-CHANNEL-CRUD");
        create.put("name", "P2真实渠道");
        create.put("platformCode", "WEIBO");
        create.put("businessOwnerUserId", 6);
        create.put("remarks", "集成测试合成渠道");
        JsonNode channel = data(postJson("/api/v1/channels", admin, create));
        long channelId = channel.at("/id").asLong();

        create.put("businessOwnerUserId", 999999);
        create.put("channelNo", "P2-CHANNEL-BAD-OWNER");
        mockMvc.perform(admin.withAuth(post("/api/v1/channels"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(create)))
                .andExpect(status().isBadRequest());

        Map<String, Object> update = new HashMap<>(create);
        update.put("channelNo", "P2-CHANNEL-CRUD");
        update.put("name", "P2真实渠道（归档）");
        update.put("businessOwnerUserId", 6);
        update.put("status", "ARCHIVED");
        update.put("version", 0);
        mockMvc.perform(admin.withAuth(patch("/api/v1/channels/" + channelId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(get("/api/v1/channels?keyword=P2-CHANNEL-CRUD&status=ARCHIVED").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("P2真实渠道（归档）"));

        update.put("status", "ACTIVE");
        update.put("version", 1);
        mockMvc.perform(admin.withAuth(patch("/api/v1/channels/" + channelId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/channels").cookie(login("manager1").cookies()))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE target_type='PARTNER_CHANNEL' AND target_id=? AND action_code IN ('CHANNEL_CREATE','CHANNEL_UPDATE')",
                Integer.class, Long.toString(channelId))).isEqualTo(2);
    }

    @Test
    void channelProfileValidatesFormatsEncryptsSensitiveFieldsAndRedactsAudit() throws Exception {
        AuthCookies admin = login("admin");
        Map<String, Object> create = channelProfileBody("P2-CHANNEL-PROFILE");
        JsonNode channel = data(postJson("/api/v1/channels", admin, create));
        long channelId = channel.at("/id").asLong();
        org.assertj.core.api.Assertions.assertThat(channel.at("/commissionPercent").decimalValue())
                .isEqualByComparingTo("12.50");
        org.assertj.core.api.Assertions.assertThat(channel.at("/contactPhone").asText()).isEqualTo("13800000000");
        org.assertj.core.api.Assertions.assertThat(channel.at("/idCardNumber").asText()).isEqualTo("11010519491231002X");
        org.assertj.core.api.Assertions.assertThat(channel.at("/settlementMethod").asText()).isEqualTo("MONTHLY");
        org.assertj.core.api.Assertions.assertThat(channel.at("/paymentAccountNo").asText()).isEqualTo("0000000000000001");
        org.assertj.core.api.Assertions.assertThat(channel.at("/businessOwnerName").asText()).isEqualTo("孙周");
        mockMvc.perform(get("/api/v1/channels/owner-options").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == 6 && @.displayName == '孙周')]").isNotEmpty());

        Map<String, Object> automatic = channelProfileBody("P2-CHANNEL-AUTO-NUMBER");
        automatic.remove("channelNo");
        JsonNode automaticChannel = data(postJson("/api/v1/channels", admin, automatic));
        org.assertj.core.api.Assertions.assertThat(automaticChannel.at("/channelNo").asText())
                .startsWith("CH").hasSize(28).matches("CH[0-9A-Z]{26}");

        Map<String, Object> distribution = channelProfileBody("P2-CHANNEL-DISTRIBUTION");
        distribution.put("channelType", "DISTRIBUTION");
        distribution.put("settlementMethod", "TWICE_MONTHLY");
        JsonNode distributionChannel = data(postJson("/api/v1/channels", admin, distribution));
        org.assertj.core.api.Assertions.assertThat(distributionChannel.at("/channelType").asText())
                .isEqualTo("DISTRIBUTION");
        mockMvc.perform(get("/api/v1/channels?channelType=DISTRIBUTION").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].channelType").value("DISTRIBUTION"));

        Map<String, Object> mismatched = channelProfileBody("P2-CHANNEL-BAD-SETTLEMENT");
        mismatched.put("channelType", "BLOGGER");
        mismatched.put("settlementMethod", "TWICE_MONTHLY");
        mockMvc.perform(admin.withAuth(post("/api/v1/channels")).contentType(MediaType.APPLICATION_JSON)
                .content(json(mismatched))).andExpect(status().isBadRequest());
        mismatched = channelProfileBody("P2-CHANNEL-REFERRAL-UNCONFIRMED");
        mismatched.put("channelType", "REFERRAL");
        mockMvc.perform(admin.withAuth(post("/api/v1/channels")).contentType(MediaType.APPLICATION_JSON)
                .content(json(mismatched))).andExpect(status().isBadRequest());

        for (String column : new String[]{"contact_phone_cipher", "id_card_number_cipher",
                "payment_account_name_cipher", "payment_account_no_cipher"}) {
            String stored = jdbcTemplate.queryForObject("SELECT " + column
                    + " FROM crm_partner_channel WHERE tenant_id=1 AND id=?", String.class, channelId);
            org.assertj.core.api.Assertions.assertThat(stored).isNotBlank()
                    .doesNotContain("13800000000", "11010519491231002X", "0000000000000001", "合成收款账户");
        }
        String audit = jdbcTemplate.queryForObject("SELECT CONCAT_WS('',before_json,after_json) FROM crm_audit_log "
                + "WHERE target_type='PARTNER_CHANNEL' AND target_id=? AND action_code='CHANNEL_CREATE'",
                String.class, Long.toString(channelId));
        org.assertj.core.api.Assertions.assertThat(audit).doesNotContain("13800000000", "11010519491231002X",
                "0000000000000001", "合成真实姓名", "合成收款账户");

        Map<String, Object> invalid = channelProfileBody("P2-CHANNEL-PHONE-12");
        invalid.put("contactPhone", "138000000000");
        mockMvc.perform(admin.withAuth(post("/api/v1/channels")).contentType(MediaType.APPLICATION_JSON)
                .content(json(invalid))).andExpect(status().isBadRequest());
        invalid = channelProfileBody("P2-CHANNEL-ID-8");
        invalid.put("idCardNumber", "12345678");
        mockMvc.perform(admin.withAuth(post("/api/v1/channels")).contentType(MediaType.APPLICATION_JSON)
                .content(json(invalid))).andExpect(status().isBadRequest());
        invalid = channelProfileBody("P2-CHANNEL-ID-CHECKSUM");
        invalid.put("idCardNumber", "110105194912310021");
        mockMvc.perform(admin.withAuth(post("/api/v1/channels")).contentType(MediaType.APPLICATION_JSON)
                .content(json(invalid))).andExpect(status().isBadRequest());
        invalid = channelProfileBody("P2-CHANNEL-DATE");
        invalid.put("cooperationStartDate", "2027-01-01");
        invalid.put("cooperationEndDate", "2026-01-01");
        mockMvc.perform(admin.withAuth(post("/api/v1/channels")).contentType(MediaType.APPLICATION_JSON)
                .content(json(invalid))).andExpect(status().isBadRequest());
        invalid = channelProfileBody("P2-CHANNEL-COMMISSION");
        invalid.put("commissionPercent", 100.01);
        mockMvc.perform(admin.withAuth(post("/api/v1/channels")).contentType(MediaType.APPLICATION_JSON)
                .content(json(invalid))).andExpect(status().isBadRequest());
    }

    private Map<String, Object> channelProfileBody(String channelNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("channelNo", channelNo);
        body.put("name", "合成博主昵称");
        body.put("platformCode", "WEIBO");
        body.put("commissionPercent", "12.50");
        body.put("businessOwnerUserId", 6);
        body.put("realName", "合成真实姓名");
        body.put("contactPhone", "13800000000");
        body.put("idCardNumber", "11010519491231002X");
        body.put("settlementMethod", "MONTHLY");
        body.put("cooperationStartDate", "2026-01-01");
        body.put("cooperationEndDate", "2026-12-31");
        body.put("paymentAccountName", "合成收款账户");
        body.put("paymentAccountNo", "0000000000000001");
        body.put("paymentBankName", "合成测试开户行");
        body.put("remarks", "仅用于集成测试的合成渠道资料");
        return body;
    }

    private Map<String, Object> campaignBody(String code, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("campaignCode", code);
        body.put("campaignName", name);
        body.put("campaignType", "PROMOTION");
        body.put("startsAt", "2020-01-01T00:00:00Z");
        body.put("endsAt", "2030-01-01T00:00:00Z");
        return body;
    }

    private Map<String, Object> batchBody(String code, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("batchCode", code);
        body.put("batchName", name);
        body.put("startsAt", "2020-01-01T00:00:00Z");
        body.put("endsAt", "2030-01-01T00:00:00Z");
        return body;
    }

    private JsonNode data(MockHttpServletRequestBuilder builder) throws Exception {
        String response = mockMvc.perform(builder).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private MockHttpServletRequestBuilder postJson(String url, AuthCookies actor, Object body) throws Exception {
        return actor.withAuth(post(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
