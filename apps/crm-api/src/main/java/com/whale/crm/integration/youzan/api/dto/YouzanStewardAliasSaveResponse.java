package com.whale.crm.integration.youzan.api.dto;

public record YouzanStewardAliasSaveResponse(
        YouzanStewardAliasView alias,
        int backfilledOrderCount) {
}
