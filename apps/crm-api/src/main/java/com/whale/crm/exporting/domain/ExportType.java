package com.whale.crm.exporting.domain;

public enum ExportType {
    CUSTOMER("customer:read"),
    ORDER("order:read"),
    REDEMPTION("redemption:read"),
    FOLLOWUP("followup:read");

    private final String readPermission;

    ExportType(String readPermission) {
        this.readPermission = readPermission;
    }

    public String readPermission() {
        return readPermission;
    }
}
