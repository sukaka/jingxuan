package com.whale.crm.exporting.api.dto;

import com.whale.crm.exporting.domain.ExportType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateExportRequest(
        @NotNull ExportType exportType,
        @Size(max = 40) List<@NotBlank @Size(max = 64) String> columns,
        @NotBlank @Size(min = 2, max = 500) String reason,
        @Valid ExportFilterRequest filters,
        @Valid ExportScopeRequest scope) {
    public CreateExportRequest {
        columns = columns == null ? null : List.copyOf(columns);
    }
}
