package com.whale.crm.integration.wecom.sidebar.api.dto;

public record WecomJsSdkSignatureResponse(String corpId, long agentId, String url,
                                          Signature config, Signature agentConfig) {
    public record Signature(String nonceStr, long timestamp, String signature) {
    }
}
