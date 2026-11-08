package com.whale.crm.exporting.domain;

public record StoredExportScope(ExportScope requested, ExportScope executed, ExportRestriction restriction) {
    public StoredExportScope withExecuted(ExportScope scope) {
        return new StoredExportScope(requested, scope, restriction);
    }
}
