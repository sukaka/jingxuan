package com.whale.crm.campaign.infrastructure.mapper;

import com.whale.crm.campaign.domain.Campaign;
import com.whale.crm.campaign.domain.CampaignBatch;
import com.whale.crm.campaign.domain.ChannelChangeRequest;
import com.whale.crm.campaign.domain.ChannelOwnerOption;
import com.whale.crm.campaign.domain.PartnerChannel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Mapper
public interface CampaignMapper {
    List<Campaign> searchCampaigns(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                                   @Param("status") String status, @Param("campaignType") String campaignType,
                                   @Param("offset") int offset, @Param("size") int size);
    long countCampaigns(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                        @Param("status") String status, @Param("campaignType") String campaignType);
    Campaign selectCampaign(@Param("tenantId") long tenantId, @Param("id") long id);
    Campaign selectCampaignForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    Campaign selectCampaignByCode(@Param("tenantId") long tenantId, @Param("code") String code);
    void insertCampaign(@Param("tenantId") long tenantId, @Param("entity") Campaign campaign);
    int updateCampaign(@Param("tenantId") long tenantId, @Param("entity") Campaign campaign);

    List<CampaignBatch> selectBatches(@Param("tenantId") long tenantId, @Param("campaignId") long campaignId);
    CampaignBatch selectBatch(@Param("tenantId") long tenantId, @Param("campaignId") long campaignId,
                              @Param("batchId") long batchId);
    CampaignBatch selectBatchByCode(@Param("tenantId") long tenantId, @Param("campaignId") long campaignId,
                                    @Param("code") String code);
    void insertBatch(@Param("tenantId") long tenantId, @Param("entity") CampaignBatch batch);
    int updateBatch(@Param("tenantId") long tenantId, @Param("entity") CampaignBatch batch);

    List<PartnerChannel> searchChannels(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                                        @Param("status") String status, @Param("channelType") String channelType,
                                        @Param("businessOwnerUserId") Long businessOwnerUserId,
                                        @Param("offset") int offset, @Param("size") int size);
    long countChannels(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                       @Param("status") String status, @Param("channelType") String channelType,
                       @Param("businessOwnerUserId") Long businessOwnerUserId);
    PartnerChannel selectChannel(@Param("tenantId") long tenantId, @Param("id") long id);
    PartnerChannel selectChannelForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    PartnerChannel selectChannelByNo(@Param("tenantId") long tenantId, @Param("channelNo") String channelNo);
    void insertChannel(@Param("tenantId") long tenantId, @Param("entity") PartnerChannel channel);
    int updateChannel(@Param("tenantId") long tenantId, @Param("entity") PartnerChannel channel);
    int updateChannelStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                            @Param("version") long version, @Param("status") String status);
    int updateChannelFinancials(@Param("tenantId") long tenantId, @Param("id") long id,
                                @Param("version") long version,
                                @Param("commissionPercent") BigDecimal commissionPercent,
                                @Param("settlementMethod") String settlementMethod,
                                @Param("paymentAccountNameCipher") String paymentAccountNameCipher,
                                @Param("paymentAccountNoCipher") String paymentAccountNoCipher,
                                @Param("paymentBankName") String paymentBankName);
    int countActiveEmployee(@Param("tenantId") long tenantId, @Param("userId") long userId);
    List<ChannelOwnerOption> selectActiveChannelOwners(@Param("tenantId") long tenantId);

    void insertChannelChangeRequest(@Param("tenantId") long tenantId, @Param("entity") ChannelChangeRequest request);
    ChannelChangeRequest selectChannelChangeRequest(@Param("tenantId") long tenantId, @Param("id") long id);
    ChannelChangeRequest selectChannelChangeRequestForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    ChannelChangeRequest selectActiveChannelChangeRequest(@Param("tenantId") long tenantId,
                                                          @Param("channelId") long channelId);
    List<ChannelChangeRequest> searchChannelChangeRequests(@Param("tenantId") long tenantId,
                                                           @Param("channelId") Long channelId,
                                                           @Param("status") String status,
                                                           @Param("offset") int offset,
                                                           @Param("size") int size);
    long countChannelChangeRequests(@Param("tenantId") long tenantId,
                                    @Param("channelId") Long channelId,
                                    @Param("status") String status);
    int updateChannelChangeRequestStatus(@Param("tenantId") long tenantId,
                                         @Param("id") long id,
                                         @Param("version") long version,
                                         @Param("fromStatus") String fromStatus,
                                         @Param("toStatus") String toStatus,
                                         @Param("reviewerUserId") long reviewerUserId,
                                         @Param("reviewedAt") Instant reviewedAt,
                                         @Param("reviewComment") String reviewComment);
}
