package com.whale.crm.reporting.api.dto;

/** 值班待办计数。null 表示当前用户无对应读取权限，不表示数量为零。 */
public record OperationsDutyResponse(Long newCustomerCount, Long pendingTaskCount, Long dueFollowupCount,
                                     Long overdueTaskCount, Long pendingAppointmentCount,
                                     Long awaitingRedemptionCount, Long pendingRefundCount) {
}
