package com.whale.crm.fulfillment.api.dto;

public record AppointmentItemView(Long id, Long entitlementId, Long projectId,
                                  Integer plannedCount, Integer postedCount) {
}
