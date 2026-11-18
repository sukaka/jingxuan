package com.whale.crm.complaint.api.dto;

import com.whale.crm.complaint.domain.ComplaintEmpathyScript;

import java.time.Instant;

public record ComplaintEmpathyScriptResponse(long id, String title, String script, String status, Long createdBy,
                                             Instant createdAt, Instant updatedAt, long version) {
    public static ComplaintEmpathyScriptResponse from(ComplaintEmpathyScript script) {
        return new ComplaintEmpathyScriptResponse(script.id(), script.title(), script.script(), script.status(),
                script.createdBy(), script.createdAt(), script.updatedAt(), script.version());
    }
}
