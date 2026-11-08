package com.whale.crm.settlement.domain;

import java.time.Instant;

public record SettlementRedemption(Long id, Long institutionId, String status, Instant serviceAt) {
}
