package com.whale.crm.exporting.domain;

public enum ExportStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
