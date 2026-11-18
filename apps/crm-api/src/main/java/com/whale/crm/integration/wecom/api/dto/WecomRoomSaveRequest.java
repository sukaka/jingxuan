package com.whale.crm.integration.wecom.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record WecomRoomSaveRequest(
        @NotBlank @Size(max = 96) String corpId,
        @Size(max = 128) String displayName,
        @NotNull Boolean enabled,
        @NotEmpty @Size(max = 500) List<@NotBlank @Size(max = 192) String> memberUserids,
        @NotBlank @Size(max = 255) String sourceEvidenceRef,
        @NotNull Long version,
        @NotBlank @Size(max = 1000) String reason) {
    public WecomRoomSaveRequest {
        memberUserids = memberUserids == null ? null : List.copyOf(memberUserids);
    }
}
