package com.whale.crm.campaign.domain;

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

    List<PartnerChannel> searchChannels(long tenantId, String keyword, String status, Long businessOwnerUserId,
                                        int offset, int size);
    long countChannels(long tenantId, String keyword, String status, Long businessOwnerUserId);
    Optional<PartnerChannel> findChannel(long tenantId, long id);
    Optional<PartnerChannel> findChannelByNo(long tenantId, String channelNo);
    void insertChannel(long tenantId, PartnerChannel channel);
    int updateChannel(long tenantId, PartnerChannel channel);
    int countActiveEmployee(long tenantId, long userId);
}
