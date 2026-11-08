package com.whale.crm.campaign.domain;

import java.time.Instant;

public record PartnerChannel(Long id, String channelNo, String name, String platformCode,
                             Long businessOwnerUserId, String status, String remarks,
                             Instant createdAt, Long version) {
}
