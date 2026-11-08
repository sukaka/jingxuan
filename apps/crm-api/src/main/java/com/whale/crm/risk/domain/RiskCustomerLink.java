package com.whale.crm.risk.domain;

public record RiskCustomerLink(
        Long riskCaseId,
        Long orderId,
        String orderNo,
        Long customerId,
        String customerNo,
        String customerName) {
}
