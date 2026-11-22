package com.whale.crm.institution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.institution.api.dto.ChangeRequestResponse;
import com.whale.crm.institution.api.dto.CreateContractRequest;
import com.whale.crm.institution.api.dto.CreateInstitutionMerchantResourceRequest;
import com.whale.crm.institution.api.dto.CreateInstitutionProfileRequest;
import com.whale.crm.institution.api.dto.CreateQualificationRequest;
import com.whale.crm.institution.application.InstitutionChangeService;
import com.whale.crm.institution.application.InstitutionDocumentService;
import com.whale.crm.institution.application.InstitutionFieldPolicy;
import com.whale.crm.institution.application.InstitutionFileOperations;
import com.whale.crm.institution.application.InstitutionMerchantResourceService;
import com.whale.crm.institution.application.InstitutionProfileService;
import com.whale.crm.institution.application.InstitutionSupport;
import com.whale.crm.institution.domain.InstitutionContract;
import com.whale.crm.institution.domain.InstitutionMerchantResource;
import com.whale.crm.institution.domain.InstitutionMerchantResourceRepository;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionQualification;
import com.whale.crm.institution.domain.InstitutionRepository;
import com.whale.crm.institution.domain.ProfileChangeRequest;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.trading.application.OutboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstitutionM4aRegressionTest {
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");

    private final PlatformTransactionManager txManager = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void c02ServicesPassExplicitTenantIdWithoutTenantContextBound() {
        SecurityContextHolder.clearContext();
        InstitutionRepository repository = mock(InstitutionRepository.class);
        InstitutionMerchantResourceRepository merchantRepo = mock(InstitutionMerchantResourceRepository.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        InstitutionAccessSupport accessSupport = mock(InstitutionAccessSupport.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        when(cipher.encrypt(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0, String.class) + ")");
        when(cipher.decrypt(anyString())).thenAnswer(inv -> {
            String value = inv.getArgument(0, String.class);
            return value.startsWith("ENC(") && value.endsWith(")")
                    ? value.substring(4, value.length() - 1) : value;
        });
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<InstitutionFileOperations> fileOpsProvider = mock(ObjectProvider.class);
        InstitutionFileOperations fileOps = mock(InstitutionFileOperations.class);
        when(fileOpsProvider.getIfAvailable()).thenReturn(fileOps);

        InstitutionSupport support = new InstitutionSupport(dataScopeService, accessSupport, cipher,
                new ObjectMapper().findAndRegisterModules(), idempotencyService, fileOpsProvider);
        AuditService auditService = mock(AuditService.class);
        OutboxService outboxService = mock(OutboxService.class);
        InstitutionChangeService changeService = new InstitutionChangeService(
                repository, support, auditService, outboxService, txManager);
        InstitutionProfileService profileService = new InstitutionProfileService(
                repository, support, changeService, auditService, outboxService, txManager);
        InstitutionDocumentService documentService = new InstitutionDocumentService(
                repository, support, changeService, auditService, outboxService, txManager);
        InstitutionMerchantResourceService resourceService = new InstitutionMerchantResourceService(
                merchantRepo, repository, support, auditService, txManager);

        CurrentUser writer = new CurrentUser(1L, 2L, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("institution:read", "institution:write", "institution:change_request"),
                Set.of());
        when(dataScopeService.scopeFor(writer, DataScopeService.RESOURCE_INSTITUTION)).thenReturn(DataScope.all());
        when(idempotencyService.lookup(eq(2L), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        InstitutionProfile profile = new InstitutionProfile(10L, "INST-T2", "二租户机构", "二租户", "BJ",
                "MED_AESTHETIC", "PRIVATE", "A", null, null, null, "ACTIVE", "MONTHLY", null,
                new BigDecimal("0.18"), new BigDecimal("0.12"), new BigDecimal("0.15"), null,
                false, 0L, false, null, "结款主体", null, null, null, null, NOW, 1L);
        when(repository.findProfileByNo(2L, "INST-T2")).thenReturn(Optional.of(profile));
        when(repository.findProfile(2L, 10L)).thenReturn(Optional.of(profile));

        profileService.create(2L, writer, new CreateInstitutionProfileRequest(
                "INST-T2", "二租户机构", "二租户", "BJ", "MED_AESTHETIC", "PRIVATE", "A", null, null, null,
                "MONTHLY", null, new BigDecimal("0.18"), new BigDecimal("0.12"), new BigDecimal("0.15"),
                null, false, 0L, false, null, "结款主体", null, null, null, null), "k-prof");
        verify(repository).insertProfile(eq(2L), any());

        InstitutionContract contract = new InstitutionContract(20L, 10L, "C-T2", 1, "合同", "SERVICE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 100L, 1000L, null, "PENDING_REVIEW",
                null, 1L, NOW, 0L);
        when(repository.findContractByKey(2L, "C-T2", 1)).thenReturn(Optional.of(contract));
        documentService.createContract(2L, writer, new CreateContractRequest(
                10L, null, "C-T2", "合同", "SERVICE", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                100L, 1000L, null), "k-cont");
        verify(repository).insertContract(eq(2L), any());
        verify(fileOps).requireClean(2L, 10L, 100L, FileClassification.CONTRACT);

        InstitutionQualification qualification = new InstitutionQualification(30L, 10L, "BUSINESS_LICENSE",
                "资质", "ENC(123)", 101L, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                "PENDING_REVIEW", 1L, null, null, NOW, 0L);
        when(repository.findLatestQualification(2L, 10L, 101L, "资质")).thenReturn(Optional.of(qualification));
        documentService.createQualification(2L, writer, new CreateQualificationRequest(
                10L, "BUSINESS_LICENSE", "资质", "123", 101L,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)), "k-qual");
        verify(repository).insertQualification(eq(2L), any());
        verify(fileOps).requireClean(2L, 10L, 101L, FileClassification.QUALIFICATION);

        ProfileChangeRequest change = new ProfileChangeRequest(40L, 10L, "PROFILE_FIELD", "fullName", 10L,
                1L, 1L, "c1", "c2", "{\"value\":\"a\"}", "{\"value\":\"b\"}", "原因", null,
                "PENDING_REVIEW", "MARKET", 1L, NOW, null, "PROFILE_FIELD:10:fullName", NOW, 0L);
        when(repository.findActiveChange(2L, "PROFILE_FIELD:10:fullName"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(change));
        when(repository.findSteps(2L, 40L)).thenReturn(List.of());
        when(repository.findChange(2L, 40L)).thenReturn(Optional.of(change));
        changeService.create(2L, writer, change);
        verify(repository).insertChange(eq(2L), any());
        verify(repository).insertStep(eq(2L), any());
        changeService.detail(2L, writer, 40L);
        verify(repository, org.mockito.Mockito.atLeast(2)).findSteps(eq(2L), eq(40L));

        InstitutionMerchantResource mr = new InstitutionMerchantResource(50L, "MR-1", 10L, "DOCTOR",
                "医生", null, null, 102L, "PUBLISHED", 1L, null, NOW, NOW, 0L);
        when(merchantRepo.findByResourceNo(eq(2L), anyString())).thenReturn(Optional.of(mr));
        resourceService.create(2L, writer, 10L, new CreateInstitutionMerchantResourceRequest(
                "DOCTOR", "医生", null, null, 102L), "k-mr");
        verify(merchantRepo).insert(eq(2L), any());
        verify(fileOps).requireClean(2L, 10L, 102L, FileClassification.MERCHANT_RESOURCE);
    }

    @Test
    void hBug06KeepsSettlementNamePlaintextAndConvertsBooleansForStorageOnly() {
        InstitutionSupport support = mock(InstitutionSupport.class);
        when(support.encrypt(anyString())).thenReturn("SHOULD_NOT_BE_CALLED");

        assertThat(InstitutionFieldPolicy.cipherField("settlementName")).isFalse();
        assertThat(InstitutionFieldPolicy.normalize("settlementName", TextNode.valueOf("明文结款主体")))
                .isEqualTo("明文结款主体");
        assertThat(InstitutionFieldPolicy.storageValue("settlementName", "明文结款主体", support))
                .isEqualTo("明文结款主体");
        verify(support, never()).encrypt("明文结款主体");

        assertThat(InstitutionFieldPolicy.normalize("chargesFee", BooleanNode.TRUE)).isEqualTo("true");
        assertThat(InstitutionFieldPolicy.normalize("guaranteeRoi", BooleanNode.FALSE)).isEqualTo("false");
        assertThat(InstitutionFieldPolicy.jsonValue("chargesFee", "true")).isEqualTo(Boolean.TRUE);
        assertThat(InstitutionFieldPolicy.jsonValue("guaranteeRoi", "false")).isEqualTo(Boolean.FALSE);
        assertThat(InstitutionFieldPolicy.storageValue("chargesFee", "true", support)).isEqualTo("1");
        assertThat(InstitutionFieldPolicy.storageValue("chargesFee", "false", support)).isEqualTo("0");
        assertThat(InstitutionFieldPolicy.storageValue("guaranteeRoi", "true", support)).isEqualTo("1");
        assertThat(InstitutionFieldPolicy.storageValue("guaranteeRoi", "false", support)).isEqualTo("0");
        verify(support, never()).encrypt(anyString());
    }

    @Test
    void hSec09Part3RedactsChangeRequestsConsistentlyAcrossRoles() {
        InstitutionSupport support = new InstitutionSupport(mock(DataScopeService.class),
                mock(InstitutionAccessSupport.class), mock(AesGcmCipher.class), new ObjectMapper(),
                mock(IdempotencyService.class), null);

        ProfileChangeRequest commercialReq = new ProfileChangeRequest(1L, 10L, "PROFILE_FIELD", "onlineRate", 10L,
                1L, 1L, "c1", "c2", "{\"value\":0.18}", "{\"value\":0.25}", "调佣", 9001L,
                "PENDING_REVIEW", "MARKET", 6L, NOW, null, "t1", NOW, 0L);
        ProfileChangeRequest financeReq = new ProfileChangeRequest(2L, 10L, "PROFILE_FIELD", "bankAccount", 10L,
                1L, 1L, "c1", "c2", "{\"value\":\"****8866\"}", "{\"value\":\"****9999\"}", "换卡", 9002L,
                "PENDING_REVIEW", "FINANCE", 6L, NOW, null, "t2", NOW, 0L);
        ProfileChangeRequest settlementDayReq = new ProfileChangeRequest(3L, 10L, "PROFILE_FIELD", "settlementDay", 10L,
                1L, 1L, "c1", "c2", "{\"value\":\"8\"}", "{\"value\":\"15\"}", "结算日", 9003L,
                "PENDING_REVIEW", "MARKET", 6L, NOW, null, "t3", NOW, 0L);
        ProfileChangeRequest contractReq = new ProfileChangeRequest(4L, 10L, "CONTRACT_ACTIVATION", null, 20L,
                1L, 0L, "c1", "c2", "{\"status\":\"PENDING_REVIEW\"}", "{\"status\":\"ACTIVE\"}", "激活合同", 9004L,
                "PENDING_REVIEW", "MARKET", 6L, NOW, null, "t4", NOW, 0L);

        CurrentUser manager = new CurrentUser(3L, 1L, "manager1", "管家", "EMPLOYEE", null,
                Set.of("MANAGER"), Set.of("institution:read"), Set.of());
        CurrentUser managerLead = new CurrentUser(4L, 1L, "lead1", "管家主管", "EMPLOYEE", null,
                Set.of("MANAGER_LEAD"), Set.of("institution:read"), Set.of());
        CurrentUser operation = new CurrentUser(7L, 1L, "operation1", "运营", "EMPLOYEE", null,
                Set.of("OPERATION"), Set.of("institution:read"), Set.of());
        CurrentUser business = new CurrentUser(6L, 1L, "business1", "商务", "EMPLOYEE", null,
                Set.of("BUSINESS"), Set.of("institution:read", "institution:write"), Set.of());
        CurrentUser staff = new CurrentUser(10L, 1L, "operator1", "机构操作员", "INSTITUTION", 1L,
                Set.of("INSTITUTION_STAFF"), Set.of("institution:read", "institution:change_request"), Set.of());
        CurrentUser finance = new CurrentUser(9L, 1L, "finance1", "财务", "EMPLOYEE", null,
                Set.of("FINANCE"), Set.of("institution:read", "institution:approve_finance"), Set.of());
        CurrentUser admin = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("institution:read", "institution:approve_finance"), Set.of());

        for (CurrentUser mgr : List.of(manager, managerLead)) {
            ChangeRequestResponse commResp = support.response(commercialReq, List.of(), mgr);
            assertThat(commResp.beforeMasked()).isNull();
            assertThat(commResp.afterMasked()).isNull();
            assertThat(commResp.evidenceFileId()).isNull();

            ChangeRequestResponse finResp = support.response(financeReq, List.of(), mgr);
            assertThat(finResp.beforeMasked()).isNull();
            assertThat(finResp.afterMasked()).isNull();
            assertThat(finResp.evidenceFileId()).isNull();

            ChangeRequestResponse dayResp = support.response(settlementDayReq, List.of(), mgr);
            assertThat(dayResp.afterMasked().get("value").asText()).isEqualTo("15");
            assertThat(dayResp.evidenceFileId()).isEqualTo(9003L);

            ChangeRequestResponse contResp = support.response(contractReq, List.of(), mgr);
            assertThat(contResp.afterMasked().get("status").asText()).isEqualTo("ACTIVE");
            assertThat(contResp.evidenceFileId()).isNull();
        }

        for (CurrentUser nonFinance : List.of(operation, business, staff)) {
            ChangeRequestResponse finResp = support.response(financeReq, List.of(), nonFinance);
            assertThat(finResp.beforeMasked()).isNull();
            assertThat(finResp.afterMasked()).isNull();
            assertThat(finResp.evidenceFileId()).isNull();

            ChangeRequestResponse commResp = support.response(commercialReq, List.of(), nonFinance);
            assertThat(commResp.afterMasked().get("value").asText()).isEqualTo("0.25");
            assertThat(commResp.evidenceFileId()).isEqualTo(9001L);
        }

        for (CurrentUser privileged : List.of(finance, admin)) {
            ChangeRequestResponse finResp = support.response(financeReq, List.of(), privileged);
            assertThat(finResp.beforeMasked().get("value").asText()).isEqualTo("****8866");
            assertThat(finResp.afterMasked().get("value").asText()).isEqualTo("****9999");
            assertThat(finResp.evidenceFileId()).isEqualTo(9002L);
        }
    }
}
