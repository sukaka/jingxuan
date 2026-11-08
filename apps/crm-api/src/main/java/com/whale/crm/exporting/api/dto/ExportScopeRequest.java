package com.whale.crm.exporting.api.dto;

import jakarta.validation.constraints.Positive;

import java.util.List;

public record ExportScopeRequest(List<@Positive Long> teamIds, @Positive Long ownerId,
                                 List<@Positive Long> institutionIds) {
    public ExportScopeRequest {
        teamIds = teamIds == null ? null : List.copyOf(teamIds);
        institutionIds = institutionIds == null ? null : List.copyOf(institutionIds);
    }
}
