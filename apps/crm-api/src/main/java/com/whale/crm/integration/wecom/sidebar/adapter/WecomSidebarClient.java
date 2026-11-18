package com.whale.crm.integration.wecom.sidebar.adapter;

public interface WecomSidebarClient {
    String oauthUserid(String code);

    WecomTicket ticket(boolean agentConfig);

    record WecomTicket(String ticket, long expiresIn) {
    }
}
