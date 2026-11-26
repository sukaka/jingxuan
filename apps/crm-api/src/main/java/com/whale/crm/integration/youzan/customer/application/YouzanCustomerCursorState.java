package com.whale.crm.integration.youzan.customer.application;

import java.time.Instant;

/**
 * 有赞客户拉取断点游标状态：配合固定底层分页大小持久化，保证跨批次切换条数不重不漏。
 */
public record YouzanCustomerCursorState(
        Instant anchorStart,
        Instant anchorEnd,
        Instant windowStart,
        Instant windowEnd,
        int pageNo,
        int itemOffset
) {
}
