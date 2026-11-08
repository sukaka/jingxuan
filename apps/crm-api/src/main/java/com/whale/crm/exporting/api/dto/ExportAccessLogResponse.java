package com.whale.crm.exporting.api.dto;

import com.whale.crm.exporting.domain.ExportAccessLog;

import java.time.Instant;

public record ExportAccessLogResponse(Long id, Long jobId, Long actorUserId, String action,
                                      String result, String reason, String denialCode, Instant createdAt) {
    public static ExportAccessLogResponse from(ExportAccessLog log) {
        return new ExportAccessLogResponse(log.getId(), log.getJobId(), log.getActorUserId(), log.getAction(),
                log.getResult(), log.getReason(), log.getDenialCode(), log.getCreatedAt());
    }
}
