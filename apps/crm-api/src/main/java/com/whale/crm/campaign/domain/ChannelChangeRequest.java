package com.whale.crm.campaign.domain;

import java.time.Instant;

public record ChannelChangeRequest(Long id, Long channelId, String channelNo, String channelName,
                                   Long baseVersion, String beforeCipher, String afterCipher,
                                   String beforeMasked, String afterMasked, String reason,
                                   String status, Long requestedBy, String requestedByName,
                                   Instant requestedAt, Long reviewerUserId, String reviewerName,
                                   Instant reviewedAt, String reviewComment, Long version) {
}
