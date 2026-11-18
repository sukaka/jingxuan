package com.whale.crm.customer.batchtag.api.dto;

public record BatchTagMemberResponse(long customerId, String customerNo, String customerName,
                                     long ownerStaffId, Long teamId) {
}
