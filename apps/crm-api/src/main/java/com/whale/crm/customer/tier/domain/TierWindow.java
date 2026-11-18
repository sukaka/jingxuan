package com.whale.crm.customer.tier.domain;

import java.time.Instant;
import java.time.ZoneId;

public record TierWindow(Instant startInclusive, Instant endExclusive, ZoneId zoneId) {
}
