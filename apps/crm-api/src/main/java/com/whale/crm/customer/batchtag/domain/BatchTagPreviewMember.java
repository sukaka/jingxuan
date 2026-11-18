package com.whale.crm.customer.batchtag.domain;

public record BatchTagPreviewMember(Long id, long tenantId, String previewId, long customerId,
                                    String customerNo, String customerName, long ownerStaffId, Long teamId,
                                    String tagCodesJson) {
}
