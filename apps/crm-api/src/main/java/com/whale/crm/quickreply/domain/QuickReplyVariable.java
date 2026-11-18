package com.whale.crm.quickreply.domain;

public record QuickReplyVariable(long id, String variableCode, String variableName, String dataSource,
                                 String resolverType, String resolverKey, String dataType, boolean required,
                                 String description, String status) {
}
