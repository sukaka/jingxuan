package com.whale.crm.integration.wecom.media.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WecomMediaRepository {
    void insert(WecomMediaAsset asset);

    Optional<WecomMediaAsset> findById(long tenantId, long id);

    Optional<WecomMediaAsset> findByIdForUpdate(long tenantId, long id);

    List<WecomMediaAsset> findByMessage(long tenantId, long messageId);

    List<Long> findPendingTenantIds(Instant now);

    List<WecomMediaAsset> claimable(long tenantId, Instant now, int size);

    int claim(long tenantId, long id, long version, String owner, Instant now, Instant leaseUntil);

    int markStored(long tenantId, long id, long version, String owner, String objectKey, String sha256,
                   long actualSize, Instant now);

    int markFailure(long tenantId, long id, long version, String owner, String status, String error,
                    Instant nextRetryAt, Instant now);
}
