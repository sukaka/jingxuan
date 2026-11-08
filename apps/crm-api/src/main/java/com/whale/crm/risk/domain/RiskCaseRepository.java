package com.whale.crm.risk.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RiskCaseRepository {
    Optional<RiskCase> findById(long tenantId, long id);

    Optional<RiskCase> findByIdForUpdate(long tenantId, long id);

    Optional<RiskCase> findBySource(long tenantId, String sourceType, String sourceRef, String riskType);

    Optional<RiskCase> findBySourceForUpdate(long tenantId, String sourceType, String sourceRef, String riskType);

    List<RiskCase> search(RiskCaseQuery query);

    long countSearch(RiskCaseQuery query);

    List<RiskCustomerLink> findCustomerLinks(long tenantId, List<Long> riskCaseIds);

    void insert(RiskCase riskCase);

    int updateStatus(long tenantId, long id, long version, String fromStatus, String toStatus,
                     Long ownerUserId, String resolution, Instant resolvedAt);

    void insertHistory(RiskCaseHistory history);

    List<RiskCaseHistory> findHistory(long tenantId, long riskCaseId);
}
