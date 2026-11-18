package com.whale.crm.quickreply.api.dto;

import java.util.List;
import java.util.Map;

public record QuickReplyPreviewResponse(long templateId, String content, Map<String, Object> variables,
                                        List<String> missingVariables, boolean canSend, boolean allowEdit) {
    public QuickReplyPreviewResponse {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        missingVariables = missingVariables == null ? List.of() : List.copyOf(missingVariables);
    }
}
