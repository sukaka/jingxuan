package com.whale.crm.customer.domain;

import java.util.List;

/**
 * crm_points_ledger 存取。金额与来源事实只追加；唯一键 (tenant_id, source_type, source_ref) 承载幂等。
 * 仅在人工确认合并时保留原流水并改挂主客户，不能修改 delta 或删除历史。
 */
public interface PointsLedgerRepository {

    List<PointsLedgerEntry> findByCustomerId(long tenantId, long customerId);

    /**
     * 账本余额（SUM(delta)），用于校验/对账；写路径仍同事务刷新 points_cached。
     */
    long sumDelta(long tenantId, long customerId);

    void insert(PointsLedgerEntry entry);

    int reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId);

    int reassignSpecific(long tenantId, List<Long> entryIds, long fromCustomerId, long toCustomerId);
}
