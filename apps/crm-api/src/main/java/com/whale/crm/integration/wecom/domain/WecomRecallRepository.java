package com.whale.crm.integration.wecom.domain;

import java.time.Instant;
import java.util.Optional;

public interface WecomRecallRepository {
    void insert(WecomRecallEvent event);

    Optional<WecomRecallEvent> findByRecallMessageId(long tenantId, String corpId, String recallMessageId);

    Optional<WecomRecallEvent> findPendingByOriginalMessageId(long tenantId, String corpId, String originalMessageId);

    int markApplied(long tenantId, long id, long version, long originalMessageRef, Instant appliedAt);
}
