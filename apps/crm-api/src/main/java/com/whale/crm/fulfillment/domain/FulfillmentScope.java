package com.whale.crm.fulfillment.domain;

import java.util.List;

public record FulfillmentScope(String scopeType, List<Long> teamIds, long selfId,
                               List<Long> institutionIds) {
    public FulfillmentScope {
        teamIds = teamIds == null ? null : List.copyOf(teamIds);
        institutionIds = institutionIds == null ? null : List.copyOf(institutionIds);
    }
}
