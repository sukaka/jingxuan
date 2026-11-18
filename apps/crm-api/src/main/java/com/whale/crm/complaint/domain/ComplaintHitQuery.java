package com.whale.crm.complaint.domain;

import java.util.List;

public record ComplaintHitQuery(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                String status, String routingStatus, String keyword, int offset, int size) {
    public ComplaintHitQuery {
        teamIds = teamIds == null ? List.of() : List.copyOf(teamIds);
    }
}
