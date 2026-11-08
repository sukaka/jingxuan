package com.whale.crm.institution.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.institution.domain.ProfileChangeStep;

import java.time.Instant;
import java.util.List;

public record ChangeRequestResponse(Long id, Long institutionId, String changeType, String fieldCode,
                                    Long targetId, Long baseVersion, Long targetVersion,
                                    JsonNode beforeMasked, JsonNode afterMasked, String reason,
                                    Long evidenceFileId, String status, String currentStep,
                                    Long requestedBy, Instant requestedAt, Instant completedAt,
                                    Long version, List<ProfileChangeStep> steps) {
    public ChangeRequestResponse {
        steps = steps == null ? null : List.copyOf(steps);
    }
}
