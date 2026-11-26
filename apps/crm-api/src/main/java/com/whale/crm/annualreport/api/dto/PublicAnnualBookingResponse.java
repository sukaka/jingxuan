package com.whale.crm.annualreport.api.dto;

/**
 * C 端预约意向提交回执（绝不返回内部任务 ID 或员工 ID，且明确提示正式核销以到院确认为准）。
 */
public record PublicAnnualBookingResponse(
        boolean submitted,
        boolean alreadyRequested,
        String butlerName,
        String message
) {
}
