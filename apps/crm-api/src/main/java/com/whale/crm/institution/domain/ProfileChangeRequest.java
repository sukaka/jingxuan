package com.whale.crm.institution.domain;

import java.time.Instant;

public record ProfileChangeRequest(Long id, Long institutionId, String changeType, String fieldCode,
                                   Long targetId, Long baseVersion, Long targetVersion,
                                   String beforeCipher, String afterCipher, String beforeMasked, String afterMasked,
                                   String reason, Long evidenceFileId, String status, String currentStep,
                                   Long requestedBy, Instant requestedAt, Instant completedAt,
                                   String activeTarget, Instant createdAt, Long version) {
}
