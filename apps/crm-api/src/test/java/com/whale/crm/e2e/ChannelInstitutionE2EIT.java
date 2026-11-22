package com.whale.crm.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F3, F4 & F2 端到端闭环：
 * - H-SEC-03 (V54)：渠道初始化、status 切换、PATCH 财务锁、* 脱敏输入拒绝、财务变更审批、SoD 自审拒绝、跨租户 404、驳回审计与重提
 * - M-BE-01：真实 HTTP /files/upload-init -> /files/upload-complete -> /institution-contracts 归属与分类与扫描状态校验
 * - H-SEC-09 #3 & H-BUG-06：settlementName、chargesFee=false、guaranteeRoi=false、bankAccount 按角色严格脱敏与明文/TINYINT落库
 */
@SpringBootTest(properties = {
        "app.files.enabled=true",
        "app.files.endpoint=http://127.0.0.1:9",
        "app.files.access-key=synthetic-test-only",
        "app.files.secret-key=synthetic-test-only",
        "app.files.max-file-size-bytes=65536",
        "app.exports.worker.enabled=false",
        "app.outbox.relay.enabled=false"
})
class ChannelInstitutionE2EIT extends AbstractE2EIT {

    @MockitoBean
    private PrivateObjectStorage storage;

    @MockitoBean
    private ClamAvScanner scanner;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

    @BeforeEach
    void initStorageMocks() throws Exception {
        configureMockStorage(storage, scanner, objectStore);
    }

