package com.whale.crm.customer.batchtag.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PreviewBatchTagJobRequest(
        @NotNull @Pattern(regexp = "ADD|REMOVE") String operation,
        @NotEmpty @Size(max = 20) List<@Pattern(regexp = "\\S{1,64}") String> tagCodes,
        @NotNull @Valid BatchTagSourceRequest source,
        @Min(1) Integer page,
        @Min(1) @Max(100) Integer size) {
    public PreviewBatchTagJobRequest {
        tagCodes = tagCodes == null ? null : List.copyOf(tagCodes);
        if (page == null) {
            page = 1;
        }
        if (size == null) {
            size = 20;
        }
    }
}
