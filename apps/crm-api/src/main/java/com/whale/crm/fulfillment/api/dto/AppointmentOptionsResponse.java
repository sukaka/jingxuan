package com.whale.crm.fulfillment.api.dto;

import java.time.LocalDate;
import java.util.List;

public record AppointmentOptionsResponse(Long customerId, List<InstitutionOption> institutions,
                                         List<EntitlementOption> entitlements) {
    public AppointmentOptionsResponse {
        institutions = institutions == null ? null : List.copyOf(institutions);
        entitlements = entitlements == null ? null : List.copyOf(entitlements);
    }

    public record InstitutionOption(Long id, String fullName, String shortName) {
    }

    public record EntitlementOption(Long id, Long orderId, Long projectId, String projectName,
                                    Integer availableCount, LocalDate validFrom, LocalDate validTo,
                                    List<Long> institutionIds, String allocationStatus) {
        public EntitlementOption {
            institutionIds = institutionIds == null ? null : List.copyOf(institutionIds);
        }
    }
}
