package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomDataGap(Long id, String corpId, String archiveScope, String gapType, String status,
                           Long fromSeq, Long toSeq, String detailMasked, Instant firstSeenAt,
                           Instant lastSeenAt, Instant resolvedAt) {
}
