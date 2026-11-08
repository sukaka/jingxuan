package com.whale.crm.platform.auth.api.dto;

public record CaptchaResponse(String captchaId, String imageBase64, long expiresIn) {
}
