package com.whale.crm.reporting.domain;

public record ReportQuery(long tenantId, ReportWindow window, ReportScope scope,
                          ReportCriteria criteria, int offset, int size) {
}
