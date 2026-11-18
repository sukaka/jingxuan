package com.whale.crm.complaint.domain;

import java.util.List;

public record ComplaintCaseQuery(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                 String status, String keyword, int offset, int size) {
    public ComplaintCaseQuery {
        teamIds = teamIds == null ? List.of() : List.copyOf(teamIds);
    }
}
