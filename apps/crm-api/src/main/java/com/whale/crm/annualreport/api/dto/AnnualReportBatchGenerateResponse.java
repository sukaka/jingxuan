package com.whale.crm.annualreport.api.dto;

import java.util.List;

/**
 * 批量生成年度报告响应：明确区分成功生成与跳过的客户，不掩盖越权或已删除项。
 */
public record AnnualReportBatchGenerateResponse(
        int reportYear,
        int requestedCount,
        int createdCount,
        int skippedCount,
        List<AnnualReportItemResponse> items,
        List<SkippedCustomer> skipped
) {
    public AnnualReportBatchGenerateResponse {
        items = items == null ? List.of() : List.copyOf(items);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    public record SkippedCustomer(long customerId, String reason) {
    }
}
