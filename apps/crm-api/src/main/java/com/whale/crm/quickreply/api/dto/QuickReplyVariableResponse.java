package com.whale.crm.quickreply.api.dto;

import com.whale.crm.quickreply.domain.QuickReplyVariable;

public record QuickReplyVariableResponse(String variableCode, String variableName, String dataSource,
                                         String dataType, boolean required, String description) {
    public static QuickReplyVariableResponse from(QuickReplyVariable variable) {
        return new QuickReplyVariableResponse(variable.variableCode(), variable.variableName(), variable.dataSource(),
                variable.dataType(), variable.required(), variable.description());
    }
}
