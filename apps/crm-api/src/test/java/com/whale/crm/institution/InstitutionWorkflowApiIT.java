package com.whale.crm.institution;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.institution.application.InstitutionFileOperations;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(InstitutionWorkflowApiIT.FileConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstitutionWorkflowApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AesGcmCipher cipher;

    @BeforeAll
    void createCleanFileFixtures() {
        for (long fileId : List.of(9001L, 9002L, 9003L)) {
            String classification = fileId == 9001L ? "CONTRACT" : fileId == 9002L ? "QUALIFICATION" : "CHANGE_EVIDENCE";
            jdbcTemplate.update("INSERT INTO crm_file_object (id,tenant_id,object_key,mime_type,size_bytes,sha256,"
                            + "classification,institution_id,scan_status,uploaded_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    fileId, 1, "it-file-" + fileId + "-" + UUID.randomUUID(), "application/pdf", 1,
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    classification, 1, "CLEAN", 1);
        }
    }

    @Test
    void seedIsMaskedAndOnboardingRequiresMarketThenFinance() throws Exception {
        AuthCookies finance = login("finance1");
        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/institution-profiles/1").cookie(finance.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlineRate").value(0.18))
                .andExpect(jsonPath("$.data.bankAccountMasked").value("****8866"));
        mockMvc.perform(get("/api/v1/institution-profiles/1").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlineRate").doesNotExist())
                .andExpect(jsonPath("$.data.bankAccountMasked").doesNotExist())
                .andExpect(jsonPath("$.data.taxIdMasked").doesNotExist())
                .andExpect(jsonPath("$.data.settlementType").doesNotExist());
        AuthCookies otherInstitution = login("operator2");
        mockMvc.perform(get("/api/v1/institution-profiles/1").cookie(otherInstitution.cookies()))
                .andExpect(status().isNotFound());

        AuthCookies business = login("business1");
        AuthCookies admin = login("admin");
        long id = newProfile(business);
        mockMvc.perform(business.withAuth(patch("/api/v1/institution-profiles/" + id))
                        .header("Idempotency-Key", key("sensitive-patch"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"onlineRate\":0.20}"))
                .andExpect(status().isBadRequest());
        JsonNode activation = data(business.withAuth(post("/api/v1/institution-profiles/" + id + "/activation-requests"))
                .header("Idempotency-Key", key("activation"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseVersion", 0, "reason", "IT-ONBOARD"))));
        long requestId = activation.at("/id").asLong();
        review(finance, requestId, "finance-review", 0, "APPROVE", key("activation-finance-first"), 403);
        review(admin, requestId, "market-review", 0, "APPROVE", key("activation-market"), 200);
        review(finance, requestId, "finance-review", 1, "APPROVE", key("activation-finance"), 200);
        mockMvc.perform(get("/api/v1/institution-profiles/" + id).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_profile_change_step WHERE change_request_id=?",
                Integer.class, requestId)).isEqualTo(3);
    }

    @Test
    void h01BankChangeKeepsOldValueUntilFinanceApprovalAndAuditIsMasked() throws Exception {
        String oldValue = "6222020200008866";
        String newValue = "6222000000009999";
        AuthCookies business = login("business1");
        AuthCookies finance = login("finance1");
        AuthCookies admin = login("admin");
        long institutionId = newActiveProfile(business, admin, finance);
        String oldCipher = jdbcTemplate.queryForObject(
                "SELECT bank_account_cipher FROM crm_institution WHERE tenant_id=1 AND id=?", String.class, institutionId);
        assertThat(cipher.decrypt(oldCipher)).isEqualTo(oldValue);

        JsonNode request = data(business.withAuth(post("/api/v1/institution-change-requests"))
                .header("Idempotency-Key", key("bank-change"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", institutionId, "fieldCode", "bankAccount",
                        "newValue", newValue, "baseVersion", 1,
                        "reason", "IT-BANK", "evidenceFileId", 9001))));
        long requestId = request.at("/id").asLong();
        assertThat(request.at("/beforeMasked/value").asText()).isEqualTo("****8866");
        assertThat(request.at("/afterMasked/value").asText()).isEqualTo("****9999");
        String requestCipher = jdbcTemplate.queryForObject(
                "SELECT after_cipher FROM crm_profile_change_request WHERE tenant_id=1 AND id=?", String.class, requestId);
        assertThat(objectMapper.readTree(cipher.decrypt(requestCipher)).asText()).isEqualTo(newValue);
        assertThat(request.toString()).doesNotContain(oldValue, newValue, oldCipher, requestCipher);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT bank_account_cipher FROM crm_institution WHERE tenant_id=1 AND id=?", String.class, institutionId))
                .isEqualTo(oldCipher);
        JsonNode beforeApproval = data(get("/api/v1/institution-profiles/" + institutionId).cookie(admin.cookies()));
        assertThat(beforeApproval.at("/bankAccountMasked").asText()).isEqualTo("****8866");
        assertThat(beforeApproval.at("/version").asLong()).isEqualTo(1L);
        assertThat(beforeApproval.toString()).doesNotContain(oldValue, newValue, oldCipher, requestCipher);
        String requestAudit = jdbcTemplate.queryForObject(
                "SELECT after_json FROM crm_audit_log WHERE action_code='INSTITUTION_CHANGE_REQUEST' AND target_id=? "
                        + "ORDER BY id DESC LIMIT 1", String.class, Long.toString(institutionId));
        assertThat(requestAudit).contains("****9999")
                .doesNotContain(oldValue, newValue, oldCipher, requestCipher);

        String approvalKey = key("bank-approve");
        JsonNode approved = review(finance, requestId, "finance-review", 0, "APPROVE", approvalKey, 200);
        assertThat(approved.at("/status").asText()).isEqualTo("APPROVED");
        assertThat(approved.toString()).doesNotContain(oldValue, newValue, oldCipher, requestCipher);
        String formalCipher = jdbcTemplate.queryForObject(
                "SELECT bank_account_cipher FROM crm_institution WHERE tenant_id=1 AND id=?", String.class, institutionId);
        assertThat(formalCipher).isNotEqualTo(oldCipher);
        assertThat(cipher.decrypt(formalCipher)).isEqualTo(newValue);
        JsonNode afterApproval = data(get("/api/v1/institution-profiles/" + institutionId).cookie(admin.cookies()));
        assertThat(afterApproval.at("/bankAccountMasked").asText()).isEqualTo("****9999");
        assertThat(afterApproval.at("/version").asLong()).isEqualTo(2L);
        assertThat(afterApproval.toString()).doesNotContain(oldValue, newValue, oldCipher, requestCipher, formalCipher);
        String reviewAudit = jdbcTemplate.queryForObject(
                "SELECT after_json FROM crm_audit_log WHERE action_code='INSTITUTION_CHANGE_REVIEW' AND target_id=? "
                        + "ORDER BY id DESC LIMIT 1", String.class, Long.toString(institutionId));
        assertThat(reviewAudit).contains("****9999")
                .doesNotContain(oldValue, newValue, oldCipher, requestCipher, formalCipher);

        review(finance, requestId, "finance-review", 0, "APPROVE", approvalKey, 200);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_institution WHERE tenant_id=1 AND id=?",
                Long.class, institutionId)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_profile_change_step "
                + "WHERE change_request_id=? AND step_code='FINANCE'", Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void h02CommissionNeedsBothIndependentReviewersAndRejectsSelfReview() throws Exception {
        AuthCookies business = login("business1");
        AuthCookies finance = login("finance1");
        AuthCookies admin = login("admin");
        long institutionId = newActiveProfile(business, admin, finance);
        JsonNode request = data(business.withAuth(post("/api/v1/institution-change-requests"))
                .header("Idempotency-Key", key("commission-change"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", institutionId, "fieldCode", "onlineRate",
                        "newValue", new BigDecimal("0.20"), "baseVersion", 1, "reason", "IT-COMMISSION"))));
        long requestId = request.at("/id").asLong();
        review(finance, requestId, "finance-review", 0, "APPROVE", key("commission-finance-first"), 403);
        assertThat(jdbcTemplate.queryForObject("SELECT online_rate FROM crm_institution WHERE tenant_id=1 AND id=?",
                BigDecimal.class, institutionId)).isEqualByComparingTo("0.18");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_institution WHERE tenant_id=1 AND id=?",
                Long.class, institutionId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_profile_change_request WHERE tenant_id=1 AND id=?",
                String.class, requestId)).isEqualTo("PENDING_REVIEW");
        review(business, requestId, "market-review", 0, "APPROVE", key("commission-self"), 403);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE action_code='INSTITUTION_SELF_REVIEW_DENIED' AND target_id=?",
                Integer.class, Long.toString(institutionId))).isEqualTo(1);
        review(admin, requestId, "market-review", 0, "APPROVE", key("commission-market"), 200);
        review(finance, requestId, "finance-review", 1, "APPROVE", key("commission-finance"), 200);
        mockMvc.perform(get("/api/v1/institution-profiles/" + institutionId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlineRate").value(0.20));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_profile_change_step WHERE change_request_id=? AND status='APPROVED'",
                Integer.class, requestId)).isEqualTo(2);
    }

    @Test
    void h04NewerVersionWinsThenRevalidationCanApprove() throws Exception {
        AuthCookies business = login("business1");
        AuthCookies finance = login("finance1");
        AuthCookies admin = login("admin");
        long institutionId = newActiveProfile(business, admin, finance);
        JsonNode request = data(business.withAuth(post("/api/v1/institution-change-requests"))
                .header("Idempotency-Key", key("conflict-change"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", institutionId, "fieldCode", "onlineRate",
                        "newValue", new BigDecimal("0.22"), "baseVersion", 1, "reason", "IT-CONFLICT"))));
        long requestId = request.at("/id").asLong();
        review(admin, requestId, "market-review", 0, "APPROVE", key("conflict-market"), 200);
        mockMvc.perform(admin.withAuth(patch("/api/v1/institution-profiles/" + institutionId))
                        .header("Idempotency-Key", key("ordinary-patch"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 1, "introduction", "较新的合法介绍"))))
                .andExpect(status().isOk());
        JsonNode conflict = review(finance, requestId, "finance-review", 1, "APPROVE", key("conflict-finance"), 200);
        assertThat(conflict.at("/status").asText()).isEqualTo("CONFLICT_REVALIDATE");
        mockMvc.perform(get("/api/v1/institution-profiles/" + institutionId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlineRate").value(0.18))
                .andExpect(jsonPath("$.data.introduction").value("较新的合法介绍"))
                .andExpect(jsonPath("$.data.version").value(2));
        JsonNode revalidated = data(admin.withAuth(post("/api/v1/institution-change-requests/" + requestId + "/revalidate"))
                .header("Idempotency-Key", key("revalidate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 2, "baseVersion", 2, "reason", "IT-REVALIDATE"))));
        assertThat(revalidated.at("/status").asText()).isEqualTo("PENDING_REVIEW");
        review(admin, requestId, "market-review", 3, "APPROVE", key("revalidate-market"), 200);
        review(finance, requestId, "finance-review", 4, "APPROVE", key("revalidate-finance"), 200);
        mockMvc.perform(get("/api/v1/institution-profiles/" + institutionId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlineRate").value(0.22));
    }

    @Test
    void contractVersionsAndQualificationUseApprovalWorkflows() throws Exception {
        AuthCookies business = login("business1");
        AuthCookies admin = login("admin");
        AuthCookies finance = login("finance1");
        long institutionId = newActiveProfile(business, admin, finance);
        JsonNode first = data(business.withAuth(post("/api/v1/institution-contracts"))
                .header("Idempotency-Key", key("contract-v1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", institutionId, "contractNo", "IT-CONTRACT",
                        "title", "第一版", "contractType", "SERVICE", "startDate", "2026-01-01",
                        "fileId", 9001, "amountFen", 100000))));
        long firstId = first.at("/id").asLong();
        long firstActivation = data(business.withAuth(post("/api/v1/institution-contracts/" + firstId + "/activation-requests"))
                .header("Idempotency-Key", key("contract-v1-activate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseVersion", 0, "reason", "IT-CONTRACT-V1")))).at("/id").asLong();
        review(finance, firstActivation, "finance-review", 0, "APPROVE", key("contract-v1-finance-first"), 403);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_institution_contract WHERE tenant_id=1 AND id=?",
                String.class, firstId)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_institution WHERE tenant_id=1 AND id=?",
                Long.class, institutionId)).isEqualTo(1L);
        review(admin, firstActivation, "market-review", 0, "APPROVE", key("contract-v1-market"), 200);
        review(finance, firstActivation, "finance-review", 1, "APPROVE", key("contract-v1-finance"), 200);

        JsonNode second = data(business.withAuth(post("/api/v1/institution-contracts"))
                .header("Idempotency-Key", key("contract-v2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", institutionId, "previousContractId", firstId,
                        "contractNo", "IGNORED-BY-SNAPSHOT", "title", "第二版", "contractType", "SERVICE",
                        "startDate", "2027-01-01", "fileId", 9002, "amountFen", 120000))));
        long secondId = second.at("/id").asLong();
        long secondActivation = data(business.withAuth(post("/api/v1/institution-contracts/" + secondId + "/activation-requests"))
                .header("Idempotency-Key", key("contract-v2-activate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseVersion", 0, "reason", "IT-CONTRACT-V2")))).at("/id").asLong();
        review(admin, secondActivation, "market-review", 0, "APPROVE", key("contract-v2-market"), 200);
        review(finance, secondActivation, "finance-review", 1, "APPROVE", key("contract-v2-finance"), 200);
        mockMvc.perform(get("/api/v1/institution-contracts/" + firstId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.data.contractNo").value("IT-CONTRACT"))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.amountFen").value(100000))
                .andExpect(jsonPath("$.data.fileId").value(9001));
        mockMvc.perform(get("/api/v1/institution-contracts/" + secondId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.contractNo").value("IT-CONTRACT"))
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.previousContractId").value(firstId))
                .andExpect(jsonPath("$.data.amountFen").value(120000))
                .andExpect(jsonPath("$.data.fileId").value(9002));

        JsonNode qualification = data(business.withAuth(post("/api/v1/institution-qualifications"))
                .header("Idempotency-Key", key("qualification"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", institutionId, "qualificationType", "BUSINESS_LICENSE",
                        "title", "虚构营业执照", "licenseNo", "TEST-8866", "fileId", 9003,
                        "validFrom", "2026-01-01", "validTo", "2027-12-31"))));
        long qualificationId = qualification.at("/id").asLong();
        String qualificationCipher = jdbcTemplate.queryForObject(
                "SELECT license_no_cipher FROM crm_institution_qualification WHERE tenant_id=1 AND id=?",
                String.class, qualificationId);
        assertThat(cipher.decrypt(qualificationCipher)).isEqualTo("TEST-8866");
        assertThat(qualification.toString()).doesNotContain("TEST-8866", qualificationCipher);
        assertThat(qualification.at("/validFrom").asText()).isEqualTo("2026-01-01");
        assertThat(qualification.at("/validTo").asText()).isEqualTo("2027-12-31");
        long reviewId = data(business.withAuth(post("/api/v1/institution-qualifications/" + qualificationId + "/review-requests"))
                .header("Idempotency-Key", key("qualification-review"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseVersion", 0, "reason", "IT-QUALIFICATION")))).at("/id").asLong();
        review(admin, reviewId, "market-review", 0, "APPROVE", key("qualification-approve"), 200);
        mockMvc.perform(get("/api/v1/institution-qualifications/" + qualificationId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.licenseNoMasked").value("****8866"));
        String qualificationAudit = jdbcTemplate.queryForObject(
                "SELECT after_json FROM crm_audit_log WHERE action_code='INSTITUTION_QUALIFICATION_CREATE' AND target_id=? "
                        + "ORDER BY id DESC LIMIT 1", String.class, Long.toString(institutionId));
        assertThat(qualificationAudit).contains("****8866").doesNotContain("TEST-8866", qualificationCipher);
    }

    private long newProfile(AuthCookies business) throws Exception {
        String code = "IT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> body = Map.ofEntries(
                Map.entry("institutionNo", code), Map.entry("fullName", "集成测试机构"),
                Map.entry("shortName", "测试院"), Map.entry("cityCode", "BJ"),
                Map.entry("businessTypeCode", "MEDICAL_BEAUTY"), Map.entry("ownershipCode", "PRIVATE"),
                Map.entry("gradeCode", "A"), Map.entry("settlementType", "MONTHLY"),
                Map.entry("onlineRate", new BigDecimal("0.18")), Map.entry("offlineRate", new BigDecimal("0.12")),
                Map.entry("platformRate", new BigDecimal("0.15")), Map.entry("chargesFee", false),
                Map.entry("depositFen", 0), Map.entry("guaranteeRoi", false),
                Map.entry("bankAccount", "6222020200008866"), Map.entry("introduction", "初始介绍"));
        return data(business.withAuth(post("/api/v1/institution-profiles"))
                .header("Idempotency-Key", key("create-profile"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).at("/id").asLong();
    }

    private long newActiveProfile(AuthCookies business, AuthCookies admin, AuthCookies finance) throws Exception {
        long id = newProfile(business);
        long requestId = data(business.withAuth(post("/api/v1/institution-profiles/" + id + "/activation-requests"))
                .header("Idempotency-Key", key("activate-before-test"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseVersion", 0, "reason", "IT-ACTIVATE")))).at("/id").asLong();
        review(admin, requestId, "market-review", 0, "APPROVE", key("activate-before-market"), 200);
        review(finance, requestId, "finance-review", 1, "APPROVE", key("activate-before-finance"), 200);
        return id;
    }

    private JsonNode review(AuthCookies actor, long requestId, String action, long version,
                            String decision, String key, int expectedStatus) throws Exception {
        String response = mockMvc.perform(actor.withAuth(post("/api/v1/institution-change-requests/" + requestId + "/" + action))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", version, "decision", decision, "comment", "IT-REVIEW"))))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return response.isBlank() ? null : objectMapper.readTree(response).at("/data");
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    @TestConfiguration
    static class FileConfig {
        @Bean
        @Primary
        InstitutionFileOperations institutionFileOperations() {
            return (tenantId, fileId) -> {
                if (!Set.of(9001L, 9002L, 9003L).contains(fileId)) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "测试文件未扫描通过");
                }
            };
        }
    }
}
