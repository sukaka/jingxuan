package com.whale.crm.integration.youzan.customer.adapter;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 仅本地开发与集成测试可用的有赞客户 Mock 适配器（严格 @Profile("local")，不含任何真实个人信息）。
 */
@Component
@Profile("local")
public class MockYouzanCustomerPullClient implements YouzanCustomerPullClient {

    public static final String ADAPTER_NAME = "MOCK_DEV_ONLY";
    public static final String MOCK_SHOP_ID = "MOCK-SHOP-001";
    private static final Instant BASE_CREATED_AT = Instant.parse("2026-06-01T02:00:00Z");

    private static final List<String[]> MOCK_REGIONS = List.of(
            new String[]{"浙江省", "杭州市", "西湖区"},
            new String[]{"上海市", "上海市", "浦东新区"},
            new String[]{"北京市", "北京市", "朝阳区"},
            new String[]{"广东省", "深圳市", "南山区"}
    );

    private final CopyOnWriteArrayList<YouzanPulledCustomer> scrmFixtures = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<YouzanPulledCustomer> orderBuyerFixtures = new CopyOnWriteArrayList<>();

    public MockYouzanCustomerPullClient() {
        resetFixtures();
    }

    public final void resetFixtures() {
        scrmFixtures.clear();
        orderBuyerFixtures.clear();
        List<YouzanPulledCustomer> defaultScrm = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            String index = String.format("%03d", i);
            String[] region = MOCK_REGIONS.get((i - 1) % MOCK_REGIONS.size());
            List<String> tags = i % 2 == 0
                    ? List.of("贵宾客户", "有赞老客")
                    : List.of("有赞老客");
            defaultScrm.add(new YouzanPulledCustomer(
                    "YZ-MOCK-CUST-" + index,
                    "有赞演示客户" + index,
                    "星选昵称" + index,
                    String.format("1399900%04d", i),
                    i % 2 == 0 ? "FEMALE" : "MALE",
                    LocalDate.of(1990 + (i % 10), ((i - 1) % 12) + 1, ((i - 1) % 27) + 1),
                    BASE_CREATED_AT.plus(i, ChronoUnit.DAYS),
                    1,
                    10000L + i,
                    "有赞演示同步样本 #" + index,
                    region[0],
                    region[1],
                    region[2],
                    tags
            ));
        }
        scrmFixtures.addAll(defaultScrm);

        // 订单买家样本包含复购订单，验证按订单拉取时的去重与目标人数计数
        List<YouzanPulledCustomer> defaultOrders = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            String index = String.format("%03d", i);
            String[] region = MOCK_REGIONS.get((i - 1) % MOCK_REGIONS.size());
            YouzanPulledCustomer buyer = new YouzanPulledCustomer(
                    "YZ-MOCK-ORDER-BUYER-" + index,
                    "有赞订单买家" + index,
                    "订单昵称" + index,
                    String.format("1399800%04d", i),
                    "FEMALE",
                    null,
                    BASE_CREATED_AT.plus(i, ChronoUnit.DAYS),
                    1,
                    20000L + i,
                    null,
                    region[0],
                    region[1],
                    region[2],
                    List.of()
            );
            defaultOrders.add(buyer);
            if (i <= 3) {
                // 模拟前 3 位买家各有复购订单
                defaultOrders.add(buyer);
            }
        }
        orderBuyerFixtures.addAll(defaultOrders);
    }

    public void setScrmFixtures(List<YouzanPulledCustomer> items) {
        scrmFixtures.clear();
        if (items != null) {
            scrmFixtures.addAll(items);
        }
    }

    public void setOrderBuyerFixtures(List<YouzanPulledCustomer> items) {
        orderBuyerFixtures.clear();
        if (items != null) {
            orderBuyerFixtures.addAll(items);
        }
    }

    @Override
    public String name() {
        return ADAPTER_NAME;
    }

    @Override
    public boolean officialEnabled() {
        return false;
    }

    @Override
    public YouzanCustomerPage pullScrmPage(long tenantId, String shopId, Instant createdStart, Instant createdEnd,
                                           int pageNo, int pageSize) {
        if (!MOCK_SHOP_ID.equals(shopId)) {
            return new YouzanCustomerPage(List.of(), 0L, false, List.of());
        }
        return slice(scrmFixtures, createdStart, createdEnd, pageNo, pageSize);
    }

    @Override
    public YouzanCustomerPage pullOrderBuyersPage(long tenantId, String shopId, Instant windowStart, Instant windowEnd,
                                                  int pageNo, int pageSize) {
        if (!MOCK_SHOP_ID.equals(shopId)) {
            return new YouzanCustomerPage(List.of(), 0L, false, List.of());
        }
        return slice(orderBuyerFixtures, windowStart, windowEnd, pageNo, pageSize);
    }

    @Override
    public YouzanCustomerEnrichment enrichCustomer(long tenantId, String shopId, String yzOpenId,
                                                   Integer fansType, Long fansId, String plainMobile) {
        if (yzOpenId != null && yzOpenId.startsWith("YZ-MOCK-ORDER-BUYER-")) {
            return new YouzanCustomerEnrichment(
                    null,
                    null,
                    plainMobile,
                    "FEMALE",
                    LocalDate.of(1993, 6, 15),
                    "有赞订单买家补全备注（" + yzOpenId + "）",
                    "浙江省",
                    "杭州市",
                    "西湖区",
                    List.of("贵宾客户", "有赞复购"),
                    null);
        }
        return new YouzanCustomerEnrichment(
                null, null, plainMobile, "UNKNOWN", null, null, null, null, null, List.of(), null);
    }

    private YouzanCustomerPage slice(List<YouzanPulledCustomer> source, Instant start, Instant end,
                                     int pageNo, int pageSize) {
        List<YouzanPulledCustomer> filtered = source.stream()
                .filter(item -> start == null || item.createdAt() == null || !item.createdAt().isBefore(start))
                .filter(item -> end == null || item.createdAt() == null || !item.createdAt().isAfter(end))
                .toList();
        int safePage = Math.max(1, pageNo);
        int safeSize = Math.max(1, pageSize);
        int from = (safePage - 1) * safeSize;
        if (from >= filtered.size()) {
            return new YouzanCustomerPage(List.of(), filtered.size(), false, List.of());
        }
        int to = Math.min(from + safeSize, filtered.size());
        boolean hasMore = to < filtered.size();
        return new YouzanCustomerPage(filtered.subList(from, to), filtered.size(), hasMore, List.of());
    }
}
