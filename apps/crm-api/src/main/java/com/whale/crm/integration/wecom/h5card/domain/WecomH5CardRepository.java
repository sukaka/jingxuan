package com.whale.crm.integration.wecom.h5card.domain;

import java.util.List;
import java.util.Optional;

public interface WecomH5CardRepository {
    void insert(WecomH5Card card);

    Optional<WecomH5Card> findById(long tenantId, long employeeId, long id);

    Optional<WecomH5Card> findByIdForUpdate(long tenantId, long employeeId, long id);

    Optional<WecomH5Card> findByShareKey(String shareKey);

    List<WecomH5Card> findForCustomer(long tenantId, long employeeId, long customerId, int size);

    int disable(long tenantId, long employeeId, long id, long version, String reason, long disabledBy,
                java.time.Instant disabledAt);

    void insertUsage(WecomH5CardUsage usage);

    Optional<WecomH5CardUsage> findUsageByKey(long tenantId, String key);
}
