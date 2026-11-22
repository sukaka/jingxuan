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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
        org.assertj.core.api.Assertions.assertThat(channel.at("/contactPhone").asText()).isEqualTo("138****0000");
        org.assertj.core.api.Assertions.assertThat(channel.at("/contactPhoneConfigured").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(channel.at("/idCardNumber").asText()).isEqualTo("110105********002X");
        org.assertj.core.api.Assertions.assertThat(channel.at("/idCardConfigured").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(channel.at("/settlementMethod").asText()).isEqualTo("MONTHLY");
        org.assertj.core.api.Assertions.assertThat(channel.at("/paymentAccountName").asText()).isEqualTo("合****户");
        org.assertj.core.api.Assertions.assertThat(channel.at("/paymentAccountNo").asText()).isEqualTo("****0001");
        org.assertj.core.api.Assertions.assertThat(channel.at("/paymentAccountConfigured").asBoolean()).isTrue();
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
        Map<String, Object> referral = channelProfileBody("P2-CHANNEL-REFERRAL-TWICE");
        referral.put("channelType", "REFERRAL");
        referral.put("settlementMethod", "TWICE_MONTHLY");
        JsonNode referralChannel = data(postJson("/api/v1/channels", admin, referral));
        org.assertj.core.api.Assertions.assertThat(referralChannel.at("/channelType").asText()).isEqualTo("REFERRAL");
        org.assertj.core.api.Assertions.assertThat(referralChannel.at("/settlementMethod").asText())
                .isEqualTo("TWICE_MONTHLY");

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

    @Test
    void operationCanMaintainDraftButOnlyOperationLeadCanPublish() throws Exception {
        AuthCookies operation = login("operation1");
        AuthCookies lead = login("operationlead1");
        Map<String, Object> draft = campaignBody("P2-OPERATION-DRAFT", "运营草稿活动");
        draft.put("status", "DRAFT");
        long id = data(postJson("/api/v1/campaigns", operation, draft)).at("/id").asLong();

        Map<String, Object> publish = campaignBody("P2-OPERATION-DRAFT", "运营草稿活动");
        publish.put("status", "ACTIVE");
        publish.put("version", 0);
        mockMvc.perform(operation.withAuth(patch("/api/v1/campaigns/" + id))
                        .contentType(MediaType.APPLICATION_JSON).content(json(publish)))
                .andExpect(status().isForbidden());
        Map<String, Object> directActive = campaignBody("P2-OPERATION-DIRECT-ACTIVE", "普通运营直接发布");
        directActive.put("status", "ACTIVE");
        mockMvc.perform(operation.withAuth(post("/api/v1/campaigns"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(directActive)))
                .andExpect(status().isForbidden());

        mockMvc.perform(lead.withAuth(patch("/api/v1/campaigns/" + id))
                        .contentType(MediaType.APPLICATION_JSON).content(json(publish)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/users").cookie(operation.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(operation.withAuth(post("/api/v1/orders/1/payment-confirmations"))
                        .header("Idempotency-Key", "IT-OPERATION-PAYMENT-DENIED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalTradeNo\":\"IT-DENIED\",\"paymentChannel\":\"OFFLINE\","
                                + "\"amountFen\":1,\"paidAt\":\"2026-11-01T00:00:00Z\",\"evidenceRef\":\"IT-DENIED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void hSec03ChannelMaskingStatusToggleAndFinanceApprovalWorkflow() throws Exception {
        AuthCookies business = login("business1");
        AuthCookies finance = login("finance1");
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");

        // 1. Unconfigured INACTIVE channel can set initial financials on PATCH, rejects wildcard *
        Map<String, Object> draftBody = new HashMap<>();
        draftBody.put("channelNo", "HSEC03-UNLOCKED");
        draftBody.put("name", "未配置财务渠道");
        draftBody.put("channelType", "BLOGGER");
        draftBody.put("status", "INACTIVE");
        draftBody.put("businessOwnerUserId", 6);
        JsonNode unlocked = data(postJson("/api/v1/channels", business, draftBody));
        long unlockedId = unlocked.at("/id").asLong();
        assertThat(unlocked.at("/contactPhoneConfigured").asBoolean()).isFalse();
        assertThat(unlocked.at("/idCardConfigured").asBoolean()).isFalse();
        assertThat(unlocked.at("/paymentAccountConfigured").asBoolean()).isFalse();

        Map<String, Object> wildcardPatch = new HashMap<>(draftBody);
        wildcardPatch.put("version", 0);
        wildcardPatch.put("paymentAccountNo", "****0001");
        mockMvc.perform(business.withAuth(patch("/api/v1/channels/" + unlockedId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(wildcardPatch)))
                .andExpect(status().isBadRequest());

        Map<String, Object> initialFinancialPatch = new HashMap<>(draftBody);
        initialFinancialPatch.put("version", 0);
        initialFinancialPatch.put("commissionPercent", "8.50");
        initialFinancialPatch.put("settlementMethod", "MONTHLY");
        initialFinancialPatch.put("paymentAccountName", "初始收款户");
        initialFinancialPatch.put("paymentAccountNo", "6222000000001234");
        initialFinancialPatch.put("paymentBankName", "初始开户行");
        JsonNode configured = data(business.withAuth(patch("/api/v1/channels/" + unlockedId))
                .contentType(MediaType.APPLICATION_JSON).content(json(initialFinancialPatch)));
        assertThat(configured.at("/paymentAccountName").asText()).isEqualTo("初***户");
        assertThat(configured.at("/paymentAccountNo").asText()).isEqualTo("****1234");
        assertThat(configured.at("/paymentAccountConfigured").asBoolean()).isTrue();

        // 2. Status toggle endpoint updates status without touching PII/bank; checks version
        JsonNode activated = data(business.withAuth(post("/api/v1/channels/" + unlockedId + "/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\",\"version\":1,\"reason\":\"上线合作\"}"));
        assertThat(activated.at("/status").asText()).isEqualTo("ACTIVE");
        assertThat(activated.at("/version").asLong()).isEqualTo(2L);
        assertThat(activated.at("/paymentAccountNo").asText()).isEqualTo("****1234");

        mockMvc.perform(business.withAuth(post("/api/v1/channels/" + unlockedId + "/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"version\":99}"))
                .andExpect(status().isConflict());

        // 3. Create ACTIVE channel with PII + bank -> ordinary PATCH preserves blank sensitive & rejects financial change
        Map<String, Object> create = channelProfileBody("HSEC03-FLOW");
        JsonNode channel = data(postJson("/api/v1/channels", business, create));
        long channelId = channel.at("/id").asLong();
        String oldPhoneCipher = jdbcTemplate.queryForObject(
                "SELECT contact_phone_cipher FROM crm_partner_channel WHERE tenant_id=1 AND id=?",
                String.class, channelId);
        String oldIdCipher = jdbcTemplate.queryForObject(
                "SELECT id_card_number_cipher FROM crm_partner_channel WHERE tenant_id=1 AND id=?",
                String.class, channelId);

        Map<String, Object> forbiddenPatch = new HashMap<>();
        forbiddenPatch.put("name", "改名");
        forbiddenPatch.put("channelType", "BLOGGER");
        forbiddenPatch.put("status", "ACTIVE");
        forbiddenPatch.put("commissionPercent", "20.00");
        forbiddenPatch.put("version", 0);
        mockMvc.perform(business.withAuth(patch("/api/v1/channels/" + channelId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(forbiddenPatch)))
                .andExpect(status().isForbidden());

        Map<String, Object> metadataPatch = new HashMap<>();
        metadataPatch.put("name", "合成博主昵称（已改）");
        metadataPatch.put("channelType", "BLOGGER");
        metadataPatch.put("businessOwnerUserId", 6);
        metadataPatch.put("status", "ACTIVE");
        metadataPatch.put("remarks", "保留敏感密文");
        metadataPatch.put("version", 0);
        JsonNode updatedMeta = data(business.withAuth(patch("/api/v1/channels/" + channelId))
                .contentType(MediaType.APPLICATION_JSON).content(json(metadataPatch)));
        assertThat(updatedMeta.at("/version").asLong()).isEqualTo(1L);
        assertThat(updatedMeta.at("/contactPhone").asText()).isEqualTo("138****0000");
        assertThat(updatedMeta.at("/contactPhoneConfigured").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT contact_phone_cipher FROM crm_partner_channel WHERE tenant_id=1 AND id=?",
                String.class, channelId)).isEqualTo(oldPhoneCipher);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id_card_number_cipher FROM crm_partner_channel WHERE tenant_id=1 AND id=?",
                String.class, channelId)).isEqualTo(oldIdCipher);

        // 4. Submit financial change request -> duplicate active 409 -> role-aware masking -> baseVersion conflict -> reject -> approve
        String createKey = "hsec03-create-" + UUID.randomUUID();
        Map<String, Object> finChange = Map.of(
                "commissionPercent", "18.50",
                "paymentAccountName", "新合作收款户",
                "paymentAccountNo", "6222020200009999",
                "paymentBankName", "新合作开户行",
                "baseVersion", 1,
                "reason", "季度调佣与更换收款卡");
        JsonNode req1 = data(business.withAuth(post("/api/v1/channels/" + channelId + "/financial-change-requests"))
                .header("Idempotency-Key", createKey)
                .contentType(MediaType.APPLICATION_JSON).content(json(finChange)));
        long reqId1 = req1.at("/id").asLong();
        assertThat(req1.at("/status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(req1.at("/afterMasked/commissionPercent").decimalValue()).isEqualByComparingTo("18.50");
        assertThat(req1.at("/afterMasked/paymentAccountName").asText()).isEqualTo("***");
        assertThat(req1.at("/afterMasked/paymentAccountNo").asText()).isEqualTo("****");
        assertThat(req1.at("/afterMasked/paymentBankName").asText()).isEqualTo("***");
        assertThat(req1.at("/paymentAccountNo").isNull()).isTrue();

        // Idempotent replay
        JsonNode replayedCreate = data(business.withAuth(post("/api/v1/channels/" + channelId + "/financial-change-requests"))
                .header("Idempotency-Key", createKey)
                .contentType(MediaType.APPLICATION_JSON).content(json(finChange)));
        assertThat(replayedCreate.at("/id").asLong()).isEqualTo(reqId1);

        // Duplicate active request with different key -> 409
        mockMvc.perform(business.withAuth(post("/api/v1/channels/" + channelId + "/financial-change-requests"))
                        .header("Idempotency-Key", "hsec03-dup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(finChange)))
                .andExpect(status().isConflict());

        // Non-finance (business) vs finance GET list masking + manager1 403
        mockMvc.perform(get("/api/v1/channel-change-requests").cookie(manager.cookies()))
                .andExpect(status().isForbidden());
        JsonNode businessList = data(get("/api/v1/channels/" + channelId + "/financial-change-requests")
                .cookie(business.cookies()));
        assertThat(businessList.at("/items/0/afterMasked/paymentAccountNo").asText()).isEqualTo("****");
        JsonNode financeList = data(get("/api/v1/channel-change-requests?channelId=" + channelId)
                .cookie(finance.cookies()));
        assertThat(financeList.at("/items/0/afterMasked/paymentAccountName").asText()).isEqualTo("新****户");
        assertThat(financeList.at("/items/0/afterMasked/paymentAccountNo").asText()).isEqualTo("****9999");
        assertThat(financeList.at("/items/0/paymentAccountNo").asText()).isEqualTo("****9999");
        assertThat(financeList.toString()).doesNotContain("6222020200009999", "新合作收款户");

        // Advance channel version via status toggle -> APPROVE returns 409 CONFLICT, REJECT clears active request
        data(business.withAuth(post("/api/v1/channels/" + channelId + "/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\",\"version\":1,\"reason\":\"临时暂停\"}"));
        mockMvc.perform(finance.withAuth(post("/api/v1/channel-change-requests/" + reqId1 + "/review"))
                        .header("Idempotency-Key", "hsec03-conflict-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"version\":0,\"comment\":\"过期版本\"}"))
                .andExpect(status().isConflict());
        JsonNode rejected = data(finance.withAuth(post("/api/v1/channel-change-requests/" + reqId1 + "/review"))
                .header("Idempotency-Key", "hsec03-reject-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"REJECT\",\"version\":0,\"comment\":\"版本已过期请重提\"}"));
        assertThat(rejected.at("/status").asText()).isEqualTo("REJECTED");

        // 5. Self-review denied (403 + CHANNEL_SELF_REVIEW_DENIED audit) & finance approval updates DB + audit
        Map<String, Object> finChange2 = Map.of(
                "commissionPercent", "19.00",
                "paymentAccountName", "终审收款户",
                "paymentAccountNo", "6222020200007777",
                "paymentBankName", "终审开户行",
                "baseVersion", 2,
                "reason", "重新提交");
        long reqId2 = data(admin.withAuth(post("/api/v1/channels/" + channelId + "/financial-change-requests"))
                .header("Idempotency-Key", "hsec03-admin-req-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json(finChange2))).at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/channel-change-requests/" + reqId2 + "/review"))
                        .header("Idempotency-Key", "hsec03-self-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"version\":0}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='CHANNEL_SELF_REVIEW_DENIED' "
                        + "AND target_id=?", Integer.class, Long.toString(channelId))).isEqualTo(1);

        String approveKey = "hsec03-approve-" + UUID.randomUUID();
        JsonNode approved = data(finance.withAuth(post("/api/v1/channel-change-requests/" + reqId2 + "/review"))
                .header("Idempotency-Key", approveKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"version\":0,\"comment\":\"财务复核通过\"}"));
        assertThat(approved.at("/status").asText()).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT commission_percent FROM crm_partner_channel WHERE tenant_id=1 AND id=?",
                java.math.BigDecimal.class, channelId)).isEqualByComparingTo("19.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payment_bank_name FROM crm_partner_channel WHERE tenant_id=1 AND id=?",
                String.class, channelId)).isEqualTo("终审开户行");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM crm_partner_channel WHERE tenant_id=1 AND id=?",
                Long.class, channelId)).isEqualTo(3L);
        String approveAudit = jdbcTemplate.queryForObject(
                "SELECT CONCAT_WS('',before_json,after_json) FROM crm_audit_log WHERE tenant_id=1 "
                        + "AND action_code='CHANNEL_FINANCE_CHANGE_APPROVE' AND target_id=?",
                String.class, Long.toString(channelId));
        assertThat(approveAudit).contains("****7777").doesNotContain("6222020200007777", "终审收款户");

        // Idempotent replay on review
        JsonNode replayApprove = data(finance.withAuth(post("/api/v1/channel-change-requests/" + reqId2 + "/review"))
                .header("Idempotency-Key", approveKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"version\":0,\"comment\":\"财务复核通过\"}"));
        assertThat(replayApprove.at("/status").asText()).isEqualTo("APPROVED");

        // 6. Cross-tenant isolation on tenant_id=2 channel
        jdbcTemplate.update("INSERT INTO crm_partner_channel (tenant_id,channel_no,name,channel_type,status) "
                + "VALUES (2,'HSEC03-T2','二租户渠道','BLOGGER','ACTIVE')");
        long t2ChannelId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_partner_channel WHERE tenant_id=2 AND channel_no='HSEC03-T2'", Long.class);
        mockMvc.perform(get("/api/v1/channels/" + t2ChannelId + "/financial-change-requests").cookie(admin.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(admin.withAuth(post("/api/v1/channels/" + t2ChannelId + "/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"version\":0}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(admin.withAuth(post("/api/v1/channels/" + t2ChannelId + "/financial-change-requests"))
                        .header("Idempotency-Key", "hsec03-t2-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(finChange)))
                .andExpect(status().isNotFound());
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
