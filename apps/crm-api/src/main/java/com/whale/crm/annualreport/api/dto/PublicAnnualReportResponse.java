package com.whale.crm.annualreport.api.dto;

import java.util.List;

/**
 * C 端公开年终购买轨迹 H5 只读快照响应。
 * 绝不暴露 customerId、tenantId、ownerStaffId、手机号、企微外部 ID 或原始有赞密文。
 */
public record PublicAnnualReportResponse(
        int reportYear,
        String memberNo,
        String displayName,
        String tierLabel,
        String sealText,
        String butlerName,
        String firstMeetDate,
        long daysTogether,
        int orderCount,
        long netSpendFen,
        int categoryCount,
        int redeemCount,
        int institutionCount,
        int butlerResponseCount,
        String toneCode,
        String toneLabel,
        String toneReason,
        Copywriting copy,
        MidnightSnapshot midnight,
        FavoriteSnapshot favorite,
        VaultSnapshot remainingEntitlements,
        List<OrderSnapshot> orders
) {
    public PublicAnnualReportResponse {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }

    public record Copywriting(
            String salutation,
            String coverSubTitle,
            String igniteBtnText,
            String igniteHint,
            String firstMeetNote,
            String coverNextBtn,
            String scene1Title,
            String scene2Title,
            String midnightStory,
            String scene3Title,
            String favDesc,
            String scene4Title,
            String vaultBadge,
            String vaultCta,
            String personaTitle,
            String personaQuote,
            String butlerSign,
            String posterHeading
    ) {
    }

    public record MidnightSnapshot(
            String time,
            String date,
            String orderTitle,
            String eventType,
            String peakLabel,
            List<HourPoint> hourDistribution
    ) {
        public MidnightSnapshot {
            hourDistribution = hourDistribution == null ? List.of() : List.copyOf(hourDistribution);
        }
    }

    public record HourPoint(
            double hour,
            double weight,
            boolean latest,
            String label
    ) {
    }

    public record FavoriteSnapshot(
            String projectName,
            String beatPercent,
            List<String> tags,
            List<DimensionItem> dimensions,
            List<TopProjectItem> top3
    ) {
        public FavoriteSnapshot {
            tags = tags == null ? List.of() : List.copyOf(tags);
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
            top3 = top3 == null ? List.of() : List.copyOf(top3);
        }
    }

    public record DimensionItem(String name, int percent) {
    }

    public record TopProjectItem(String rank, String name, String countText) {
    }

    public record VaultSnapshot(
            int totalCount,
            String expireDate,
            List<VaultItem> items
    ) {
        public VaultSnapshot {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record VaultItem(String name, String subtitle, String countText) {
    }

    public record OrderSnapshot(
            String maskedOrderNo,
            String date,
            String md,
            String time,
            String season,
            String title,
            String category,
            long amountFen,
            String statusText,
            boolean open,
            String channel,
            List<OrderSkuSnapshot> skus
    ) {
        public OrderSnapshot {
            skus = skus == null ? List.of() : List.copyOf(skus);
        }
    }

    public record OrderSkuSnapshot(String name, String qtyText) {
    }
}
