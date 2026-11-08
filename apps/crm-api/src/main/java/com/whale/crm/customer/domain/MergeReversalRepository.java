package com.whale.crm.customer.domain;

import java.time.Instant;
import java.util.Optional;

public interface MergeReversalRepository {
    void insert(long tenantId, MergeReversal reversal);
    Optional<MergeReversal> findById(long tenantId, long id);
    Optional<MergeReversal> findForUpdate(long tenantId, long id);
    Optional<MergeReversal> findByMergeCaseId(long tenantId, long mergeCaseId);
    int updateStatus(long tenantId, long id, long version, String status, String reviewReason,
                     long reviewedBy, Instant reviewedAt, Instant executedAt,
                     String warningMessage, String errorMasked);
}
