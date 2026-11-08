package com.whale.crm.trading.domain;

import java.util.List;

public record OrderDetail(Order order, List<OrderItem> items, List<PaymentTxn> payments,
                          List<OrderStatusHistory> history) {
    public OrderDetail {
        items = items == null ? null : List.copyOf(items);
        payments = payments == null ? null : List.copyOf(payments);
        history = history == null ? null : List.copyOf(history);
    }
}
