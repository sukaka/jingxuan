package com.whale.crm.customer.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 客户唯一性排查分组：展示同一顾客添加了哪几位管家及所属团队，无需合并客户档案。
 * 跨团队客户档案详情按数据范围隔离，仅保留管家与团队汇总。
 */
public record MultiStewardCustomerGroupResponse(
        String groupKey,
        Long primaryCustomerId,
        String primaryCustomerNo,
        String displayName,
        String nickname,
        String mobileMasked,
        int stewardCount,
        int inScopeStewardCount,
        int crossTeamStewardCount,
        boolean crossTeam,
        List<String> matchReasons,
        String evidenceMasked,
        Instant latestAddedAt,
        List<CustomerStewardRelationItemResponse> stewards) {
    public MultiStewardCustomerGroupResponse {
        matchReasons = matchReasons == null ? List.of() : List.copyOf(matchReasons);
        stewards = stewards == null ? List.of() : List.copyOf(stewards);
    }
}
