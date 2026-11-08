package com.whale.crm.reporting.domain;

import java.util.List;

public record ReportScope(String scopeType, List<Long> teamIds, long selfId) {
    public ReportScope {
        teamIds = teamIds == null ? List.of() : List.copyOf(teamIds);
    }

    public boolean empty() {
        return !"ALL".equals(scopeType) && !"TEAM".equals(scopeType) && !"SELF".equals(scopeType);
    }
}
