package com.whale.crm.risk.domain;

public record RiskSourceCustomerLink(
        Long sourceId,
        Long orderId,
        String orderNo,
        Long customerId,
        String customerNo,
        String customerName) {
}
