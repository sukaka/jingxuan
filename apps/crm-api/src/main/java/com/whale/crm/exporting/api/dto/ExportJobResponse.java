package com.whale.crm.exporting.api.dto;

import com.whale.crm.exporting.domain.ExportFilter;
import com.whale.crm.exporting.domain.ExportStatus;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.exporting.domain.StoredExportScope;

import java.time.Instant;
import java.util.List;

public record ExportJobResponse(Long id, String jobNo, ExportType exportType, ExportStatus status,
                                List<String> columns, ExportFilter filters, StoredExportScope scope,
                                String reason, Long rowCount, Long fileId, Instant expiresAt,
                                String lastError, Instant startedAt, Instant finishedAt,
                                Instant createdAt, Instant updatedAt, Long version) {
    public ExportJobResponse {
        columns = columns == null ? null : List.copyOf(columns);
    }
}