    @Test
    void partnerChannelLifecycleMaskingStatusToggleFinancialChangeApprovalAndCrossTenantIsolation() throws Exception {
        AuthCookies business = login("business1");
        AuthCookies finance = login("finance1");
        AuthCookies admin = login("admin");

        String channelNo = shortUuid("E2E-CH").toUpperCase();
        Map<String, Object> createDraft = new HashMap<>();
        createDraft.put("channelNo", channelNo);
        createDraft.put("name", "E2E渠道-" + channelNo);
        createDraft.put("channelType", "BLOGGER");
        createDraft.put("status", "INACTIVE");
        createDraft.put("businessOwnerUserId", 6);

        JsonNode created = data(business.withAuth(post("/api/v1/channels"))
                .contentType(MediaType.APPLICATION_JSON).content(json(createDraft)));
        long channelId = created.at("/id").asLong();
        assertThat(created.at("/status").asText()).isEqualTo("INACTIVE");
        assertThat(created.at("/paymentAccountConfigured").asBoolean()).isFalse();

        // Wildcard '*' masked input on INACTIVE unconfigured channel -> 400
        Map<String, Object> wildcardPatch = new HashMap<>(createDraft);
        wildcardPatch.put("version", 0);
        wildcardPatch.put("paymentAccountNo", "****1111");
        mockMvc.perform(business.withAuth(patch("/api/v1/channels/" + channelId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(wildcardPatch)))
                .andExpect(status().isBadRequest());

        // Initial financial configuration while INACTIVE & unconfigured -> 200
        Map<String, Object> initFin = new HashMap<>(createDraft);
        initFin.put("version", 0);
        initFin.put("commissionPercent", "10.00");
        initFin.put("settlementMethod", "MONTHLY");
        initFin.put("paymentAccountName", "初始收款户");
        initFin.put("paymentAccountNo", "6222000000001111");
        initFin.put("paymentBankName", "初始开户行");
        JsonNode configured = data(business.withAuth(patch("/api/v1/channels/" + channelId))
                .contentType(MediaType.APPLICATION_JSON).content(json(initFin)));
        assertThat(configured.at("/paymentAccountNo").asText()).isEqualTo("****1111");
        assertThat(configured.at("/paymentAccountConfigured").asBoolean()).isTrue();
        assertThat(configured.at("/version").asLong()).isEqualTo(1L);

        // Status toggle -> ACTIVE (version 1 -> 2)
        JsonNode activated = data(business.withAuth(post("/api/v1/channels/" + channelId + "/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("status", "ACTIVE", "version", 1, "reason", "E2E上线"))));
        assertThat(activated.at("/status").asText()).isEqualTo("ACTIVE");
        assertThat(activated.at("/version").asLong()).isEqualTo(2L);

        // Non-financial remarks PATCH -> 200 (version 2 -> 3)
        Map<String, Object> remarksPatch = new HashMap<>();
        remarksPatch.put("name", "E2E渠道-" + channelNo);
        remarksPatch.put("channelType", "BLOGGER");
        remarksPatch.put("businessOwnerUserId", 6);
        remarksPatch.put("status", "ACTIVE");
        remarksPatch.put("remarks", "非财务备注更新");
        remarksPatch.put("version", 2);
        JsonNode remarksUpdated = data(business.withAuth(patch("/api/v1/channels/" + channelId))
                .contentType(MediaType.APPLICATION_JSON).content(json(remarksPatch)));
        assertThat(remarksUpdated.at("/version").asLong()).isEqualTo(3L);

        // Bank/commission PATCH on configured ACTIVE channel -> 403
        Map<String, Object> forbiddenBankPatch = new HashMap<>(remarksPatch);
        forbiddenBankPatch.put("version", 3);
        forbiddenBankPatch.put("commissionPercent", "22.00");
        mockMvc.perform(business.withAuth(patch("/api/v1/channels/" + channelId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(forbiddenBankPatch)))
                .andExpect(status().isForbidden());

        // Submit financial change request (submitted by admin so we can also test self-review -> 403)
        Map<String, Object> firstChange = Map.of(
                "commissionPercent", "15.00",
                "paymentAccountName", "待驳回账户",
                "paymentAccountNo", "6222020200008888",
                "paymentBankName", "待驳回开户行",
                "baseVersion", 3,
                "reason", "首次调佣");
        long reqId1 = data(admin.withAuth(post("/api/v1/channels/" + channelId + "/financial-change-requests"))
                .header("Idempotency-Key", uniqueKey("e2e-ch-req1"))
                .contentType(MediaType.APPLICATION_JSON).content(json(firstChange)))
                .at("/id").asLong();

        // Duplicate active request -> 409
        mockMvc.perform(business.withAuth(post("/api/v1/channels/" + channelId + "/financial-change-requests"))
                        .header("Idempotency-Key", uniqueKey("e2e-ch-dup"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(firstChange)))
                .andExpect(status().isConflict());

        // Self-review -> 403 + CHANNEL_SELF_REVIEW_DENIED audit
        mockMvc.perform(admin.withAuth(post("/api/v1/channel-change-requests/" + reqId1 + "/review"))
                        .header("Idempotency-Key", uniqueKey("e2e-ch-self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "APPROVE", "version", 0))))
                .andExpect(status().isForbidden());
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND action_code='CHANNEL_SELF_REVIEW_DENIED' AND target_id=?", Long.toString(channelId)))
                .isEqualTo(1);

        // Finance REJECT -> audit + allows resubmit
        JsonNode rejected = data(finance.withAuth(post("/api/v1/channel-change-requests/" + reqId1 + "/review"))
                .header("Idempotency-Key", uniqueKey("e2e-ch-reject"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("decision", "REJECT", "version", 0, "comment", "需补充凭证"))));
        assertThat(rejected.at("/status").asText()).isEqualTo("REJECTED");
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND action_code='CHANNEL_FINANCE_CHANGE_REJECT' AND target_id=?", Long.toString(channelId)))
                .isEqualTo(1);

        // Cross-tenant review -> 404
        String t2ChannelNo = shortUuid("E2E-T2CH").toUpperCase();
        jdbcTemplate.update("INSERT INTO crm_partner_channel (tenant_id,channel_no,name,channel_type,status) "
                + "VALUES (2,?,'二租户渠道','BLOGGER','ACTIVE')", t2ChannelNo);
        long t2ChannelId = queryLong("SELECT id FROM crm_partner_channel WHERE tenant_id=2 AND channel_no=?",
                t2ChannelNo);
        jdbcTemplate.update("INSERT INTO crm_channel_change_request (tenant_id,channel_id,base_version,before_cipher,"
                        + "after_cipher,before_masked,after_masked,reason,status,requested_by) "
                        + "VALUES (2,?,0,'{}','{}','{}','{}','cross-tenant-test','PENDING_REVIEW',1)",
                t2ChannelId);
        long t2ReqId = queryLong("SELECT id FROM crm_channel_change_request WHERE tenant_id=2 AND channel_id=?",
                t2ChannelId);
        mockMvc.perform(finance.withAuth(post("/api/v1/channel-change-requests/" + t2ReqId + "/review"))
                        .header("Idempotency-Key", uniqueKey("e2e-ch-t2rev"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "APPROVE", "version", 0))))
                .andExpect(status().isNotFound());

        // Resubmit & Finance APPROVE -> GET /channels verifies updated fields + masking
        Map<String, Object> secondChange = Map.of(
                "commissionPercent", "18.75",
                "paymentAccountName", "E2E终审户",
                "paymentAccountNo", "6222020200009999",
                "paymentBankName", "E2E终审开户行",
                "baseVersion", 3,
                "reason", "重提并批准");
        long reqId2 = data(business.withAuth(post("/api/v1/channels/" + channelId + "/financial-change-requests"))
                .header("Idempotency-Key", uniqueKey("e2e-ch-req2"))
                .contentType(MediaType.APPLICATION_JSON).content(json(secondChange)))
                .at("/id").asLong();

        JsonNode approved = data(finance.withAuth(post("/api/v1/channel-change-requests/" + reqId2 + "/review"))
                .header("Idempotency-Key", uniqueKey("e2e-ch-app"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("decision", "APPROVE", "version", 0, "comment", "核准"))));
        assertThat(approved.at("/status").asText()).isEqualTo("APPROVED");

        JsonNode channelList = data(get("/api/v1/channels?keyword=" + channelNo).cookie(business.cookies()));
        assertThat(channelList.at("/items/0/id").asLong()).isEqualTo(channelId);
        assertThat(channelList.at("/items/0/commissionPercent").decimalValue()).isEqualByComparingTo("18.75");
        assertThat(channelList.at("/items/0/paymentBankName").asText()).isEqualTo("E2E终审开户行");
        assertThat(channelList.at("/items/0/paymentAccountNo").asText()).isEqualTo("****9999");
        assertThat(channelList.at("/items/0/paymentAccountConfigured").asBoolean()).isTrue();
        assertThat(channelList.toString()).doesNotContain("6222020200009999", "E2E终审户");
    }

    @Test
    void httpFileUploadToInstitutionContractAndRoleMaskedChangeRequestsEndToEnd() throws Exception {
        AuthCookies business = login("business1");
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        AuthCookies operation = login("operation1");
        AuthCookies finance = login("finance1");

        long inst1 = createActiveInstitution(business, admin, finance);
        long inst2 = createActiveInstitution(business, admin, finance);

        // M-BE-01: Full HTTP upload-init + upload-complete
        byte[] pdf = cleanPdfBytes();
        long validContractFile = uploadCleanFileViaHttp(business, objectStore, shortUuid("contract-ok") + ".pdf",
                "application/pdf", pdf, "CONTRACT", inst1);
        long wrongInstContractFile = uploadCleanFileViaHttp(business, objectStore, shortUuid("contract-i2") + ".pdf",
                "application/pdf", pdf, "CONTRACT", inst2);
        long wrongClassQualFile = uploadCleanFileViaHttp(business, objectStore, shortUuid("qual-i1") + ".pdf",
                "application/pdf", pdf, "QUALIFICATION", inst1);

        // Init-only PENDING contract file (upload-complete not called)
        JsonNode pendingInit = data(business.withAuth(post("/api/v1/files/upload-init"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "originalName", shortUuid("pending") + ".pdf",
                        "mimeType", "application/pdf",
                        "sizeBytes", (long) pdf.length,
                        "sha256", sha256Hex(pdf),
                        "classification", "CONTRACT",
                        "institutionId", inst1))));
        long pendingContractFile = pendingInit.at("/fileId").asLong();

        // 403 wrong institution
        mockMvc.perform(business.withAuth(post("/api/v1/institution-contracts"))
                        .header("Idempotency-Key", uniqueKey("e2e-mbe01-403"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "contractNo", shortUuid("C403"),
                                "title", "跨机构合同", "contractType", "SERVICE", "startDate", "2026-01-01",
                                "fileId", wrongInstContractFile, "amountFen", 1000))))
                .andExpect(status().isForbidden());

        // 409 wrong classification
        mockMvc.perform(business.withAuth(post("/api/v1/institution-contracts"))
                        .header("Idempotency-Key", uniqueKey("e2e-mbe01-class"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "contractNo", shortUuid("C409C"),
                                "title", "错分类合同", "contractType", "SERVICE", "startDate", "2026-01-01",
                                "fileId", wrongClassQualFile, "amountFen", 1000))))
                .andExpect(status().isConflict());

        // 409 PENDING
        mockMvc.perform(business.withAuth(post("/api/v1/institution-contracts"))
                        .header("Idempotency-Key", uniqueKey("e2e-mbe01-pend"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "contractNo", shortUuid("C409P"),
                                "title", "未扫描合同", "contractType", "SERVICE", "startDate", "2026-01-01",
                                "fileId", pendingContractFile, "amountFen", 1000))))
                .andExpect(status().isConflict());

        // 201 valid contract
        mockMvc.perform(business.withAuth(post("/api/v1/institution-contracts"))
                        .header("Idempotency-Key", uniqueKey("e2e-mbe01-ok"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("institutionId", inst1, "contractNo", shortUuid("COK"),
                                "title", "E2E合法合同", "contractType", "SERVICE", "startDate", "2026-01-01",
                                "fileId", validContractFile, "amountFen", 1000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fileId").value(validContractFile));

        // H-SEC-09 #3 + H-BUG-06: Upload clean CHANGE_EVIDENCE PDF via HTTP, create change requests
        long evidenceFileId = uploadCleanFileViaHttp(business, objectStore, shortUuid("evidence") + ".pdf",
                "application/pdf", pdf, "CHANGE_EVIDENCE", inst1);

        long settlementNameReqId = data(business.withAuth(post("/api/v1/institution-change-requests"))
                .header("Idempotency-Key", uniqueKey("e2e-inst-sname"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", inst1, "fieldCode", "settlementName",
                        "newValue", "E2E明文结款主体", "baseVersion", 1, "reason", "修改结款主体"))))
                .at("/id").asLong();

        long bankReqId = data(business.withAuth(post("/api/v1/institution-change-requests"))
                .header("Idempotency-Key", uniqueKey("e2e-inst-bank"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", inst1, "fieldCode", "bankAccount",
                        "newValue", "6222000000007777", "baseVersion", 1, "reason", "变更银行卡",
                        "evidenceFileId", evidenceFileId))))
                .at("/id").asLong();

        // Role-aware masking across MANAGER vs OPERATION vs FINANCE
        JsonNode mgrBank = data(get("/api/v1/institution-change-requests/" + bankReqId).cookie(manager.cookies()));
        assertThat(mgrBank.at("/beforeMasked").isNull()).isTrue();
        assertThat(mgrBank.at("/afterMasked").isNull()).isTrue();
        assertThat(mgrBank.at("/evidenceFileId").isNull()).isTrue();

        JsonNode mgrName = data(get("/api/v1/institution-change-requests/" + settlementNameReqId)
                .cookie(manager.cookies()));
        assertThat(mgrName.at("/beforeMasked").isNull()).isTrue();
        assertThat(mgrName.at("/afterMasked").isNull()).isTrue();

        JsonNode opBank = data(get("/api/v1/institution-change-requests/" + bankReqId).cookie(operation.cookies()));
        assertThat(opBank.at("/beforeMasked").isNull()).isTrue();
        assertThat(opBank.at("/afterMasked").isNull()).isTrue();
        assertThat(opBank.at("/evidenceFileId").isNull()).isTrue();

        JsonNode finBank = data(get("/api/v1/institution-change-requests/" + bankReqId).cookie(finance.cookies()));
        assertThat(finBank.at("/beforeMasked/value").asText()).isEqualTo("****8866");
        assertThat(finBank.at("/afterMasked/value").asText()).isEqualTo("****7777");
        assertThat(finBank.at("/evidenceFileId").asLong()).isEqualTo(evidenceFileId);
        assertThat(finBank.toString()).doesNotContain("6222000000007777", "6222000000008866");

        // Approve settlementName (version 1 -> 2), chargesFee=false (2 -> 3), guaranteeRoi=false (3 -> 4)
        mockMvc.perform(finance.withAuth(post("/api/v1/institution-change-requests/" + settlementNameReqId
                        + "/finance-review"))
                        .header("Idempotency-Key", uniqueKey("e2e-sname-fin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "decision", "APPROVE", "comment", "同意"))))
                .andExpect(status().isOk());

        long feeReqId = data(business.withAuth(post("/api/v1/institution-change-requests"))
                .header("Idempotency-Key", uniqueKey("e2e-inst-fee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", inst1, "fieldCode", "chargesFee",
                        "newValue", false, "baseVersion", 2, "reason", "取消收费"))))
                .at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/institution-change-requests/" + feeReqId + "/market-review"))
                        .header("Idempotency-Key", uniqueKey("e2e-fee-mkt"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "decision", "APPROVE", "comment", "同意"))))
                .andExpect(status().isOk());
        mockMvc.perform(finance.withAuth(post("/api/v1/institution-change-requests/" + feeReqId + "/finance-review"))
                        .header("Idempotency-Key", uniqueKey("e2e-fee-fin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 1, "decision", "APPROVE", "comment", "同意"))))
                .andExpect(status().isOk());

        long roiReqId = data(business.withAuth(post("/api/v1/institution-change-requests"))
                .header("Idempotency-Key", uniqueKey("e2e-inst-roi"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("institutionId", inst1, "fieldCode", "guaranteeRoi",
                        "newValue", false, "baseVersion", 3, "reason", "取消保投产"))))
                .at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/institution-change-requests/" + roiReqId + "/market-review"))
                        .header("Idempotency-Key", uniqueKey("e2e-roi-mkt"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "decision", "APPROVE", "comment", "同意"))))
                .andExpect(status().isOk());
        mockMvc.perform(finance.withAuth(post("/api/v1/institution-change-requests/" + roiReqId + "/finance-review"))
                        .header("Idempotency-Key", uniqueKey("e2e-roi-fin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 1, "decision", "APPROVE", "comment", "同意"))))
                .andExpect(status().isOk());

        // Verify H-BUG-06 plaintext settlement_name + TINYINT 0
        assertThat(queryString("SELECT settlement_name FROM crm_institution WHERE tenant_id=1 AND id=?", inst1))
                .isEqualTo("E2E明文结款主体");
        assertThat(queryInt("SELECT charges_fee FROM crm_institution WHERE tenant_id=1 AND id=?", inst1)).isZero();
        assertThat(queryInt("SELECT guarantee_roi FROM crm_institution WHERE tenant_id=1 AND id=?", inst1)).isZero();
    }

    private long createActiveInstitution(AuthCookies business, AuthCookies admin, AuthCookies finance)
            throws Exception {
        String no = shortUuid("E2E-INST").toUpperCase();
        Map<String, Object> body = Map.ofEntries(
                Map.entry("institutionNo", no),
                Map.entry("fullName", "E2E机构-" + no),
                Map.entry("shortName", "E2E院"),
                Map.entry("cityCode", "BJ"),
                Map.entry("businessTypeCode", "MEDICAL_BEAUTY"),
                Map.entry("ownershipCode", "PRIVATE"),
                Map.entry("gradeCode", "A"),
                Map.entry("address", "E2E地址"),
                Map.entry("contactName", "E2E联系人"),
                Map.entry("contactPhone", "13800001234"),
                Map.entry("settlementType", "MONTHLY"),
                Map.entry("onlineRate", new BigDecimal("0.18")),
                Map.entry("offlineRate", new BigDecimal("0.12")),
                Map.entry("platformRate", new BigDecimal("0.15")),
                Map.entry("otherRateRule", "无"),
                Map.entry("chargesFee", true),
                Map.entry("depositFen", 10000),
                Map.entry("guaranteeRoi", true),
                Map.entry("roiTerms", "初始保投产"),
                Map.entry("settlementName", "初始结款主体"),
                Map.entry("bankName", "初始银行"),
                Map.entry("bankAccount", "6222000000008866"),
                Map.entry("taxId", "91110000000000000X"),
                Map.entry("introduction", "初始介绍"));
        long instId = data(business.withAuth(post("/api/v1/institution-profiles"))
                .header("Idempotency-Key", uniqueKey("e2e-inst-create"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .at("/id").asLong();

        long actReqId = data(business.withAuth(post("/api/v1/institution-profiles/" + instId + "/activation-requests"))
                .header("Idempotency-Key", uniqueKey("e2e-inst-act"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseVersion", 0, "reason", "E2E激活"))))
                .at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/institution-change-requests/" + actReqId + "/market-review"))
                        .header("Idempotency-Key", uniqueKey("e2e-inst-act-mkt"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "decision", "APPROVE", "comment", "同意"))))
                .andExpect(status().isOk());
        mockMvc.perform(finance.withAuth(post("/api/v1/institution-change-requests/" + actReqId + "/finance-review"))
                        .header("Idempotency-Key", uniqueKey("e2e-inst-act-fin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 1, "decision", "APPROVE", "comment", "同意"))))
                .andExpect(status().isOk());
        return instId;
    }
}
