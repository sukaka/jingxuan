package com.whale.crm.campaign.infrastructure;

import com.whale.crm.campaign.domain.Campaign;
import com.whale.crm.campaign.domain.CampaignBatch;
import com.whale.crm.campaign.domain.CampaignRepository;
import com.whale.crm.campaign.domain.ChannelChangeRequest;
import com.whale.crm.campaign.domain.ChannelOwnerOption;
import com.whale.crm.campaign.domain.PartnerChannel;
import com.whale.crm.campaign.infrastructure.mapper.CampaignMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisCampaignRepository implements CampaignRepository {
    private final CampaignMapper mapper;

    public MybatisCampaignRepository(CampaignMapper mapper) {
        this.mapper = mapper;
    }

    public List<Campaign> searchCampaigns(long tenantId, String keyword, String status, String campaignType,
                                          int offset, int size) {
        return mapper.searchCampaigns(tenantId, keyword, status, campaignType, offset, size);
    }

    public long countCampaigns(long tenantId, String keyword, String status, String campaignType) {
        return mapper.countCampaigns(tenantId, keyword, status, campaignType);
    }

    public Optional<Campaign> findCampaign(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectCampaign(tenantId, id));
    }

    public Optional<Campaign> findCampaignForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectCampaignForUpdate(tenantId, id));
    }

    public Optional<Campaign> findCampaignByCode(long tenantId, String code) {
        return Optional.ofNullable(mapper.selectCampaignByCode(tenantId, code));
    }

    public void insertCampaign(long tenantId, Campaign campaign) { mapper.insertCampaign(tenantId, campaign); }
    public int updateCampaign(long tenantId, Campaign campaign) { return mapper.updateCampaign(tenantId, campaign); }

    public List<CampaignBatch> findBatches(long tenantId, long campaignId) {
        return mapper.selectBatches(tenantId, campaignId);
    }

    public Optional<CampaignBatch> findBatch(long tenantId, long campaignId, long batchId) {
        return Optional.ofNullable(mapper.selectBatch(tenantId, campaignId, batchId));
    }

    public Optional<CampaignBatch> findBatchByCode(long tenantId, long campaignId, String code) {
        return Optional.ofNullable(mapper.selectBatchByCode(tenantId, campaignId, code));
    }

    public void insertBatch(long tenantId, CampaignBatch batch) { mapper.insertBatch(tenantId, batch); }
    public int updateBatch(long tenantId, CampaignBatch batch) { return mapper.updateBatch(tenantId, batch); }

    public List<PartnerChannel> searchChannels(long tenantId, String keyword, String status, String channelType,
                                               Long businessOwnerUserId, int offset, int size) {
        return mapper.searchChannels(tenantId, keyword, status, channelType, businessOwnerUserId, offset, size);
    }

    public long countChannels(long tenantId, String keyword, String status, String channelType, Long businessOwnerUserId) {
        return mapper.countChannels(tenantId, keyword, status, channelType, businessOwnerUserId);
    }

    public Optional<PartnerChannel> findChannel(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectChannel(tenantId, id));
    }

    public Optional<PartnerChannel> findChannelForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectChannelForUpdate(tenantId, id));
    }

    public Optional<PartnerChannel> findChannelByNo(long tenantId, String channelNo) {
        return Optional.ofNullable(mapper.selectChannelByNo(tenantId, channelNo));
    }

    public void insertChannel(long tenantId, PartnerChannel channel) { mapper.insertChannel(tenantId, channel); }
    public int updateChannel(long tenantId, PartnerChannel channel) { return mapper.updateChannel(tenantId, channel); }

    public int updateChannelStatus(long tenantId, long id, long version, String status) {
        return mapper.updateChannelStatus(tenantId, id, version, status);
    }

    public int updateChannelFinancials(long tenantId, long id, long version, BigDecimal commissionPercent,
                                       String settlementMethod, String paymentAccountNameCipher,
                                       String paymentAccountNoCipher, String paymentBankName) {
        return mapper.updateChannelFinancials(tenantId, id, version, commissionPercent, settlementMethod,
                paymentAccountNameCipher, paymentAccountNoCipher, paymentBankName);
    }

    public int countActiveEmployee(long tenantId, long userId) { return mapper.countActiveEmployee(tenantId, userId); }
    public List<ChannelOwnerOption> listActiveChannelOwners(long tenantId) {
        return mapper.selectActiveChannelOwners(tenantId);
    }

    public void insertChannelChangeRequest(long tenantId, ChannelChangeRequest request) {
        mapper.insertChannelChangeRequest(tenantId, request);
    }

    public Optional<ChannelChangeRequest> findChannelChangeRequest(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectChannelChangeRequest(tenantId, id));
    }

    public Optional<ChannelChangeRequest> findChannelChangeRequestForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectChannelChangeRequestForUpdate(tenantId, id));
    }

    public Optional<ChannelChangeRequest> findActiveChannelChangeRequest(long tenantId, long channelId) {
        return Optional.ofNullable(mapper.selectActiveChannelChangeRequest(tenantId, channelId));
    }

    public List<ChannelChangeRequest> searchChannelChangeRequests(long tenantId, Long channelId, String status,
                                                                  int offset, int size) {
        return mapper.searchChannelChangeRequests(tenantId, channelId, status, offset, size);
    }

    public long countChannelChangeRequests(long tenantId, Long channelId, String status) {
        return mapper.countChannelChangeRequests(tenantId, channelId, status);
    }

    public int updateChannelChangeRequestStatus(long tenantId, long id, long version, String fromStatus,
                                                String toStatus, long reviewerUserId, Instant reviewedAt,
                                                String reviewComment) {
        return mapper.updateChannelChangeRequestStatus(tenantId, id, version, fromStatus, toStatus,
                reviewerUserId, reviewedAt, reviewComment);
    }
}
