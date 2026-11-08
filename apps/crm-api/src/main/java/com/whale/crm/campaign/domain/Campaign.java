package com.whale.crm.campaign.domain;

import java.time.Instant;

public record Campaign(Long id, String campaignCode, String campaignName, String campaignType,
                       Instant startsAt, Instant endsAt, String status, Instant createdAt, Long version) {
}
