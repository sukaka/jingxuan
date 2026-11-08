package com.whale.crm.integration.youzan.api.dto;

import java.time.Instant;

public record YouzanShopConfigView(Long id, String shopId, String shopName, String clientId,
                                   boolean secretConfigured, Instant tokenExpiresAt, String connectionStatus,
                                   boolean syncEnabled, String lastError, Instant lastConnectedAt,
                                   String webhookPath, Long version) {
}
