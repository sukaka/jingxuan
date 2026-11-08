package com.whale.crm.campaign.domain;

import java.time.Instant;

public record CampaignBatch(Long id, Long campaignId, String batchCode, String batchName,
                            Instant startsAt, Instant endsAt, String status, Instant createdAt, Long version) {
}
