package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanShopConfig(Long id, Long tenantId, String shopId, String shopName, String clientId,
                               String clientSecretCipher, String accessTokenCipher, String refreshTokenCipher,
                               Instant tokenExpiresAt, String connectionStatus, Boolean syncEnabled,
                               String lastError, Instant lastConnectedAt, Instant createdAt, Long version) {
}
