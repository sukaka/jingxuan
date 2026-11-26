package com.whale.crm.annualreport.adapter;

import java.time.Instant;
import java.util.List;

/**
 * 有赞历史订单只读快照与买家资料拉取适配器（只读拉取，不写入 CRM 交易账本）。
 */
public interface YouzanAnnualPullClient {

    /**
     * 拉取指定时间窗内单页有赞订单详情（自动保证含商品标题与买家标识）。
     */
    PulledOrderPage pullOrderPage(long tenantId, String shopId, Instant windowStart, Instant windowEnd,
                                  int pageNo, int pageSize);

    /**
     * 按 yz_open_id 补全买家姓名、昵称、性别、微信 OpenID/UnionID 及手机号（兼容旧三参调用）。
     */
    default BuyerProfileEnrichment fetchBuyerProfile(long tenantId, String shopId, String yzOpenId) {
        return fetchBuyerProfile(tenantId, shopId, yzOpenId, null, null, null);
    }

    /**
     * 按 yz_open_id 与订单侧可选的 fans_type / fans_id / 明文手机号补全买家资料
     * （若应用未开通对应权限，返回空字段与提示，不阻断订单拉取）。
     */
    BuyerProfileEnrichment fetchBuyerProfile(long tenantId, String shopId, String yzOpenId,
                                             Integer fansType, Long fansId, String orderMobile);

    /**
     * 判断字符串是否为有赞隐私加密密文（如 {@code $<base64>$1$}），避免密文泄露到展示姓名或手机号字段。
     */
    static boolean isYouzanCiphertext(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 5 || !trimmed.startsWith("$") || !trimmed.endsWith("$")) {
            return false;
        }
        int lastSep = trimmed.lastIndexOf('$', trimmed.length() - 2);
        if (lastSep <= 1 || lastSep >= trimmed.length() - 2) {
            return false;
        }
        for (int i = lastSep + 1; i < trimmed.length() - 1; i++) {
            char ch = trimmed.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 过滤空白与有赞隐私密文（{@code $...$1$}），仅保留可读明文。
     */
    static String stripCiphertext(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return isYouzanCiphertext(trimmed) ? null : trimmed;
    }

    record PulledOrderPage(
            List<PulledOrder> orders,
            long totalResults,
            boolean hasMore
    ) {
        public PulledOrderPage {
            orders = orders == null ? List.of() : List.copyOf(orders);
        }
    }

    record PulledOrder(
            String tid,
            String yzOpenId,
            String orderStatus,
            boolean paid,
            Instant orderedAt,
            Instant paidAt,
            long paymentFen,
            long refundedFen,
            String buyerNickname,
            String buyerMobile,
            String receiverName,
            String titleSummary,
            List<PulledOrderItem> items,
            Integer buyerFansType,
            Long buyerFansId
    ) {
        public PulledOrder {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public PulledOrder(
                String tid,
                String yzOpenId,
                String orderStatus,
                boolean paid,
                Instant orderedAt,
                Instant paidAt,
                long paymentFen,
                long refundedFen,
                String buyerNickname,
                String buyerMobile,
                String receiverName,
                String titleSummary,
                List<PulledOrderItem> items
        ) {
            this(tid, yzOpenId, orderStatus, paid, orderedAt, paidAt, paymentFen, refundedFen,
                    buyerNickname, buyerMobile, receiverName, titleSummary, items, null, null);
        }
    }

    record PulledOrderItem(
            String itemId,
            String skuId,
            String title,
            String skuProps,
            int num,
            long priceFen
    ) {
    }

    record BuyerProfileEnrichment(
            String name,
            String nickname,
            String genderHint,
            String weixinOpenId,
            String weixinUnionId,
            String mobile,
            String warning
    ) {
        public static BuyerProfileEnrichment empty(String warning) {
            return new BuyerProfileEnrichment(null, null, "UNKNOWN", null, null, null, warning);
        }
    }
}
