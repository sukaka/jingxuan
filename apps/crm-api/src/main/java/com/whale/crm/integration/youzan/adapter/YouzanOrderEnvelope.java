package com.whale.crm.integration.youzan.adapter;

public record YouzanOrderEnvelope(YouzanOrderEvent event, String providerPayload) {
}
