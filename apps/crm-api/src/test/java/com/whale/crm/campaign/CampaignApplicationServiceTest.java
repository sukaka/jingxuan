package com.whale.crm.campaign;

import com.whale.crm.campaign.api.dto.CreateCampaignRequest;
import com.whale.crm.campaign.api.dto.CreateChannelRequest;
import com.whale.crm.campaign.application.CampaignApplicationService;
import com.whale.crm.campaign.domain.Campaign;
import com.whale.crm.campaign.domain.CampaignBatch;
import com.whale.crm.campaign.domain.CampaignRepository;
import com.whale.crm.campaign.domain.OrderAttributionSnapshot;
import com.whale.crm.campaign.domain.PartnerChannel;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CampaignApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");

    private final CampaignRepository repository = mock(CampaignRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final CampaignApplicationService service = new CampaignApplicationService(repository, auditService,
            mock(AesGcmCipher.class));

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
    void channelProfileRejectsInvalidPhoneIdCardCommissionAndDates() {
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
                channelRequest(null, null, java.math.BigDecimal.valueOf(100.01), null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("100%");
        assertThatThrownBy(() -> service.createChannel(1L, admin,
                channelRequest(null, null, null, java.time.LocalDate.parse("2026-02-01"),
                        java.time.LocalDate.parse("2026-01-01"), null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不得早于");
        verify(repository, never()).insertChannel(anyLong(), any());
    }

    private CreateChannelRequest channelRequest(String phone, String idCard, java.math.BigDecimal commission,
                                                java.time.LocalDate start, java.time.LocalDate end, Long ownerId) {
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
