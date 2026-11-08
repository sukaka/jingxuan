package com.whale.crm.fulfillment.api.dto;

import com.whale.crm.fulfillment.domain.Appointment;
import com.whale.crm.fulfillment.domain.AppointmentHistory;

import java.util.List;

public record AppointmentDetail(Appointment appointment, List<AppointmentItemView> items,
                                List<AppointmentHistory> history, List<RedemptionDetail> redemptions) {
    public AppointmentDetail {
        items = items == null ? null : List.copyOf(items);
        history = history == null ? null : List.copyOf(history);
        redemptions = redemptions == null ? null : List.copyOf(redemptions);
    }
}
