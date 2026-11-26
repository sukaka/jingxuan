package com.whale.crm.customer.api.dto;

import java.util.List;

/**
 * 单客户视角的多管家添加汇总（供客户详情与只读排查使用，不触发合并）。
 */
public record CustomerStewardSummaryResponse(
        Long customerId,
        int stewardCount,
        int inScopeStewardCount,
        int crossTeamStewardCount,
        List<String> matchReasons,
        String evidenceMasked,
        List<CustomerStewardRelationItemResponse> stewards) {
    public CustomerStewardSummaryResponse {
        matchReasons = matchReasons == null ? List.of() : List.copyOf(matchReasons);
        stewards = stewards == null ? List.of() : List.copyOf(stewards);
    }
}
