package com.whale.crm.quickreply.application;

public interface QuickReplyVariableResolver {
    String code();

    Object resolve(QuickReplyContext context);
}
