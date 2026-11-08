package com.whale.crm.material.domain;

import java.util.List;

public record MaterialScopeQuery(String scopeType, List<Long> institutionIds, long selfId,
                                 boolean canRead, boolean canWrite, boolean canReview) {
    public MaterialScopeQuery {
        institutionIds = institutionIds == null ? null : List.copyOf(institutionIds);
    }

    public boolean isAll() {
        return "ALL".equals(scopeType);
    }
}
