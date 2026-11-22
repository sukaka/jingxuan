package com.whale.crm.campaign.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository {
    List<Campaign> searchCampaigns(long tenantId, String keyword, String status, String campaignType,
                                   int offset, int size);
    long countCampaigns(long tenantId, String keyword, String status, String campaignType);
    Optional<Campaign> findCampaign(long tenantId, long id);
    Optional<Campaign> findCampaignForUpdate(long tenantId, long id);
    Optional<Campaign> findCampaignByCode(long tenantId, String code);
    void insertCampaign(long tenantId, Campaign campaign);
    int updateCampaign(long tenantId, Campaign campaign);

    List<CampaignBatch> findBatches(long tenantId, long campaignId);
    Optional<CampaignBatch> findBatch(long tenantId, long campaignId, long batchId);
    Optional<CampaignBatch> findBatchByCode(long tenantId, long campaignId, String code);
    void insertBatch(long tenantId, CampaignBatch batch);
    int updateBatch(long tenantId, CampaignBatch batch);

    List<PartnerChannel> searchChannels(long tenantId, String keyword, String status, String channelType,
                                        Long businessOwnerUserId, int offset, int size);
    long countChannels(long tenantId, String keyword, String status, String channelType, Long businessOwnerUserId);
    Optional<PartnerChannel> findChannel(long tenantId, long id);
    Optional<PartnerChannel> findChannelForUpdate(long tenantId, long id);
    Optional<PartnerChannel> findChannelByNo(long tenantId, String channelNo);
    void insertChannel(long tenantId, PartnerChannel channel);
    int updateChannel(long tenantId, PartnerChannel channel);
    int updateChannelStatus(long tenantId, long id, long version, String status);
    int updateChannelFinancials(long tenantId, long id, long version, BigDecimal commissionPercent,
                                String settlementMethod, String paymentAccountNameCipher,
                                String paymentAccountNoCipher, String paymentBankName);
    int countActiveEmployee(long tenantId, long userId);
    List<ChannelOwnerOption> listActiveChannelOwners(long tenantId);

    void insertChannelChangeRequest(long tenantId, ChannelChangeRequest request);
    Optional<ChannelChangeRequest> findChannelChangeRequest(long tenantId, long id);
    Optional<ChannelChangeRequest> findChannelChangeRequestForUpdate(long tenantId, long id);
    Optional<ChannelChangeRequest> findActiveChannelChangeRequest(long tenantId, long channelId);
    List<ChannelChangeRequest> searchChannelChangeRequests(long tenantId, Long channelId, String status,
                                                           int offset, int size);
    long countChannelChangeRequests(long tenantId, Long channelId, String status);
    int updateChannelChangeRequestStatus(long tenantId, long id, long version, String fromStatus, String toStatus,
                                         long reviewerUserId, Instant reviewedAt, String reviewComment);
}
