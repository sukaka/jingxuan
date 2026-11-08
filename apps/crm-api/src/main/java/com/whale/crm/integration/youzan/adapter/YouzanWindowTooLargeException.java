package com.whale.crm.integration.youzan.adapter;

public class YouzanWindowTooLargeException extends RuntimeException {
    public YouzanWindowTooLargeException() {
        super("有赞订单数量超过单窗口100页上限，必须缩小时间窗口");
    }
}
