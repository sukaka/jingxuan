package com.whale.crm.risk.api.dto;

import java.time.Instant;

public record RiskSyncResponse(int createdCount, int updatedCount, Instant syncedAt) {
}
