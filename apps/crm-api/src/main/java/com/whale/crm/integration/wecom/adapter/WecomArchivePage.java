package com.whale.crm.integration.wecom.adapter;

import java.util.List;

public record WecomArchivePage(List<WecomArchiveMessage> messages, long nextSeq, boolean hasMore) {
    public WecomArchivePage {
        messages = messages == null ? null : List.copyOf(messages);
    }
}
