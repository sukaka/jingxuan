package com.whale.crm.integration.youzan.api.dto;

import java.time.Instant;

public record YouzanReconcileReport(String shopId, long totalEvents, long internalOrders,
                                    long openIssues, Instant lastSyncAt, long todayEvents, long campaignOrders) {
}
