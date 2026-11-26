package com.whale.crm.integration.youzan.customer.adapter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 有赞客户拉取适配器接口：仅在 adapter 层封装有赞官方接口调用，绝不将供应商 DTO 泄露到客户领域实体。
 */
public interface YouzanCustomerPullClient {

    String name();

    boolean officialEnabled();

    YouzanCustomerPage pullScrmPage(long tenantId, String shopId, Instant createdStart, Instant createdEnd,
                                    int pageNo, int pageSize);

    YouzanCustomerPage pullOrderBuyersPage(long tenantId, String shopId, Instant windowStart, Instant windowEnd,
                                           int pageNo, int pageSize);

    YouzanCustomerEnrichment enrichCustomer(long tenantId, String shopId, String yzOpenId,
                                            Integer fansType, Long fansId, String plainMobile);

    record YouzanPulledCustomer(
            String yzOpenId,
            String name,
            String nickname,
            String mobile,
            String genderHint,
            LocalDate birthday,
            Instant createdAt,
            Integer fansType,
            Long fansId,
            String remark
    ) {
    }

    record YouzanCustomerPage(
            List<YouzanPulledCustomer> items,
            long totalResults,
            boolean hasMore,
            List<String> warnings
    ) {
        public YouzanCustomerPage {
            items = items == null ? List.of() : List.copyOf(items);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    record YouzanCustomerEnrichment(
            String name,
            String nickname,
            String mobile,
            String genderHint,
            LocalDate birthday,
            String warning
    ) {
        public static YouzanCustomerEnrichment empty(String warning) {
            return new YouzanCustomerEnrichment(null, null, null, "UNKNOWN", null, warning);
        }
    }

    /**
     * 过滤有赞安全密文字串（形如 $...$1$），防止密文污染姓名、昵称或手机号。
     */
    static String stripCiphertext(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 8 && trimmed.startsWith("$") && trimmed.endsWith("$")) {
            return null;
        }
        return trimmed;
    }
}
