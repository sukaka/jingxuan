package com.whale.crm.annualreport.domain;

import java.time.Instant;

/**
 * 客户年终购买轨迹 H5 只读快照实体。
 */
public record AnnualReport(
        Long id,
        long tenantId,
        long customerId,
        long ownerStaffId,
        Long teamId,
        long createdBy,
        int reportYear,
        String shareKey,
        String toneCode,
        String toneReason,
        String displayNameSnapshot,
        String tierLabelSnapshot,
        String butlerNameSnapshot,
        int orderCount,
        long netSpendFen,
        int redeemCount,
        int remainingEntitlementCount,
        String snapshotJson,
        String status,
        String disableReason,
        Long disabledBy,
        Instant disabledAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
