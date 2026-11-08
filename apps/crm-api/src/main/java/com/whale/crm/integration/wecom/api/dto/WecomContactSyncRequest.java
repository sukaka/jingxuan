package com.whale.crm.integration.wecom.api.dto;

import jakarta.validation.constraints.Pattern;

public record WecomContactSyncRequest(@Pattern(regexp = "FULL|RECONCILE") String type) {
}
