package com.whale.crm.integration.wecom.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WecomSyncPolicyRepository {
    void insert(WecomSyncPolicy policy);
    Optional<WecomSyncPolicy> find(long tenantId, String corpId);
    Optional<WecomSyncPolicy> findForUpdate(long tenantId, String corpId);
    int update(WecomSyncPolicy policy);
    List<WecomSyncPolicy> findDue(Instant now, int limit);
    int claim(long tenantId, long id, long version, Instant scheduledAt, Instant nextRunAt);
    int finish(long tenantId, long id, long version, String status, String error);
}
