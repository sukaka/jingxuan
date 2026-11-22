package com.whale.crm.institution;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstitutionSecurityBoundaryApiIT extends AbstractMysqlIT {
    private static final String OLD_BANK = "6222020200008866";
    private static final String OLD_TAX = "91330100SYNTH8866";
    private static final String NEW_TAX = "91330100SYNTH9999";
    private static final String OLD_PHONE = "13800008866";
    private static final Set<String> MANAGER_FORBIDDEN_FIELDS = Set.of(
            "bankname", "banknamemasked", "banknamecipher",
            "bankaccount", "bankaccountmasked", "bankaccountcipher",
            "taxid", "taxidmasked", "taxidcipher",
            "settlementtype", "settlementname", "onlinerate",
            "offlinerate", "platformrate", "otherraterule", "chargesfee", "depositfen",
            "guaranteeroi", "roiterms", "contract", "contracts", "contractid", "contractno",
            "contracttype", "amountfen", "commission", "commissionrate", "fileid",
            "qualification", "qualifications", "licenseno", "licensenocipher");

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AesGcmCipher cipher;

    private AuthCookies business;
    private AuthCookies admin;
    private AuthCookies finance;
    private AuthCookies manager;
    private AuthCookies operator1;
    private AuthCookies operator2;

    @BeforeAll
    void loginActors() throws Exception {
        business = login("business1");
        admin = login("admin");
        finance = login("finance1");
        manager = login("manager1");
        operator1 = login("operator1");
        operator2 = login("operator2");
    }

    @Test
    void taxChangeUsesCiphertextAndNeverExposesNewPlaintextBeforeOrAfterApproval() throws Exception {
        long institutionId = newActiveProfile();
        String oldCipher = profileTaxCipher(institutionId);
        assertAesGcmCipher(oldCipher, OLD_TAX);

        JsonNode request = data(changeRequest(business, institutionId, "taxId", NEW_TAX, 1,
                "IT-TAX-BOUNDARY", key("tax-change")));
        long requestId = request.at("/id").asLong();
        assertThat(request.at("/status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(request.at("/currentStep").asText()).isEqualTo("FINANCE");
        assertThat(request.at("/beforeMasked").isNull()).isTrue();
        assertThat(request.at("/afterMasked").isNull()).isTrue();
        String requestCipher = queryString(
                "SELECT after_cipher FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", requestId);
        assertAesGcmJson(requestCipher, NEW_TAX);
        assertNoSecrets(request.toString(), OLD_TAX, NEW_TAX, oldCipher, requestCipher);
        assertThat(profileTaxCipher(institutionId)).isEqualTo(oldCipher);
        assertThat(profileVersion(institutionId)).isEqualTo(1L);

        JsonNode changeDetail = data(get("/api/v1/institution-change-requests/" + requestId)
                .cookie(finance.cookies()));
        assertThat(changeDetail.at("/beforeMasked/value").asText()).isEqualTo("****8866");
        assertThat(changeDetail.at("/afterMasked/value").asText()).isEqualTo("****9999");
        assertNoSecrets(changeDetail.toString(), OLD_TAX, NEW_TAX, oldCipher, requestCipher);
        JsonNode beforeApproval = data(get("/api/v1/institution-profiles/" + institutionId)
                .cookie(finance.cookies()));
        assertThat(beforeApproval.at("/taxIdMasked").asText()).isEqualTo("****8866");
        assertNoSecrets(beforeApproval.toString(), OLD_TAX, NEW_TAX, oldCipher, requestCipher);
        String requestAudit = latestAudit("INSTITUTION_CHANGE_REQUEST", institutionId);
        assertThat(objectMapper.readTree(requestAudit).at("/id").asLong()).isEqualTo(requestId);
        assertThat(requestAudit).contains("****9999");
        assertNoSecrets(requestAudit, OLD_TAX, NEW_TAX, oldCipher, requestCipher);

        JsonNode approved = review(finance, requestId, "finance-review", 0, "APPROVE",
                key("tax-approve"), 200);
        assertThat(approved.at("/status").asText()).isEqualTo("APPROVED");
        assertNoSecrets(approved.toString(), OLD_TAX, NEW_TAX, oldCipher, requestCipher);
        String formalCipher = profileTaxCipher(institutionId);
        assertThat(formalCipher).isNotEqualTo(oldCipher);
        assertAesGcmCipher(formalCipher, NEW_TAX);
        assertThat(profileVersion(institutionId)).isEqualTo(2L);
        JsonNode afterApproval = data(get("/api/v1/institution-profiles/" + institutionId)
                .cookie(finance.cookies()));
        assertThat(afterApproval.at("/taxIdMasked").asText()).isEqualTo("****9999");
        assertThat(afterApproval.at("/version").asLong()).isEqualTo(2L);
        assertNoSecrets(afterApproval.toString(), OLD_TAX, NEW_TAX, oldCipher, requestCipher, formalCipher);
        String reviewAudit = latestAudit("INSTITUTION_CHANGE_REVIEW", institutionId);
        assertThat(objectMapper.readTree(reviewAudit).at("/id").asLong()).isEqualTo(requestId);
        assertThat(reviewAudit).contains("****9999");
        assertNoSecrets(reviewAudit, OLD_TAX, NEW_TAX, oldCipher, requestCipher, formalCipher);
    }

    @Test
    void marketCannotApproveFinanceOnlyChangeOrAdvanceItsFormalValue() throws Exception {
        long institutionId = newActiveProfile();
        String oldCipher = profileTaxCipher(institutionId);
        JsonNode request = data(changeRequest(admin, institutionId, "taxId", NEW_TAX, 1,
                "IT-FINANCE-ONLY", key("finance-only-request")));
        long requestId = request.at("/id").asLong();
        assertThat(request.at("/currentStep").asText()).isEqualTo("FINANCE");

        review(business, requestId, "market-review", 0, "APPROVE", key("finance-only-market"), 403);

        assertThat(queryString("SELECT status FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", requestId))
                .isEqualTo("PENDING_REVIEW");
        assertThat(queryString("SELECT current_step FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", requestId))
                .isEqualTo("FINANCE");
        assertThat(queryLong("SELECT version FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", requestId))
                .isZero();
        assertThat(profileTaxCipher(institutionId)).isEqualTo(oldCipher);
        assertAesGcmCipher(oldCipher, OLD_TAX);
        assertThat(profileVersion(institutionId)).isEqualTo(1L);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_profile_change_step WHERE change_request_id=?", requestId))
                .isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id LIKE ?",
                "institution.change.reviewed.v1:" + requestId + ":%")).isZero();
    }

    @Test
    void everySensitivePatchIsRejectedWithoutChangingFormalSnapshot() throws Exception {
        long institutionId = newActiveProfile();
        Map<String, Object> before = sensitiveSnapshot(institutionId);
        Map<String, Object> attempts = new LinkedHashMap<>();
        attempts.put("fullName", "PATCH禁止全称");
        attempts.put("businessTypeCode", "DENTAL");
        attempts.put("ownershipCode", "PUBLIC");
        attempts.put("gradeCode", "S");
        attempts.put("settlementType", "PER_REDEMPTION");
        attempts.put("settlementDay", "12");
        attempts.put("onlineRate", new BigDecimal("0.91"));
        attempts.put("offlineRate", new BigDecimal("0.92"));
        attempts.put("platformRate", new BigDecimal("0.93"));
        attempts.put("otherRateRule", "PATCH禁止佣金");
        attempts.put("chargesFee", false);
        attempts.put("depositFen", 999999);
        attempts.put("guaranteeRoi", false);
        attempts.put("roiTerms", "PATCH禁止保投产");
        attempts.put("settlementName", "PATCH禁止结款主体");
        attempts.put("bankName", "PATCH禁止开户行");
        attempts.put("bankAccount", "PATCHBANK9988");
        attempts.put("taxId", "PATCHTAX9988");
        attempts.put("contractNo", "PATCH-CONTRACT-9988");
        attempts.put("fileId", 999999);

        for (Map.Entry<String, Object> attempt : attempts.entrySet()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("version", 1);
            body.put(attempt.getKey(), attempt.getValue());
            MvcResult result = mockMvc.perform(business.withAuth(patch("/api/v1/institution-profiles/" + institutionId))
                            .header("Idempotency-Key", key("sensitive-patch"))
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as(attempt.getKey()).isEqualTo(400);
            JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(error.at("/code").asText()).as(attempt.getKey()).isEqualTo("VALIDATION_FAILED");
            if (attempt.getValue() instanceof String value && value.length() >= 8) {
                assertThat(result.getResponse().getContentAsString()).as(attempt.getKey()).doesNotContain(value);
            }
        }

        assertThat(sensitiveSnapshot(institutionId)).isEqualTo(before);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND action_code='INSTITUTION_PROFILE_UPDATE' AND target_id=?", Long.toString(institutionId))).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 "
                + "AND event_type='institution.profile.updated.v1' AND aggregate_id=?",
                Long.toString(institutionId))).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE tenant_id=1 "
                + "AND scope_code='institution.profile.update' AND resource_id=?",
                Long.toString(institutionId))).isZero();
    }

    @Test
    void h03SelfReviewIsIndependentlyAuditedAndDoesNotAdvanceRequest() throws Exception {
        long institutionId = newActiveProfile();
        JsonNode request = data(changeRequest(business, institutionId, "fullName", "自审禁止生效", 1,
                "IT-H03-SELF", key("self-request")));
        long requestId = request.at("/id").asLong();
        String reviewKey = key("self-review");

        mockMvc.perform(business.withAuth(post("/api/v1/institution-change-requests/" + requestId + "/market-review"))
                        .header("Idempotency-Key", reviewKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "decision", "APPROVE", "comment", "IT-H03-SELF"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(queryString("SELECT status FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", requestId))
                .isEqualTo("PENDING_REVIEW");
        assertThat(queryString("SELECT current_step FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", requestId))
                .isEqualTo("MARKET");
        assertThat(queryLong("SELECT version FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", requestId))
                .isZero();
        assertThat(queryString("SELECT full_name FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId))
                .doesNotContain("自审禁止生效");
        assertThat(profileVersion(institutionId)).isEqualTo(1L);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_profile_change_step WHERE change_request_id=?", requestId))
                .isEqualTo(1);
        assertThat(queryString("SELECT step_code FROM crm_profile_change_step WHERE change_request_id=?", requestId))
                .isEqualTo("REQUESTED");
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id LIKE ?",
                "institution.change.reviewed.v1:" + requestId + ":%")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE tenant_id=1 "
                + "AND scope_code='institution.change.review' AND idempotency_key=?", reviewKey)).isZero();

        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "SELECT actor_user_id,after_json,reason,trace_id FROM crm_audit_log WHERE tenant_id=1 "
                        + "AND action_code='INSTITUTION_SELF_REVIEW_DENIED' AND target_id=?",
                Long.toString(institutionId));
        assertThat(audits).hasSize(1);
        Map<String, Object> audit = audits.get(0);
        assertThat(((Number) audit.get("actor_user_id")).longValue()).isEqualTo(6L);
        assertThat(objectMapper.readTree((String) audit.get("after_json")).at("/id").asLong()).isEqualTo(requestId);
        assertThat((String) audit.get("reason")).contains("申请人不能审核本人");
        assertThat((String) audit.get("trace_id")).isNotBlank();
    }

    @Test
    void idempotentReplayAndFinanceRejectionCannotCreateOrApplyTwice() throws Exception {
        long institutionId = newActiveProfile();
        String createKey = key("reject-create");
        Map<String, Object> body = changeBody(institutionId, "onlineRate", new BigDecimal("0.27"), 1,
                "IT-REJECT-IDEMPOTENT");
        JsonNode created = data(changeRequest(business, body, createKey));
        JsonNode replayed = data(changeRequest(business, body, createKey));
        long requestId = created.at("/id").asLong();
        assertThat(replayed.at("/id").asLong()).isEqualTo(requestId);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_profile_change_request WHERE tenant_id=1 "
                + "AND institution_id=? AND change_type='PROFILE_FIELD' AND field_code='onlineRate'", institutionId))
                .isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?",
                "institution.change.requested.v1:" + requestId + ":0")).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_idempotency_record WHERE tenant_id=1 "
                + "AND scope_code='institution.profile.field-change' AND idempotency_key=?", createKey)).isEqualTo(1);

        Map<String, Object> different = changeBody(institutionId, "onlineRate", new BigDecimal("0.28"), 1,
                "IT-REJECT-IDEMPOTENT");
        mockMvc.perform(changeRequest(business, different, createKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(queryInt("SELECT COUNT(*) FROM crm_profile_change_request WHERE tenant_id=1 "
                + "AND institution_id=? AND change_type='PROFILE_FIELD' AND field_code='onlineRate'", institutionId))
                .isEqualTo(1);

        review(admin, requestId, "market-review", 0, "APPROVE", key("reject-market"), 200);
        assertThat(onlineRate(institutionId)).isEqualByComparingTo("0.18");
        String rejectKey = key("finance-reject");
        JsonNode rejected = review(finance, requestId, "finance-review", 1, "REJECT", rejectKey, 200);
        assertThat(rejected.at("/status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.at("/version").asLong()).isEqualTo(2L);
        assertThat(onlineRate(institutionId)).isEqualByComparingTo("0.18");
        assertThat(profileVersion(institutionId)).isEqualTo(1L);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_profile_change_step WHERE change_request_id=?", requestId))
                .isEqualTo(3);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id LIKE ?",
                "institution.change.reviewed.v1:" + requestId + ":%")).isEqualTo(2);

        JsonNode rejectReplay = review(finance, requestId, "finance-review", 1, "REJECT", rejectKey, 200);
        assertThat(rejectReplay.at("/status").asText()).isEqualTo("REJECTED");
        review(finance, requestId, "finance-review", 2, "APPROVE", key("approve-rejected"), 409);
        assertThat(queryString("SELECT status FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", requestId))
                .isEqualTo("REJECTED");
        assertThat(queryLong("SELECT version FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", requestId))
                .isEqualTo(2L);
        assertThat(onlineRate(institutionId)).isEqualByComparingTo("0.18");
        assertThat(profileVersion(institutionId)).isEqualTo(1L);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_profile_change_step WHERE change_request_id=?", requestId))
                .isEqualTo(3);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id LIKE ?",
                "institution.change.reviewed.v1:" + requestId + ":%")).isEqualTo(2);
        String rejectionAudit = latestAudit("INSTITUTION_CHANGE_REVIEW", institutionId);
        JsonNode audit = objectMapper.readTree(rejectionAudit);
        assertThat(audit.at("/id").asLong()).isEqualTo(requestId);
        assertThat(audit.at("/status").asText()).isEqualTo("REJECTED");
    }

    @Test
    void ordinaryPatchIsIdempotentOptimisticallyLockedAndPhoneMasked() throws Exception {
        long institutionId = newActiveProfile();
        String oldPhoneCipher = queryString(
                "SELECT contact_phone_cipher FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId);
        assertAesGcmCipher(oldPhoneCipher, OLD_PHONE);
        String newPhone = "13900009999";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 1);
        body.put("shortName", "普通资料新简称");
        body.put("cityCode", "HZ");
        body.put("address", "合成地址已更新");
        body.put("contactName", "合成联系人已更新");
        body.put("contactPhone", newPhone);
        body.put("introduction", "普通介绍已更新");
        String updateKey = key("ordinary-update");

        JsonNode updated = data(business.withAuth(patch("/api/v1/institution-profiles/" + institutionId))
                .header("Idempotency-Key", updateKey)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
        assertThat(updated.at("/version").asLong()).isEqualTo(2L);
        assertThat(updated.at("/shortName").asText()).isEqualTo("普通资料新简称");
        assertThat(updated.at("/cityCode").asText()).isEqualTo("HZ");
        assertThat(updated.at("/contactPhoneMasked").asText()).isEqualTo("****9999");
        String newPhoneCipher = queryString(
                "SELECT contact_phone_cipher FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId);
        assertAesGcmCipher(newPhoneCipher, newPhone);
        assertNoSecrets(updated.toString(), OLD_PHONE, newPhone, oldPhoneCipher, newPhoneCipher);
        String updateAudit = latestAudit("INSTITUTION_PROFILE_UPDATE", institutionId);
        assertThat(updateAudit).contains("****9999");
        assertNoSecrets(updateAudit, OLD_PHONE, newPhone, oldPhoneCipher, newPhoneCipher);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_id=?",
                "institution.profile.updated.v1:" + institutionId + ":2")).isEqualTo(1);

        JsonNode replayed = data(business.withAuth(patch("/api/v1/institution-profiles/" + institutionId))
                .header("Idempotency-Key", updateKey)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
        assertThat(replayed.at("/version").asLong()).isEqualTo(2L);
        assertThat(profileVersion(institutionId)).isEqualTo(2L);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND action_code='INSTITUTION_PROFILE_UPDATE' AND target_id=?", Long.toString(institutionId)))
                .isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 "
                + "AND event_type='institution.profile.updated.v1' AND aggregate_id=?",
                Long.toString(institutionId))).isEqualTo(1);

        Map<String, Object> sameKeyDifferentBody = new LinkedHashMap<>(body);
        sameKeyDifferentBody.put("introduction", "禁止换体重放");
        mockMvc.perform(business.withAuth(patch("/api/v1/institution-profiles/" + institutionId))
                        .header("Idempotency-Key", updateKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(sameKeyDifferentBody)))
                .andExpect(status().isConflict());
        Map<String, Object> stale = new LinkedHashMap<>(body);
        stale.put("introduction", "旧版本禁止覆盖");
        mockMvc.perform(business.withAuth(patch("/api/v1/institution-profiles/" + institutionId))
                        .header("Idempotency-Key", key("ordinary-stale"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(queryString("SELECT introduction FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId))
                .isEqualTo("普通介绍已更新");
        assertThat(profileVersion(institutionId)).isEqualTo(2L);
    }

    @Test
    void institutionUserCanSubmitOnlyOwnInstitutionAndCannotReadOtherRequest() throws Exception {
        long institutionId = newActiveProfile();
        jdbcTemplate.update("INSERT INTO crm_institution_user (tenant_id,institution_id,user_id,institution_role,status) "
                + "VALUES (1,?,10,'OPERATOR','ACTIVE')", institutionId);
        String crossPlaintext = "6222000000007777";
        String originalBankCipher = queryString(
                "SELECT bank_account_cipher FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId);
        assertAesGcmCipher(originalBankCipher, OLD_BANK);
        Map<String, Object> body = changeBody(institutionId, "bankAccount", crossPlaintext, 1, "IT-CROSS-SCOPE");

        MvcResult denied = mockMvc.perform(changeRequest(operator2, body, key("cross-denied"))).andReturn();
        assertThat(denied.getResponse().getStatus()).isIn(403, 404);
        assertThat(denied.getResponse().getContentAsString()).doesNotContain(crossPlaintext);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_profile_change_request WHERE tenant_id=1 "
                + "AND institution_id=? AND change_type='PROFILE_FIELD'", institutionId)).isZero();

        JsonNode own = data(changeRequest(operator1, body, key("own-scope")));
        long requestId = own.at("/id").asLong();
        assertThat(own.at("/beforeMasked").isNull()).isTrue();
        assertThat(own.at("/afterMasked").isNull()).isTrue();
        assertThat(own.toString()).doesNotContain(crossPlaintext);
        JsonNode financeView = data(get("/api/v1/institution-change-requests/" + requestId)
                .cookie(finance.cookies()));
        assertThat(financeView.at("/afterMasked/value").asText()).isEqualTo("****7777");
        assertThat(queryLong("SELECT requested_by FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", requestId))
                .isEqualTo(10L);
        mockMvc.perform(get("/api/v1/institution-change-requests/" + requestId).cookie(operator2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/institution-change-requests?institutionId=" + institutionId
                                + "&status=PENDING_REVIEW").cookie(operator2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        mockMvc.perform(get("/api/v1/institution-change-requests?institutionId=" + institutionId
                                + "&status=PENDING_REVIEW").cookie(operator1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(requestId));
        assertThat(profileVersion(institutionId)).isEqualTo(1L);
        String unchangedBankCipher = queryString(
                "SELECT bank_account_cipher FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId);
        assertThat(unchangedBankCipher).isEqualTo(originalBankCipher);
        assertAesGcmCipher(unchangedBankCipher, OLD_BANK);
    }

    @Test
    void otherTenantProfileCannotBeReadListedOrChanged() throws Exception {
        String institutionNo = "IT-TENANT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("INSERT INTO crm_institution (tenant_id,institution_no,full_name,status,settlement_type) "
                + "VALUES (2,?,?,'ACTIVE','MONTHLY')", institutionNo, "其他租户合成机构");
        long institutionId = queryLong(
                "SELECT id FROM crm_institution WHERE tenant_id=2 AND institution_no=?", institutionNo);

        mockMvc.perform(get("/api/v1/institution-profiles/" + institutionId).cookie(admin.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/institution-profiles?keyword=" + institutionNo).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        mockMvc.perform(changeRequest(business, institutionId, "onlineRate", new BigDecimal("0.66"), 0,
                        "IT-CROSS-TENANT", key("cross-tenant")))
                .andExpect(status().isNotFound());
        assertThat(queryInt("SELECT COUNT(*) FROM crm_profile_change_request WHERE tenant_id=2 AND institution_id=?",
                institutionId)).isZero();
        assertThat(queryString("SELECT full_name FROM crm_institution WHERE tenant_id=2 AND id=?", institutionId))
                .isEqualTo("其他租户合成机构");
    }

    @Test
    void managerOnePagerOmitsBankTaxContractAndFinancialValues() throws Exception {
        long institutionId = newActiveProfile();
        String institutionNo = queryString(
                "SELECT institution_no FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId);
        String bankCipher = queryString(
                "SELECT bank_account_cipher FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId);
        String taxCipher = profileTaxCipher(institutionId);
        String bankNameCipher = queryString(
                "SELECT bank_name_cipher FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId);

        JsonNode detail = root(get("/api/v1/institution-profiles/" + institutionId).cookie(manager.cookies()));
        assertThat(detail.at("/data/id").asLong()).isEqualTo(institutionId);
        assertThat(detail.at("/data/fullName").asText()).contains("合成机构");
        assertThat(detail.at("/data/introduction").asText()).isEqualTo("初始介绍");
        assertManagerFields(detail);
        assertNoSecrets(detail.toString(), OLD_BANK, OLD_TAX, "合成结款主体", "合成银行", "初始保投产",
                bankCipher, taxCipher, bankNameCipher);

        JsonNode search = root(get("/api/v1/institution-profiles?keyword=" + institutionNo)
                .cookie(manager.cookies()));
        assertThat(search.at("/data/total").asLong()).isEqualTo(1L);
        assertThat(search.at("/data/items/0/id").asLong()).isEqualTo(institutionId);
        assertManagerFields(search);
        assertNoSecrets(search.toString(), OLD_BANK, OLD_TAX, "合成结款主体", "合成银行", "初始保投产",
                bankCipher, taxCipher, bankNameCipher);
    }

    @Test
    void hSec09Part3ChangeRequestResponsesRedactCommercialAndFinanceFieldsByRole() throws Exception {
        long institutionId = newActiveProfile();
        AuthCookies operation = login("operation1");
        long evidenceFileId = insertFileObject(1L, "CHANGE_EVIDENCE", institutionId, "CLEAN");
        long contractFileId = insertFileObject(1L, "CONTRACT", institutionId, "CLEAN");

        JsonNode rateReq = data(changeRequest(business, institutionId, "onlineRate", new BigDecimal("0.21"), 1,
                "IT-HSEC09-RATE", key("hsec09-rate")));
        long rateReqId = rateReq.at("/id").asLong();

        JsonNode bankReq = data(changeRequest(business, Map.of(
                "institutionId", institutionId,
                "fieldCode", "bankAccount",
                "newValue", "6222000000005555",
                "baseVersion", 1,
                "reason", "IT-HSEC09-BANK",
                "evidenceFileId", evidenceFileId), key("hsec09-bank")));
        long bankReqId = bankReq.at("/id").asLong();
        assertThat(bankReq.at("/beforeMasked").isNull()).isTrue();
        assertThat(bankReq.at("/afterMasked").isNull()).isTrue();
        assertThat(bankReq.at("/evidenceFileId").isNull()).isTrue();

        JsonNode dayReq = data(changeRequest(business, institutionId, "settlementDay", "15", 1,
                "IT-HSEC09-DAY", key("hsec09-day")));
        long dayReqId = dayReq.at("/id").asLong();

        long contractId = data(business.withAuth(post("/api/v1/institution-contracts"))
                .header("Idempotency-Key", key("hsec09-contract"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", institutionId, "contractNo",
                        "IT-HSEC09-" + UUID.randomUUID().toString().substring(0, 8),
                        "title", "边界测试合同", "contractType", "SERVICE", "startDate", "2026-01-01",
                        "fileId", contractFileId, "amountFen", 10000))))
                .at("/id").asLong();
        long contractReqId = data(business.withAuth(post("/api/v1/institution-contracts/" + contractId + "/activation-requests"))
                .header("Idempotency-Key", key("hsec09-contract-act"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseVersion", 0, "reason", "IT-HSEC09-CONTRACT",
                        "evidenceFileId", evidenceFileId))))
                .at("/id").asLong();

        // MANAGER sees null for commercial & finance fields and null evidenceFileId on CONTRACT_ACTIVATION,
        // while settlementDay remains visible; full response contains zero forbidden fields.
        JsonNode managerList = root(get("/api/v1/institution-change-requests?institutionId=" + institutionId)
                .cookie(manager.cookies()));
        assertManagerFields(managerList);
        assertNoSecrets(managerList.toString(), OLD_BANK, "6222000000005555", "****8866", "****5555");
        JsonNode managerRate = data(get("/api/v1/institution-change-requests/" + rateReqId).cookie(manager.cookies()));
        assertThat(managerRate.at("/beforeMasked").isNull()).isTrue();
        assertThat(managerRate.at("/afterMasked").isNull()).isTrue();
        assertThat(managerRate.at("/evidenceFileId").isNull()).isTrue();
        JsonNode managerBank = data(get("/api/v1/institution-change-requests/" + bankReqId).cookie(manager.cookies()));
        assertThat(managerBank.at("/beforeMasked").isNull()).isTrue();
        assertThat(managerBank.at("/afterMasked").isNull()).isTrue();
        assertThat(managerBank.at("/evidenceFileId").isNull()).isTrue();
        JsonNode managerDay = data(get("/api/v1/institution-change-requests/" + dayReqId).cookie(manager.cookies()));
        assertThat(managerDay.at("/afterMasked/value").asText()).isEqualTo("15");
        JsonNode managerContract = data(get("/api/v1/institution-change-requests/" + contractReqId)
                .cookie(manager.cookies()));
        assertThat(managerContract.at("/afterMasked/status").asText()).isEqualTo("ACTIVE");
        assertThat(managerContract.at("/evidenceFileId").isNull()).isTrue();

        // OPERATION (!finance) sees commercial onlineRate, but NOT finance bankAccount
        JsonNode opRate = data(get("/api/v1/institution-change-requests/" + rateReqId).cookie(operation.cookies()));
        assertThat(opRate.at("/afterMasked/value").asText()).isEqualTo("0.21");
        JsonNode opBank = data(get("/api/v1/institution-change-requests/" + bankReqId).cookie(operation.cookies()));
        assertThat(opBank.at("/beforeMasked").isNull()).isTrue();
        assertThat(opBank.at("/afterMasked").isNull()).isTrue();
        assertThat(opBank.at("/evidenceFileId").isNull()).isTrue();

        // FINANCE sees masked bankAccount + evidenceFileId
        JsonNode finBank = data(get("/api/v1/institution-change-requests/" + bankReqId).cookie(finance.cookies()));
        assertThat(finBank.at("/beforeMasked/value").asText()).isEqualTo("****8866");
        assertThat(finBank.at("/afterMasked/value").asText()).isEqualTo("****5555");
        assertThat(finBank.at("/evidenceFileId").asLong()).isEqualTo(evidenceFileId);
    }

    @Test
    void hBug06ApprovingSettlementNameAndBooleanFieldsPersistsPlaintextAndTinyint() throws Exception {
        long institutionId = newActiveProfile();

        // 1. settlementName -> plaintext stored in crm_institution.settlement_name
        long nameReqId = data(changeRequest(business, institutionId, "settlementName", "新版结款主体明文", 1,
                "IT-HBUG06-NAME", key("hbug06-name"))).at("/id").asLong();
        review(finance, nameReqId, "finance-review", 0, "APPROVE", key("hbug06-name-approve"), 200);
        assertThat(queryString("SELECT settlement_name FROM crm_institution WHERE tenant_id=1 AND id=?",
                institutionId)).isEqualTo("新版结款主体明文");

        // 2. chargesFee -> false stored as 0 in TINYINT(1)
        long feeReqId = data(changeRequest(business, institutionId, "chargesFee", false, 2,
                "IT-HBUG06-FEE", key("hbug06-fee"))).at("/id").asLong();
        review(admin, feeReqId, "market-review", 0, "APPROVE", key("hbug06-fee-market"), 200);
        review(finance, feeReqId, "finance-review", 1, "APPROVE", key("hbug06-fee-finance"), 200);
        assertThat(queryInt("SELECT charges_fee FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId))
                .isZero();

        // 3. guaranteeRoi -> false stored as 0, then true stored as 1 in TINYINT(1)
        long roiReqId = data(changeRequest(business, institutionId, "guaranteeRoi", false, 3,
                "IT-HBUG06-ROI-0", key("hbug06-roi-0"))).at("/id").asLong();
        review(admin, roiReqId, "market-review", 0, "APPROVE", key("hbug06-roi0-market"), 200);
        review(finance, roiReqId, "finance-review", 1, "APPROVE", key("hbug06-roi0-finance"), 200);
        assertThat(queryInt("SELECT guarantee_roi FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId))
                .isZero();

        long roiTrueReqId = data(changeRequest(business, institutionId, "guaranteeRoi", true, 4,
                "IT-HBUG06-ROI-1", key("hbug06-roi-1"))).at("/id").asLong();
        review(admin, roiTrueReqId, "market-review", 0, "APPROVE", key("hbug06-roi1-market"), 200);
        review(finance, roiTrueReqId, "finance-review", 1, "APPROVE", key("hbug06-roi1-finance"), 200);
        assertThat(queryInt("SELECT guarantee_roi FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId))
                .isEqualTo(1);

        JsonNode profile = data(get("/api/v1/institution-profiles/" + institutionId).cookie(finance.cookies()));
        assertThat(profile.at("/settlementName").asText()).isEqualTo("新版结款主体明文");
        assertThat(profile.at("/chargesFee").asBoolean()).isFalse();
        assertThat(profile.at("/guaranteeRoi").asBoolean()).isTrue();
    }

    @Test
    void mBe01RealFileInstitutionOperationsEnforcesOwnershipClassificationScanStatusAndTenant() throws Exception {
        long inst1 = newActiveProfile();
        long inst2 = newActiveProfile();

        long inst1ContractClean = insertFileObject(1L, "CONTRACT", inst1, "CLEAN");
        long inst1QualClean = insertFileObject(1L, "QUALIFICATION", inst1, "CLEAN");
        long inst1EvidenceClean = insertFileObject(1L, "CHANGE_EVIDENCE", inst1, "CLEAN");
        long inst1ContractPending = insertFileObject(1L, "CONTRACT", inst1, "PENDING");
        long inst2ContractClean = insertFileObject(1L, "CONTRACT", inst2, "CLEAN");
        long inst2EvidenceClean = insertFileObject(1L, "CHANGE_EVIDENCE", inst2, "CLEAN");
        long tenant2File = insertFileObject(2L, "CONTRACT", null, "CLEAN");

        // Wrong institution -> 403 FORBIDDEN
        mockMvc.perform(business.withAuth(post("/api/v1/institution-contracts"))
                        .header("Idempotency-Key", key("mbe01-wrong-inst-contract"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "contractNo", "MBE01-1",
                                "title", "越权机构合同", "contractType", "SERVICE", "startDate", "2026-01-01",
                                "fileId", inst2ContractClean, "amountFen", 100))))
                .andExpect(status().isForbidden());
        mockMvc.perform(changeRequest(business, Map.of(
                        "institutionId", inst1, "fieldCode", "fullName", "newValue", "跨机构凭证",
                        "baseVersion", 1, "reason", "IT-MBE01", "evidenceFileId", inst2EvidenceClean),
                key("mbe01-wrong-inst-evidence")))
                .andExpect(status().isForbidden());

        // Wrong classification -> 409 CONFLICT
        mockMvc.perform(business.withAuth(post("/api/v1/institution-contracts"))
                        .header("Idempotency-Key", key("mbe01-wrong-class-contract"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "contractNo", "MBE01-2",
                                "title", "错类合同", "contractType", "SERVICE", "startDate", "2026-01-01",
                                "fileId", inst1QualClean, "amountFen", 100))))
                .andExpect(status().isConflict());
        mockMvc.perform(business.withAuth(post("/api/v1/institution-qualifications"))
                        .header("Idempotency-Key", key("mbe01-wrong-class-qual"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "qualificationType", "BUSINESS_LICENSE",
                                "title", "错类资质", "licenseNo", "LIC-1", "fileId", inst1ContractClean))))
                .andExpect(status().isConflict());
        mockMvc.perform(changeRequest(business, Map.of(
                        "institutionId", inst1, "fieldCode", "fullName", "newValue", "错类凭证",
                        "baseVersion", 1, "reason", "IT-MBE01", "evidenceFileId", inst1ContractClean),
                key("mbe01-wrong-class-evidence")))
                .andExpect(status().isConflict());

        // Non-CLEAN (PENDING) -> 409 CONFLICT
        mockMvc.perform(business.withAuth(post("/api/v1/institution-contracts"))
                        .header("Idempotency-Key", key("mbe01-pending"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "contractNo", "MBE01-3",
                                "title", "未扫描合同", "contractType", "SERVICE", "startDate", "2026-01-01",
                                "fileId", inst1ContractPending, "amountFen", 100))))
                .andExpect(status().isConflict());

        // Cross-tenant file -> 404 NOT_FOUND
        mockMvc.perform(business.withAuth(post("/api/v1/institution-contracts"))
                        .header("Idempotency-Key", key("mbe01-cross-tenant"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "contractNo", "MBE01-4",
                                "title", "跨租户文件", "contractType", "SERVICE", "startDate", "2026-01-01",
                                "fileId", tenant2File, "amountFen", 100))))
                .andExpect(status().isNotFound());

        // Valid matching institutionId + FileClassification + CLEAN -> 201
        mockMvc.perform(business.withAuth(post("/api/v1/institution-contracts"))
                        .header("Idempotency-Key", key("mbe01-valid-contract"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "contractNo", "MBE01-OK-" + UUID.randomUUID().toString().substring(0, 6),
                                "title", "合法合同", "contractType", "SERVICE", "startDate", "2026-01-01",
                                "fileId", inst1ContractClean, "amountFen", 100))))
                .andExpect(status().isCreated());
        mockMvc.perform(business.withAuth(post("/api/v1/institution-qualifications"))
                        .header("Idempotency-Key", key("mbe01-valid-qual"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "qualificationType", "BUSINESS_LICENSE",
                                "title", "合法资质", "licenseNo", "LIC-OK", "fileId", inst1QualClean))))
                .andExpect(status().isCreated());
        mockMvc.perform(changeRequest(business, Map.of(
                        "institutionId", inst1, "fieldCode", "fullName", "newValue", "合法凭证名称",
                        "baseVersion", 1, "reason", "IT-MBE01-OK", "evidenceFileId", inst1EvidenceClean),
                key("mbe01-valid-evidence")))
                .andExpect(status().isCreated());
    }

    private long insertFileObject(long tenantId, String classification, Long institutionId, String scanStatus) {
        String objectKey = "sealed/" + tenantId + "/it-mbe01-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO crm_file_object (tenant_id,object_key,mime_type,size_bytes,sha256,"
                        + "classification,institution_id,scan_status,uploaded_by) VALUES (?,?,?,?,?,?,?,?,?)",
                tenantId, objectKey, "application/pdf", 128L,
                "0000000000000000000000000000000000000000000000000000000000000000",
                classification, institutionId, scanStatus, 1L);
        return queryLong("SELECT id FROM crm_file_object WHERE tenant_id=? AND object_key=?", tenantId, objectKey);
    }

    private long newActiveProfile() throws Exception {
        long id = newProfile();
        JsonNode activation = data(business.withAuth(post("/api/v1/institution-profiles/" + id + "/activation-requests"))
                .header("Idempotency-Key", key("activate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseVersion", 0, "reason", "IT-BOUNDARY-ACTIVATE"))));
        long requestId = activation.at("/id").asLong();
        review(admin, requestId, "market-review", 0, "APPROVE", key("activate-market"), 200);
        review(finance, requestId, "finance-review", 1, "APPROVE", key("activate-finance"), 200);
        assertThat(profileVersion(id)).isEqualTo(1L);
        return id;
    }

    private long newProfile() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> body = Map.ofEntries(
                Map.entry("institutionNo", "IT-BOUNDARY-" + suffix),
                Map.entry("fullName", "合成机构-" + suffix),
                Map.entry("shortName", "合成院"),
                Map.entry("cityCode", "BJ"),
                Map.entry("businessTypeCode", "MEDICAL_BEAUTY"),
                Map.entry("ownershipCode", "PRIVATE"),
                Map.entry("gradeCode", "A"),
                Map.entry("address", "初始地址"),
                Map.entry("contactName", "合成联系人"),
                Map.entry("contactPhone", OLD_PHONE),
                Map.entry("settlementType", "MONTHLY"),
                Map.entry("onlineRate", new BigDecimal("0.18")),
                Map.entry("offlineRate", new BigDecimal("0.12")),
                Map.entry("platformRate", new BigDecimal("0.15")),
                Map.entry("otherRateRule", "初始其他佣金"),
                Map.entry("chargesFee", true),
                Map.entry("depositFen", 123456),
                Map.entry("guaranteeRoi", true),
                Map.entry("roiTerms", "初始保投产"),
                Map.entry("settlementName", "合成结款主体"),
                Map.entry("bankName", "合成银行"),
                Map.entry("bankAccount", OLD_BANK),
                Map.entry("taxId", OLD_TAX),
                Map.entry("introduction", "初始介绍"));
        return data(business.withAuth(post("/api/v1/institution-profiles"))
                .header("Idempotency-Key", key("create-profile"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).at("/id").asLong();
    }

    private MockHttpServletRequestBuilder changeRequest(AuthCookies actor, long institutionId, String fieldCode,
                                                         Object newValue, long baseVersion, String reason,
                                                         String idempotencyKey) throws Exception {
        return changeRequest(actor, changeBody(institutionId, fieldCode, newValue, baseVersion, reason), idempotencyKey);
    }

    private MockHttpServletRequestBuilder changeRequest(AuthCookies actor, Map<String, Object> body,
                                                         String idempotencyKey) throws Exception {
        return actor.withAuth(post("/api/v1/institution-change-requests"))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    private Map<String, Object> changeBody(long institutionId, String fieldCode, Object newValue,
                                           long baseVersion, String reason) {
        return Map.of("institutionId", institutionId, "fieldCode", fieldCode, "newValue", newValue,
                "baseVersion", baseVersion, "reason", reason);
    }

    private JsonNode review(AuthCookies actor, long requestId, String action, long version,
                            String decision, String key, int expectedStatus) throws Exception {
        String response = mockMvc.perform(actor.withAuth(
                                post("/api/v1/institution-change-requests/" + requestId + "/" + action))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", version, "decision", decision, "comment", "IT-BOUNDARY-REVIEW"))))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private JsonNode root(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        return root(request).at("/data");
    }

    private String latestAudit(String action, long institutionId) {
        return queryString("SELECT after_json FROM crm_audit_log WHERE tenant_id=1 AND action_code=? "
                + "AND target_type='INSTITUTION' AND target_id=? ORDER BY id DESC LIMIT 1",
                action, Long.toString(institutionId));
    }

    private Map<String, Object> sensitiveSnapshot(long institutionId) {
        return jdbcTemplate.queryForMap("SELECT full_name,business_type_code,ownership_code,grade_code,settlement_type,settlement_day,"
                + "online_rate,offline_rate,platform_rate,other_rate_rule,charges_fee,deposit_fen,guarantee_roi,"
                + "roi_terms,settlement_name,bank_name_cipher,bank_account_cipher,tax_id_cipher,version "
                + "FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId);
    }

    private String profileTaxCipher(long institutionId) {
        return queryString("SELECT tax_id_cipher FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId);
    }

    private long profileVersion(long institutionId) {
        return queryLong("SELECT version FROM crm_institution WHERE tenant_id=1 AND id=?", institutionId);
    }

    private BigDecimal onlineRate(long institutionId) {
        return jdbcTemplate.queryForObject("SELECT online_rate FROM crm_institution WHERE tenant_id=1 AND id=?",
                BigDecimal.class, institutionId);
    }

    private void assertAesGcmCipher(String encrypted, String expectedPlaintext) {
        assertThat(encrypted).isNotBlank().isNotEqualTo(expectedPlaintext);
        assertThat(Base64.getDecoder().decode(encrypted).length).isGreaterThan(28);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(expectedPlaintext);
    }

    private void assertAesGcmJson(String encrypted, String expectedPlaintext) throws Exception {
        assertThat(encrypted).isNotBlank().isNotEqualTo(expectedPlaintext);
        assertThat(Base64.getDecoder().decode(encrypted).length).isGreaterThan(28);
        assertThat(objectMapper.readTree(cipher.decrypt(encrypted)).asText()).isEqualTo(expectedPlaintext);
    }

    private void assertNoSecrets(String visible, String... secrets) {
        assertThat(visible).doesNotContain(secrets);
    }

    private void assertManagerFields(JsonNode root) {
        List<String> violations = new ArrayList<>();
        collectManagerViolations(root, "", violations);
        assertThat(violations).isEmpty();
    }

    private void collectManagerViolations(JsonNode node, String path, List<String> violations) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectManagerViolations(node.get(i), path + "[" + i + "]", violations);
            }
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldPath = path + "/" + field.getKey();
            if (MANAGER_FORBIDDEN_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT))
                    && !field.getValue().isNull()) {
                violations.add(fieldPath + "=" + field.getValue());
            }
            collectManagerViolations(field.getValue(), fieldPath, violations);
        }
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return "it-boundary-" + prefix + "-" + UUID.randomUUID();
    }
}
