package com.whale.crm.customer.api.dto;

import java.util.List;

/**
 * 查重扫描结果：新建候选数 + 本次新建明细（幂等，已存在同主从候选不重复生成）。
 */
public record MergeScanResultResponse(int created, List<MergeCandidateItemResponse> items) {
    public MergeScanResultResponse {
        items = items == null ? null : List.copyOf(items);
    }
}
