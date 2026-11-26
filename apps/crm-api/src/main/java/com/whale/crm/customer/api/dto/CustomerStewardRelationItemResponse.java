package com.whale.crm.customer.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 同一顾客添加的单个管家明细。
 * 当 inMyScope=false（跨团队管家）时，仅展示管家姓名与所属团队汇总，不返回跨组客户 ID/编号/称呼等明细。
 */
public record CustomerStewardRelationItemResponse(
        Long staffUserId,
        String staffName,
        Long teamId,
        String teamName,
        boolean inMyScope,
        Long customerId,
        String customerNo,
        String customerName,
        List<String> relationTypes,
        Instant addedAt) {
    public CustomerStewardRelationItemResponse {
        relationTypes = relationTypes == null ? List.of() : List.copyOf(relationTypes);
    }
}
