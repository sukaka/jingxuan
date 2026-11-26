package com.whale.crm.integration.youzan.customer.adapter;

/**
 * 标记有赞当前查询时间窗命中平台最大翻页上限，需按时间窗二分切分后再拉取。
 */
public class YouzanCustomerWindowTooLargeException extends RuntimeException {

    private final long totalResults;

    public YouzanCustomerWindowTooLargeException(long totalResults) {
        super("有赞当前时间窗客户数超过单窗翻页上限: " + totalResults);
        this.totalResults = totalResults;
    }

    public long totalResults() {
        return totalResults;
    }
}
