package com.whale.crm.campaign.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record CampaignView(Long id, String campaignCode, String campaignName, String campaignType,
                           Instant startsAt, Instant endsAt, String status, Instant createdAt, Long version,
                           List<CampaignBatch> batches) {
    public CampaignView {
        batches = batches == null ? null : Collections.unmodifiableList(new ArrayList<>(batches));
    }

    public CampaignView(Campaign campaign, List<CampaignBatch> batches) {
        this(campaign.id(), campaign.campaignCode(), campaign.campaignName(), campaign.campaignType(),
                campaign.startsAt(), campaign.endsAt(), campaign.status(), campaign.createdAt(), campaign.version(), batches);
    }
}
