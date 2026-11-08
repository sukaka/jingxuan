package com.whale.crm.file.domain;

public enum FileClassification {
    CONTRACT,
    QUALIFICATION,
    CHANGE_EVIDENCE,
    PAYMENT,
    REDEMPTION,
    MATERIAL,
    MERCHANT_RESOURCE,
    EXPORT;

    public String directory() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean pdfOnly() {
        return this == CONTRACT || this == QUALIFICATION || this == CHANGE_EVIDENCE;
    }
}
