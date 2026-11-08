package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.integration.wecom.domain.WecomContactProfile;

import java.util.List;

public interface WecomContactClient {
    String name();
    String corpId();
    WecomContactBatchPage batchGetByUser(List<String> wecomUserids, String cursor, int limit);
    List<WecomContactProfile> getContact(String externalUserid);

    record WecomContactBatchPage(List<WecomContactProfile> contacts, String nextCursor) {
        public WecomContactBatchPage {
            contacts = contacts == null ? null : List.copyOf(contacts);
        }
    }
}
