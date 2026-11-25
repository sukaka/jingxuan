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

    List<RiskCustomerLink> findResolvedCustomerLinks(long tenantId, List<Long> riskCaseIds);

    List<RiskSourceCustomerLink> findDelayedOrderCustomerLinks(long tenantId, List<Long> sourceIds);

    List<RiskSourceCustomerLink> findDelayedYouzanIssueCustomerLinks(long tenantId, List<Long> sourceIds);

    List<RiskSourceCustomerLink> findDelayedRefundCustomerLinks(long tenantId, List<Long> sourceIds);

    List<RiskSourceCustomerLink> findDelayedEntitlementCustomerLinks(long tenantId, List<Long> sourceIds);

    List<RiskSourceCustomerLink> findDelayedRedemptionCustomerLinks(long tenantId, List<Long> sourceIds);

    Optional<RiskResolvedIds> resolveOrderLink(long tenantId, long sourceId);

    Optional<RiskResolvedIds> resolveYouzanIssueLink(long tenantId, long sourceId);

    Optional<RiskResolvedIds> resolveRefundLink(long tenantId, long sourceId);

    Optional<RiskResolvedIds> resolveEntitlementLink(long tenantId, long sourceId);

    Optional<RiskResolvedIds> resolveRedemptionLink(long tenantId, long sourceId);

    void insert(RiskCase riskCase);

    int backfillLinkIfAbsent(long tenantId, long id, Long customerId, Long orderId);

    int linkCustomerToYouzanIssues(long tenantId, List<String> sourceRefs, long customerId);

    int linkOrderToYouzanIssues(long tenantId, List<String> sourceRefs, long orderId, long customerId);

    List<Long> findIdsByCustomerId(long tenantId, long customerId);

    int reassignSpecific(long tenantId, List<Long> ids, long fromCustomerId, long toCustomerId);

    int reassignSpecificByOrderIds(long tenantId, List<Long> orderIds, long fromCustomerId, long toCustomerId);

    int updateStatus(long tenantId, long id, long version, String fromStatus, String toStatus,
                     Long ownerUserId, String resolution, Instant resolvedAt);

    void insertHistory(RiskCaseHistory history);

    List<RiskCaseHistory> findHistory(long tenantId, long riskCaseId);
}
