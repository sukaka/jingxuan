package com.whale.crm.customer.domain;

import java.util.List;

public interface MergeResourceSnapshotRepository {
    void insert(long tenantId, MergeResourceSnapshot snapshot);
    List<MergeResourceSnapshot> findByMergeCaseId(long tenantId, long mergeCaseId);
}
