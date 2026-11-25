package com.whale.crm.customer.tier.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CustomerTierRepository {
    List<CustomerTierRule> findRules(long tenantId);

    Optional<CustomerTierRule> findRule(long tenantId, long ruleId);

    Optional<CustomerTierRule> findRuleForUpdate(long tenantId, long ruleId);

    Optional<CustomerTierRule> findActiveRule(long tenantId);

    Optional<CustomerTierRule> findActiveRuleForUpdate(long tenantId);

    List<CustomerTierRule> findAllActiveRules();

    int nextVersionNo(long tenantId, String ruleCode);

    CustomerTierRule insertRule(CustomerTierRule rule);

    int updateDraftRule(CustomerTierRule rule);

    int activateRule(long tenantId, long ruleId, long version, Instant activatedAt);

    int retireRule(long tenantId, long ruleId, long version, Instant retiredAt);

    void deleteLevels(long tenantId, long ruleId);

    void insertLevel(CustomerTierLevel level);

    List<CustomerTierLevel> findLevels(long tenantId, long ruleId);

    CustomerTierMetrics loadMetrics(long tenantId, long customerId, Instant startInclusive,
                                    Instant endExclusive, String archiveScope);

    CustomerTierMetricSnapshot insertSnapshot(CustomerTierMetricSnapshot snapshot);

    Optional<CustomerTierMetricSnapshot> findSnapshotByKey(long tenantId, String recalculationKey);

    Optional<CustomerTierMetricSnapshot> findSnapshot(long tenantId, long snapshotId);

    Optional<CustomerTierMetricSnapshot> findLatestSnapshot(long tenantId, long customerId);

    List<CustomerTierMetricSnapshot> findSnapshots(long tenantId, long customerId, int limit);

    Optional<CustomerTierDowngradeReview> findReview(long tenantId, long reviewId);

    Optional<CustomerTierDowngradeReview> findReviewForUpdate(long tenantId, long reviewId);

    Optional<CustomerTierDowngradeReview> findPendingForUpdate(long tenantId, long customerId);

    Optional<CustomerTierDowngradeReview> findPending(long tenantId, long customerId);

    CustomerTierDowngradeReview insertReview(CustomerTierDowngradeReview review);

    int updateReviewStatus(long tenantId, long reviewId, long version, String fromStatus, String toStatus,
                           Long reviewerId, String reason, Instant reviewedAt, Instant effectiveAt);

    int markPendingStale(long tenantId, long customerId, String reason, Instant changedAt);

    int markAllPendingStale(long tenantId, String reason, Instant changedAt);

    List<CustomerTierDowngradeReview> searchReviews(long tenantId, String scopeType, List<Long> teamIds,
                                                    long selfId, String status, int offset, int size);

    long countReviews(long tenantId, String scopeType, List<Long> teamIds, long selfId, String status);

    void insertHistory(CustomerTierHistory history);

    List<CustomerTierHistory> findHistory(long tenantId, long customerId, int limit);

    List<CustomerTierHistory> findTimelineHistory(long tenantId, long customerId, String cursorMode,
                                                  Instant cursorOccurredAt, Long cursorSourceId, int limit);

    int updateTier(long tenantId, long customerId, long expectedVersion, String tierCode,
                   boolean locked, String lockReason);

    List<Long> findCustomerIdsAfter(long tenantId, long afterId, int limit);

    List<Long> findScopedCustomerIds(long tenantId, String scopeType, List<Long> teamIds,
                                     long selfId, int limit);
}
