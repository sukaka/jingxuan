package com.whale.crm.customer.tier.infrastructure;

import com.whale.crm.customer.tier.domain.CustomerTierDowngradeReview;
import com.whale.crm.customer.tier.domain.CustomerTierHistory;
import com.whale.crm.customer.tier.domain.CustomerTierLevel;
import com.whale.crm.customer.tier.domain.CustomerTierMetricSnapshot;
import com.whale.crm.customer.tier.domain.CustomerTierMetrics;
import com.whale.crm.customer.tier.domain.CustomerTierRepository;
import com.whale.crm.customer.tier.domain.CustomerTierRule;
import com.whale.crm.customer.tier.infrastructure.mapper.CustomerTierMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisCustomerTierRepository implements CustomerTierRepository {
    private final CustomerTierMapper mapper;

    public MybatisCustomerTierRepository(CustomerTierMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<CustomerTierRule> findRules(long tenantId) {
        return mapper.selectRules(tenantId);
    }

    @Override
    public Optional<CustomerTierRule> findRule(long tenantId, long ruleId) {
        return Optional.ofNullable(mapper.selectRule(tenantId, ruleId));
    }

    @Override
    public Optional<CustomerTierRule> findRuleForUpdate(long tenantId, long ruleId) {
        return Optional.ofNullable(mapper.selectRuleForUpdate(tenantId, ruleId));
    }

    @Override
    public Optional<CustomerTierRule> findActiveRule(long tenantId) {
        return Optional.ofNullable(mapper.selectActiveRule(tenantId));
    }

    @Override
    public Optional<CustomerTierRule> findActiveRuleForUpdate(long tenantId) {
        return Optional.ofNullable(mapper.selectActiveRuleForUpdate(tenantId));
    }

    @Override
    public List<CustomerTierRule> findAllActiveRules() {
        return mapper.selectAllActiveRules();
    }

    @Override
    public int nextVersionNo(long tenantId, String ruleCode) {
        Integer current = mapper.selectMaxVersionNo(tenantId, ruleCode);
        return current == null ? 1 : current + 1;
    }

    @Override
    public CustomerTierRule insertRule(CustomerTierRule rule) {
        mapper.insertRule(rule);
        return Optional.ofNullable(mapper.selectRuleByVersion(rule.tenantId(), rule.ruleCode(), rule.versionNo()))
                .orElseThrow(() -> new IllegalStateException("分级规则写入后不可读取"));
    }

    @Override
    public int updateDraftRule(CustomerTierRule rule) {
        return mapper.updateDraftRule(rule);
    }

    @Override
    public int activateRule(long tenantId, long ruleId, long version, Instant activatedAt) {
        return mapper.activateRule(tenantId, ruleId, version, activatedAt);
    }

    @Override
    public int retireRule(long tenantId, long ruleId, long version, Instant retiredAt) {
        return mapper.retireRule(tenantId, ruleId, version, retiredAt);
    }

    @Override
    public void deleteLevels(long tenantId, long ruleId) {
        mapper.deleteLevels(tenantId, ruleId);
    }

    @Override
    public void insertLevel(CustomerTierLevel level) {
        mapper.insertLevel(level);
    }

    @Override
    public List<CustomerTierLevel> findLevels(long tenantId, long ruleId) {
        return mapper.selectLevels(tenantId, ruleId);
    }

    @Override
    public CustomerTierMetrics loadMetrics(long tenantId, long customerId, Instant startInclusive,
                                           Instant endExclusive, String archiveScope) {
        return mapper.selectMetrics(tenantId, customerId, startInclusive, endExclusive, archiveScope);
    }

    @Override
    public CustomerTierMetricSnapshot insertSnapshot(CustomerTierMetricSnapshot snapshot) {
        mapper.insertSnapshot(snapshot);
        return findSnapshotByKey(snapshot.tenantId(), snapshot.recalculationKey())
                .orElseThrow(() -> new IllegalStateException("指标快照写入后不可读取"));
    }

    @Override
    public Optional<CustomerTierMetricSnapshot> findSnapshotByKey(long tenantId, String recalculationKey) {
        return Optional.ofNullable(mapper.selectSnapshotByKey(tenantId, recalculationKey));
    }

    @Override
    public Optional<CustomerTierMetricSnapshot> findSnapshot(long tenantId, long snapshotId) {
        return Optional.ofNullable(mapper.selectSnapshot(tenantId, snapshotId));
    }

    @Override
    public Optional<CustomerTierMetricSnapshot> findLatestSnapshot(long tenantId, long customerId) {
        return Optional.ofNullable(mapper.selectLatestSnapshot(tenantId, customerId));
    }

    @Override
    public List<CustomerTierMetricSnapshot> findSnapshots(long tenantId, long customerId, int limit) {
        return mapper.selectSnapshots(tenantId, customerId, limit);
    }

    @Override
    public Optional<CustomerTierDowngradeReview> findReview(long tenantId, long reviewId) {
        return Optional.ofNullable(mapper.selectReview(tenantId, reviewId));
    }

    @Override
    public Optional<CustomerTierDowngradeReview> findReviewForUpdate(long tenantId, long reviewId) {
        return Optional.ofNullable(mapper.selectReviewForUpdate(tenantId, reviewId));
    }

    @Override
    public Optional<CustomerTierDowngradeReview> findPendingForUpdate(long tenantId, long customerId) {
        return Optional.ofNullable(mapper.selectPendingForUpdate(tenantId, customerId));
    }

    @Override
    public Optional<CustomerTierDowngradeReview> findPending(long tenantId, long customerId) {
        return Optional.ofNullable(mapper.selectPending(tenantId, customerId));
    }

    @Override
    public CustomerTierDowngradeReview insertReview(CustomerTierDowngradeReview review) {
        mapper.insertReview(review);
        return findPendingForUpdate(review.tenantId(), review.customerId())
                .orElseThrow(() -> new IllegalStateException("降级复核写入后不可读取"));
    }

    @Override
    public int updateReviewStatus(long tenantId, long reviewId, long version, String fromStatus, String toStatus,
                                  Long reviewerId, String reason, Instant reviewedAt, Instant effectiveAt) {
        return mapper.updateReviewStatus(tenantId, reviewId, version, fromStatus, toStatus, reviewerId,
                reason, reviewedAt, effectiveAt);
    }

    @Override
    public int markPendingStale(long tenantId, long customerId, String reason, Instant changedAt) {
        return mapper.markPendingStale(tenantId, customerId, reason, changedAt);
    }

    @Override
    public int markAllPendingStale(long tenantId, String reason, Instant changedAt) {
        return mapper.markAllPendingStale(tenantId, reason, changedAt);
    }

    @Override
    public List<CustomerTierDowngradeReview> searchReviews(long tenantId, String scopeType, List<Long> teamIds,
                                                           long selfId, String status, int offset, int size) {
        return mapper.searchReviews(tenantId, scopeType, teamIds, selfId, status, offset, size);
    }

    @Override
    public long countReviews(long tenantId, String scopeType, List<Long> teamIds, long selfId, String status) {
        return mapper.countReviews(tenantId, scopeType, teamIds, selfId, status);
    }

    @Override
    public void insertHistory(CustomerTierHistory history) {
        mapper.insertHistory(history);
    }

    @Override
    public List<CustomerTierHistory> findHistory(long tenantId, long customerId, int limit) {
        return mapper.selectHistory(tenantId, customerId, limit);
    }

    @Override
    public int updateTier(long tenantId, long customerId, long expectedVersion, String tierCode,
                          boolean locked, String lockReason) {
        return mapper.updateTier(tenantId, customerId, expectedVersion, tierCode, locked, lockReason);
    }

    @Override
    public List<Long> findCustomerIdsAfter(long tenantId, long afterId, int limit) {
        return mapper.selectCustomerIdsAfter(tenantId, afterId, limit);
    }

    @Override
    public List<Long> findScopedCustomerIds(long tenantId, String scopeType, List<Long> teamIds,
                                            long selfId, int limit) {
        return mapper.selectScopedCustomerIds(tenantId, scopeType, teamIds, selfId, limit);
    }
}
