package com.whale.crm.customer.domain;

import java.util.List;

public interface CustomerAttentionProjectRepository {
    List<Long> findProjectIds(long tenantId, long customerId);

    void deleteByCustomerId(long tenantId, long customerId);

    void insert(long tenantId, long customerId, long projectId, Long actorUserId);
}
