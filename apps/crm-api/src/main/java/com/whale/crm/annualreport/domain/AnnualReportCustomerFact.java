package com.whale.crm.annualreport.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 从 CRM 真实多表只读聚合所需的投影行类型。
 */
public final class AnnualReportCustomerFact {

    private AnnualReportCustomerFact() {
    }

    public record OrderRow(
            long orderId,
            String orderNo,
            String externalOrderNo,
            String sourceSystem,
            String orderTypeCode,
            String status,
            long paidFen,
            long refundedFen,
            Instant orderedAt,
            Instant paidAt
    ) {
    }

    public record OrderItemRow(
            long orderId,
            String skuNameSnapshot,
            int purchaseQuantity
    ) {
    }

    public record EntitlementRow(
            long entitlementId,
            long orderId,
            long projectId,
            String projectName,
            String categoryCode,
            int grantedCount,
            int availableCount,
            LocalDate validTo,
            String status
    ) {
    }

    public record RedemptionRow(
            long redemptionId,
            long institutionId,
            String institutionName,
            Instant serviceAt,
            long projectId,
            String projectName,
            String categoryCode,
            int count
    ) {
    }

    public record FollowupRow(
            long id,
            Instant contactAt
    ) {
    }

    public record ReportListRow(
            long id,
            long customerId,
            long ownerStaffId,
            Long teamId,
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
            String status,
            Instant createdAt,
            long version,
            long visitCount,
            long totalVisibleDurationMs,
            int maxSceneReached,
            int maxViewedSceneCount,
            boolean generatedPoster,
            boolean clickedBookButler,
            Instant lastActiveAt
    ) {
    }

    public record ReportSummaryRow(
            long totalReports,
            long activeReports,
            long openedReports,
            long completedReports,
            long totalSessions,
            long totalVisibleDurationMs,
            long posterReports,
            long bookingIntentReports,
            long reachedScene1,
            long reachedScene2,
            long reachedScene3,
            long reachedScene4,
            long reachedScene5,
            long reachedScene6
    ) {
    }
}
