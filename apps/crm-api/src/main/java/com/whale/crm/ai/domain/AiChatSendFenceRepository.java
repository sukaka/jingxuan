package com.whale.crm.ai.domain;

import java.time.Instant;
import java.util.Optional;

public interface AiChatSendFenceRepository {
    Optional<AiChatSendFence> findForUpdate(long tenantId, long runId);

    void insert(AiChatSendFence fence);

    int markStarted(long tenantId, long runId, long expectedVersion, Instant now);

    int blockTenant(long tenantId, Instant now);

    int blockCustomer(long tenantId, long customerId, Instant now);

    long countInFlightForTenant(long tenantId);

    long countInFlightForCustomer(long tenantId, long customerId);

    int finish(long tenantId, long runId, long expectedVersion, Instant now);

    int recover(long tenantId, long runId, Instant now);
}
