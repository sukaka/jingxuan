package com.whale.crm.fulfillment.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record PostRedemptionRequest(@NotNull @Min(0) Long appointmentVersion,
                                    @NotNull Instant serviceAt,
                                    @NotBlank @Size(max = 192) String evidenceRef,
                                    Long voucherFileId,
                                    @NotEmpty List<@Valid RedemptionLineRequest> lines) {
    public PostRedemptionRequest {
        lines = lines == null ? null : List.copyOf(lines);
    }
}
