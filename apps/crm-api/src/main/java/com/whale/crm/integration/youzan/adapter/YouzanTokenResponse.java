package com.whale.crm.integration.youzan.adapter;

import java.time.Instant;

public record YouzanTokenResponse(String accessToken, String refreshToken, Instant expiresAt) {
}
