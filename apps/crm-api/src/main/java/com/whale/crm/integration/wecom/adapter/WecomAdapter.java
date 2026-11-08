package com.whale.crm.integration.wecom.adapter;

import java.util.List;

public interface WecomAdapter {
    String name();

    List<WecomContactInput> pullContacts(String corpId);

    WecomArchivePage pullArchive(String corpId, long afterSeq, int limit);
}
