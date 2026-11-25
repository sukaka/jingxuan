package com.whale.crm.exporting.domain;

public record ExportDataQuery(long tenantId, ExportType exportType, ExportScope scope,
                              ExportRestriction restriction, ExportFilter filter, long afterId, int size) {
}
