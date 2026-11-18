package com.whale.crm.quickreply.application;

public class FactQuickReplyVariableResolver implements QuickReplyVariableResolver {
    private final String code;

    public FactQuickReplyVariableResolver(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Object resolve(QuickReplyContext context) {
        return context.facts().get(code);
    }
}
