package com.whale.crm.integration.config.api.dto;

public record IntegrationRuntimeConfigResponse(boolean youzanRealEnabled, boolean wecomArchiveRealEnabled,
                                               boolean wecomOfficialEnabled, long version, String source) {
}
