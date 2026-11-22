package com.whale.crm.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.campaign.api.dto.ChannelFinancialChangeRequestResponse;
import com.whale.crm.campaign.api.dto.CreateCampaignRequest;
import com.whale.crm.campaign.api.dto.CreateChannelFinancialChangeRequest;
import com.whale.crm.campaign.api.dto.CreateChannelRequest;
import com.whale.crm.campaign.api.dto.PartnerChannelResponse;
import com.whale.crm.campaign.api.dto.ReviewChannelFinancialChangeRequest;
import com.whale.crm.campaign.api.dto.ToggleChannelStatusRequest;
import com.whale.crm.campaign.api.dto.UpdateChannelRequest;
import com.whale.crm.campaign.application.CampaignApplicationService;
import com.whale.crm.campaign.application.ChannelMasker;
import com.whale.crm.campaign.domain.Campaign;
import com.whale.crm.campaign.domain.CampaignBatch;
import com.whale.crm.campaign.domain.CampaignRepository;
import com.whale.crm.campaign.domain.ChannelChangeRequest;
import com.whale.crm.campaign.domain.OrderAttributionSnapshot;
import com.whale.crm.campaign.domain.PartnerChannel;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CampaignApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");

    private final CampaignRepository repository = mock(CampaignRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AesGcmCipher cipher = mock(AesGcmCipher.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
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
    private final CampaignApplicationService service = new CampaignApplicationService(
            repository, auditService, cipher, idempotencyService, objectMapper, txManager);

    @BeforeEach
    void setUpCipher() {
        when(cipher.encrypt(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0, String.class) + ")");
        when(cipher.decrypt(anyString())).thenAnswer(inv -> {
            String value = inv.getArgument(0, String.class);
            if (value.startsWith("ENC(") && value.endsWith(")")) {
                return value.substring(4, value.length() - 1);
            }
            return value;
        });
    }

    @Test
    void resolvesOwnedBatchAndRealChannelIntoSaleSnapshots() {
        Campaign campaign = campaign("ACTIVE", NOW.minusSeconds(60), NOW.plusSeconds(60));
        CampaignBatch batch = batch(NOW.minusSeconds(30), NOW.plusSeconds(30));
        PartnerChannel channel = channel("ACTIVE", 44L);
        when(repository.findCampaignForUpdate(1L, 11L)).thenReturn(Optional.of(campaign));
        when(repository.findBatch(1L, 11L, 22L)).thenReturn(Optional.of(batch));
        when(repository.findChannel(1L, 33L)).thenReturn(Optional.of(channel));
        when(repository.countActiveEmployee(1L, 44L)).thenReturn(1);

        OrderAttributionSnapshot result = service.resolveOrderAttribution(1L, 11L, 22L, 33L, NOW);

        assertThat(result.campaignId()).isEqualTo(11L);
        assertThat(result.campaignBatchId()).isEqualTo(22L);
        assertThat(result.campaignCodeSnapshot()).isEqualTo("CAMPAIGN");
        assertThat(result.campaignNameSnapshot()).isEqualTo("活动原名");
        assertThat(result.batchCodeSnapshot()).isEqualTo("BATCH");
        assertThat(result.batchNameSnapshot()).isEqualTo("批次原名");
        assertThat(result.channelId()).isEqualTo(33L);
        assertThat(result.businessOwnerAtSaleId()).isEqualTo(44L);
    }

    @Test
    void emptySelectionStaysEmptyAndDoesNotInventCampaign() {
        OrderAttributionSnapshot result = service.resolveOrderAttribution(1L, null, null, null, NOW);

        assertThat(result.campaignId()).isNull();
        assertThat(result.campaignBatchId()).isNull();
        assertThat(result.campaignNameSnapshot()).isNull();
        assertThat(result.channelId()).isNull();
        assertThat(result.businessOwnerAtSaleId()).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void mismatchedBatchAndExpiredCampaignAreRejected() {
        Campaign campaign = campaign("ACTIVE", NOW.minusSeconds(60), NOW.plusSeconds(60));
        when(repository.findCampaignForUpdate(1L, 11L)).thenReturn(Optional.of(campaign));
        when(repository.findBatch(1L, 11L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveOrderAttribution(1L, 11L, 99L, null, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于所选活动");

        Campaign expired = campaign("ACTIVE", NOW.minusSeconds(120), NOW.minusSeconds(1));
        when(repository.findCampaignForUpdate(1L, 11L)).thenReturn(Optional.of(expired));
        when(repository.findBatch(1L, 11L, 22L)).thenReturn(Optional.of(batch(null, null)));
        assertThatThrownBy(() -> service.resolveOrderAttribution(1L, 11L, 22L, null, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在有效期内");
    }

    @Test
    void nonActiveBatchCannotCreateAttribution() {
        when(repository.findCampaignForUpdate(1L, 11L))
                .thenReturn(Optional.of(campaign("ACTIVE", NOW.minusSeconds(60), NOW.plusSeconds(60))));
        when(repository.findBatch(1L, 11L, 22L)).thenReturn(Optional.of(batch("DRAFT", null, null)));

        assertThatThrownBy(() -> service.resolveOrderAttribution(1L, 11L, 22L, null, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("批次不存在或未启用");
    }

    @Test
    void inactiveChannelAndUnrealBusinessOwnerCannotCreateAttribution() {
        when(repository.findChannel(1L, 33L)).thenReturn(Optional.of(channel("INACTIVE", 44L)));
        assertThatThrownBy(() -> service.resolveOrderAttribution(1L, null, null, 33L, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("渠道不存在或未启用");

        when(repository.findChannel(1L, 33L)).thenReturn(Optional.of(channel("ACTIVE", 44L)));
        when(repository.countActiveEmployee(1L, 44L)).thenReturn(0);
        assertThatThrownBy(() -> service.resolveOrderAttribution(1L, null, null, 33L, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("渠道负责人");
    }

    @Test
    void crossTenantCampaignWriteIsDeniedBeforeRepositoryAccess() {
        CurrentUser otherTenant = user(2L, "campaign:write");
        CreateCampaignRequest request = new CreateCampaignRequest("TENANT-CHECK", "越租户活动",
                "REGULAR", null, null, "ACTIVE");

        assertThatThrownBy(() -> service.createCampaign(1L, otherTenant, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(repository, auditService);
    }

    @Test
    void channelCreateDoesNotAcceptUnknownOwner() {
        CurrentUser admin = user(1L, "channel:write");
        CreateChannelRequest request = channelRequest(null, null, null, null, null, 999L);
        when(repository.findChannelByNo(1L, "CHANNEL-CHECK")).thenReturn(Optional.empty());
        when(repository.countActiveEmployee(1L, 999L)).thenReturn(0);

        assertThatThrownBy(() -> service.createChannel(1L, admin, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同租户启用中的内部员工");
        verify(repository, never()).insertChannel(anyLong(), any());
    }

    @Test
    void channelProfileRejectsInvalidPhoneIdCardCommissionDatesAndMaskedWildcards() {
        CurrentUser admin = user(1L, "channel:write");

        assertThatThrownBy(() -> service.createChannel(1L, admin,
                channelRequest("138000000000", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("11位");
        assertThatThrownBy(() -> service.createChannel(1L, admin,
                channelRequest(null, "12345678", null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("18位");
        assertThatThrownBy(() -> service.createChannel(1L, admin,
                channelRequest(null, "110105194912310021", null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("校验码");
        assertThatThrownBy(() -> service.createChannel(1L, admin,
                channelRequest(null, null, BigDecimal.valueOf(100.01), null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("100%");
        assertThatThrownBy(() -> service.createChannel(1L, admin,
                channelRequest(null, null, null, LocalDate.parse("2026-02-01"),
                        LocalDate.parse("2026-01-01"), null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不得早于");
        assertThatThrownBy(() -> service.createChannel(1L, admin,
                new CreateChannelRequest("CHANNEL-WILDCARD-1", "渠道", "BLOGGER", "WEIBO", null, null, null,
                        null, null, null, null, null, "张*", null, null, "ACTIVE", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.createChannel(1L, admin,
                new CreateChannelRequest("CHANNEL-WILDCARD-2", "渠道", "BLOGGER", "WEIBO", null, null, null,
                        null, null, null, null, null, null, "****0001", null, "ACTIVE", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repository, never()).insertChannel(anyLong(), any());
    }

    @Test
    void channelMaskerFormatsAndChannelResponsesMaskSensitiveFields() {
        assertThat(ChannelMasker.maskPhone("13800000000")).isEqualTo("138****0000");
        assertThat(ChannelMasker.maskIdCard("11010519491231002X")).isEqualTo("110105********002X");
        assertThat(ChannelMasker.maskAccountName("张三")).isEqualTo("张*");
        assertThat(ChannelMasker.maskAccountName("合成收款账户")).isEqualTo("合****户");
        assertThat(ChannelMasker.maskAccountNo("6222020200000001")).isEqualTo("****0001");
        assertThat(ChannelMasker.maskAccountNo("1234")).isEqualTo("****");

        PartnerChannel stored = new PartnerChannel(33L, "CHANNEL-1", "渠道", "BLOGGER", "WEIBO",
                new BigDecimal("12.50"), 6L, "孙周", "张三",
                "ENC(13800000000)", "ENC(11010519491231002X)", "MONTHLY", null, null,
                "ENC(合成收款账户)", "ENC(0000000000000001)", "测试银行", "ACTIVE", null, NOW, 0L);
        when(repository.searchChannels(1L, null, null, null, null, 0, 20)).thenReturn(List.of(stored));
        when(repository.countChannels(1L, null, null, null, null)).thenReturn(1L);

        PageResponse<PartnerChannelResponse> page = service.channels(1L, user(1L, "channel:read"),
                null, null, null, null, 1, 20);
        PartnerChannelResponse item = page.items().get(0);
        assertThat(item.contactPhone()).isEqualTo("138****0000");
        assertThat(item.contactPhoneConfigured()).isTrue();
        assertThat(item.idCardNumber()).isEqualTo("110105********002X");
        assertThat(item.idCardConfigured()).isTrue();
        assertThat(item.paymentAccountName()).isEqualTo("合****户");
        assertThat(item.paymentAccountNo()).isEqualTo("****0001");
        assertThat(item.paymentAccountConfigured()).isTrue();
    }

    @Test
    void patchPreservesBlankSensitiveCipherAndRejectsFinancialEditsWhenLocked() {
        CurrentUser writer = user(1L, "channel:write");
        PartnerChannel activeChannel = new PartnerChannel(33L, "CH-LOCKED", "渠道", "BLOGGER", "WEIBO",
                new BigDecimal("10.00"), 6L, "孙周", "张三",
                "ENC(13800000000)", "ENC(11010519491231002X)", "MONTHLY", null, null,
                "ENC(合成收款账户)", "ENC(0000000000000001)", "合成银行", "ACTIVE", null, NOW, 1L);
        when(repository.findChannelForUpdate(1L, 33L)).thenReturn(Optional.of(activeChannel));
        when(repository.countActiveEmployee(1L, 6L)).thenReturn(1);
        when(repository.updateChannel(eq(1L), any())).thenReturn(1);
        when(repository.findChannel(1L, 33L)).thenReturn(Optional.of(activeChannel));

        UpdateChannelRequest financialAttempt = new UpdateChannelRequest(null, "渠道改名", "BLOGGER", "WEIBO",
                new BigDecimal("15.00"), 6L, "张三", null, null, null, null, null,
                null, null, null, "ACTIVE", null, 1L);
        assertThatThrownBy(() -> service.updateChannel(1L, writer, 33L, financialAttempt))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        UpdateChannelRequest metadataOnly = new UpdateChannelRequest(null, "渠道改名", "BLOGGER", "WEIBO",
                null, 6L, "张三", "", " ", null, null, null,
                null, null, null, "ACTIVE", "更新备注", 1L);
        service.updateChannel(1L, writer, 33L, metadataOnly);
        verify(repository).updateChannel(eq(1L), argThat(updated ->
                "ENC(13800000000)".equals(updated.contactPhone())
                        && "ENC(11010519491231002X)".equals(updated.idCardNumber())
                        && "ENC(合成收款账户)".equals(updated.paymentAccountName())
                        && "ENC(0000000000000001)".equals(updated.paymentAccountNo())
                        && new BigDecimal("10.00").equals(updated.commissionPercent())
                        && "MONTHLY".equals(updated.settlementMethod())
                        && "合成银行".equals(updated.paymentBankName())));
    }

    @Test
    void toggleStatusUpdatesOnlyStatusAndChecksVersionAndArchived() {
        CurrentUser writer = user(1L, "channel:write");
        PartnerChannel active = channel("ACTIVE", 6L);
        PartnerChannel paused = channel("INACTIVE", 6L);
        when(repository.findChannelForUpdate(1L, 33L)).thenReturn(Optional.of(active));
        when(repository.updateChannelStatus(1L, 33L, 0L, "INACTIVE")).thenReturn(1);
        when(repository.findChannel(1L, 33L)).thenReturn(Optional.of(paused));

        PartnerChannelResponse toggled = service.toggleChannelStatus(1L, writer, 33L,
                new ToggleChannelStatusRequest("INACTIVE", 0L, "暂停合作"));
        assertThat(toggled.status()).isEqualTo("INACTIVE");

        assertThatThrownBy(() -> service.toggleChannelStatus(1L, writer, 33L,
                new ToggleChannelStatusRequest("INACTIVE", 99L, "版本错误")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        when(repository.findChannelForUpdate(1L, 33L)).thenReturn(Optional.of(channel("ARCHIVED", 6L)));
        assertThatThrownBy(() -> service.toggleChannelStatus(1L, writer, 33L,
                new ToggleChannelStatusRequest("ACTIVE", 0L, "归档不可改")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void financialChangeRequestAndReviewEnforceSodVersionConflictIdempotencyAndRoleMasking() {
        CurrentUser business = new CurrentUser(6L, 1L, "business1", "孙周", "EMPLOYEE", null,
                Set.of("BUSINESS"), Set.of("channel:read", "channel:write"), Set.of());
        CurrentUser finance = new CurrentUser(9L, 1L, "finance1", "财务专员", "EMPLOYEE", null,
                Set.of("FINANCE"), Set.of("channel:approve_finance"), Set.of());

        PartnerChannel active = new PartnerChannel(33L, "CH-33", "渠道33", "BLOGGER", "WEIBO",
                new BigDecimal("10.00"), 6L, "孙周", "张三",
                "ENC(13800000000)", "ENC(11010519491231002X)", "MONTHLY", null, null,
                "ENC(原收款户)", "ENC(6222000000001111)", "原开户行", "ACTIVE", null, NOW, 2L);
        when(repository.findChannelForUpdate(1L, 33L)).thenReturn(Optional.of(active));
        when(repository.findActiveChannelChangeRequest(1L, 33L)).thenReturn(Optional.empty()).thenReturn(Optional.of(
                new ChannelChangeRequest(101L, 33L, "CH-33", "渠道33", 2L,
                        "ENC({\"commissionPercent\":10.00,\"paymentAccountNo\":\"6222000000001111\"})",
                        "ENC({\"commissionPercent\":18.50,\"paymentAccountName\":\"新收款户\",\"paymentAccountNo\":\"6222000000009999\",\"paymentBankName\":\"新银行\"})",
                        "{\"commissionPercent\":10.00,\"paymentAccountNo\":\"****1111\"}",
                        "{\"commissionPercent\":18.50,\"paymentAccountName\":\"新**户\",\"paymentAccountNo\":\"****9999\",\"paymentBankName\":\"新银行\"}",
                        "调整收款账户", "PENDING_REVIEW", 6L, "孙周", NOW, null, null, null, null, 0L)));

        CreateChannelFinancialChangeRequest createReq = new CreateChannelFinancialChangeRequest(
                new BigDecimal("18.50"), null, "新收款户", "6222000000009999", "新银行", 2L, "调整收款账户");
        ChannelFinancialChangeRequestResponse created = service.createFinancialChangeRequest(
                1L, business, 33L, createReq, "idem-create-1");
        assertThat(created.afterMasked().get("commissionPercent").decimalValue()).isEqualByComparingTo("18.50");
        assertThat(created.afterMasked().get("paymentAccountName").asText()).isEqualTo("***");
        assertThat(created.afterMasked().get("paymentAccountNo").asText()).isEqualTo("****");
        assertThat(created.afterMasked().get("paymentBankName").asText()).isEqualTo("***");
        assertThat(created.paymentAccountName()).isNull();
        assertThat(created.paymentAccountNo()).isNull();
        assertThat(created.paymentBankName()).isNull();

        // Self review by requester (simulated if user has channel:approve_finance) -> 403 + independent audit
        CurrentUser selfReviewer = new CurrentUser(6L, 1L, "self", "自审", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("channel:approve_finance"), Set.of());
        ChannelChangeRequest pendingReq = new ChannelChangeRequest(101L, 33L, "CH-33", "渠道33", 2L,
                "ENC({\"commissionPercent\":10.00})",
                "ENC({\"commissionPercent\":18.50,\"paymentAccountName\":\"新收款户\",\"paymentAccountNo\":\"6222000000009999\",\"paymentBankName\":\"新银行\"})",
                "{\"commissionPercent\":10.00,\"paymentAccountNo\":\"****1111\"}",
                "{\"commissionPercent\":18.50,\"paymentAccountName\":\"新**户\",\"paymentAccountNo\":\"****9999\",\"paymentBankName\":\"新银行\"}",
                "调整收款账户", "PENDING_REVIEW", 6L, "孙周", NOW, null, null, null, null, 0L);
        when(repository.findChannelChangeRequestForUpdate(1L, 101L)).thenReturn(Optional.of(pendingReq));

        assertThatThrownBy(() -> service.reviewFinancialChangeRequest(1L, selfReviewer, 101L,
                new ReviewChannelFinancialChangeRequest("APPROVE", 0L, "self"), "idem-self"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(auditService).recordIndependent(eq(1L), eq(6L), eq("CHANNEL_SELF_REVIEW_DENIED"),
                eq("PARTNER_CHANNEL"), eq("33"), isNull(), any(), anyString());

        // baseVersion mismatch -> 409 CONFLICT
        PartnerChannel bumpedChannel = new PartnerChannel(33L, "CH-33", "渠道33", "BLOGGER", "WEIBO",
                new BigDecimal("10.00"), 6L, "孙周", "张三",
                "ENC(13800000000)", "ENC(11010519491231002X)", "MONTHLY", null, null,
                "ENC(原收款户)", "ENC(6222000000001111)", "原开户行", "ACTIVE", null, NOW, 3L);
        when(repository.findChannelForUpdate(1L, 33L)).thenReturn(Optional.of(bumpedChannel));
        assertThatThrownBy(() -> service.reviewFinancialChangeRequest(1L, finance, 101L,
                new ReviewChannelFinancialChangeRequest("APPROVE", 0L, "ok"), "idem-conflict"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // Matching version -> APPROVE succeeds and finance role sees last-4 masked bank fields
        when(repository.findChannelForUpdate(1L, 33L)).thenReturn(Optional.of(active));
        when(repository.updateChannelFinancials(eq(1L), eq(33L), eq(2L),
                argThat(v -> v != null && v.compareTo(new BigDecimal("18.50")) == 0), eq("MONTHLY"),
                eq("ENC(新收款户)"), eq("ENC(6222000000009999)"), eq("新银行"))).thenReturn(1);
        when(repository.updateChannelChangeRequestStatus(eq(1L), eq(101L), eq(0L), eq("PENDING_REVIEW"),
                eq("APPROVED"), eq(9L), any(), eq("同意"))).thenReturn(1);
        ChannelChangeRequest approvedReq = new ChannelChangeRequest(101L, 33L, "CH-33", "渠道33", 2L,
                pendingReq.beforeCipher(), pendingReq.afterCipher(),
                pendingReq.beforeMasked(), pendingReq.afterMasked(),
                "调整收款账户", "APPROVED", 6L, "孙周", NOW, 9L, "财务专员", NOW, "同意", 1L);
        when(repository.findChannelChangeRequest(1L, 101L)).thenReturn(Optional.of(approvedReq));

        ChannelFinancialChangeRequestResponse approved = service.reviewFinancialChangeRequest(
                1L, finance, 101L, new ReviewChannelFinancialChangeRequest("APPROVE", 0L, "同意"), "idem-ok");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.paymentAccountName()).isEqualTo("新**户");
        assertThat(approved.paymentAccountNo()).isEqualTo("****9999");
        assertThat(approved.paymentBankName()).isEqualTo("新银行");

        // Idempotency replay returns approved response without re-updating
        when(idempotencyService.lookup(eq(1L), eq("channel.financial-change.review"), eq("idem-ok"), anyString()))
                .thenReturn(Optional.of(new IdempotencyRecord()));
        ChannelFinancialChangeRequestResponse replayed = service.reviewFinancialChangeRequest(
                1L, finance, 101L, new ReviewChannelFinancialChangeRequest("APPROVE", 0L, "同意"), "idem-ok");
        assertThat(replayed.status()).isEqualTo("APPROVED");
    }

    private CreateChannelRequest channelRequest(String phone, String idCard, BigDecimal commission,
                                                LocalDate start, LocalDate end, Long ownerId) {
        return new CreateChannelRequest("CHANNEL-CHECK", "渠道", "BLOGGER", "WEIBO", commission, ownerId, null,
                phone, idCard, null, start, end, null, null, null, "ACTIVE", null);
    }

    private Campaign campaign(String status, Instant startsAt, Instant endsAt) {
        return new Campaign(11L, "CAMPAIGN", "活动原名", "PROMOTION", startsAt, endsAt, status,
                NOW.minusSeconds(200), 0L);
    }

    private CampaignBatch batch(Instant startsAt, Instant endsAt) {
        return batch("ACTIVE", startsAt, endsAt);
    }

    private CampaignBatch batch(String status, Instant startsAt, Instant endsAt) {
        return new CampaignBatch(22L, 11L, "BATCH", "批次原名", startsAt, endsAt, status,
                NOW.minusSeconds(100), 0L);
    }

    private PartnerChannel channel(String status, Long ownerId) {
        return new PartnerChannel(33L, "CHANNEL", "渠道原名", "BLOGGER", "WEIBO", null, ownerId, "测试商务", null, null,
                null, null, null, null, null, null, null, status, null, NOW.minusSeconds(100), 0L);
    }

    private CurrentUser user(long tenantId, String permission) {
        return new CurrentUser(1L, tenantId, "test", "测试", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of(permission), Set.of());
    }
}
