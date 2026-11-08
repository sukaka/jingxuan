package com.whale.crm.reporting.api.dto;

import java.math.BigDecimal;

public record PendingRatio(BigDecimal value, String status, String questionCode, String description) {
    public static PendingRatio q13(String description) {
        return new PendingRatio(null, "PENDING_DEFINITION", "Q13", description);
    }
}
