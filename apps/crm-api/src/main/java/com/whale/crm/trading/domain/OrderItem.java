package com.whale.crm.trading.domain;

public record OrderItem(Long id, Long orderId, Long skuId, Long packageVersionId, String skuCodeSnapshot,
                        String skuNameSnapshot, Integer purchaseQuantity, Long listAmountFen,
                        Long paidAllocatedFen, String entitlementSnapshot, Long version) {
}
