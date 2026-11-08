package com.whale.crm.refund.api.dto;

import com.whale.crm.refund.domain.Refund;
import com.whale.crm.refund.domain.RefundItem;
import com.whale.crm.refund.domain.RefundPayment;
import com.whale.crm.refund.domain.RefundStatusHistory;

import java.util.List;

public record RefundDetail(Refund refund, List<RefundItem> items, List<RefundPayment> payments,
                           List<RefundStatusHistory> history) {
    public RefundDetail {
        items = items == null ? null : List.copyOf(items);
        payments = payments == null ? null : List.copyOf(payments);
        history = history == null ? null : List.copyOf(history);
    }
}
