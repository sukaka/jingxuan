package com.whale.crm.customer.tier.infrastructure.mapper;

import com.whale.crm.customer.tier.domain.CustomerTierDowngradeReview;
import com.whale.crm.customer.tier.domain.CustomerTierHistory;
import com.whale.crm.customer.tier.domain.CustomerTierLevel;
import com.whale.crm.customer.tier.domain.CustomerTierMetricSnapshot;
import com.whale.crm.customer.tier.domain.CustomerTierMetrics;
import com.whale.crm.customer.tier.domain.CustomerTierRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface CustomerTierMapper {
    List<CustomerTierRule> selectRules(@Param("tenantId") long tenantId);

    CustomerTierRule selectRule(@Param("tenantId") long tenantId, @Param("ruleId") long ruleId);

    CustomerTierRule selectRuleForUpdate(@Param("tenantId") long tenantId, @Param("ruleId") long ruleId);

    CustomerTierRule selectActiveRule(@Param("tenantId") long tenantId);

    CustomerTierRule selectActiveRuleForUpdate(@Param("tenantId") long tenantId);

    List<CustomerTierRule> selectAllActiveRules();

    Integer selectMaxVersionNo(@Param("tenantId") long tenantId, @Param("ruleCode") String ruleCode);

    int insertRule(CustomerTierRule rule);

    CustomerTierRule selectRuleByVersion(@Param("tenantId") long tenantId, @Param("ruleCode") String ruleCode,
                                         @Param("versionNo") int versionNo);

    int updateDraftRule(CustomerTierRule rule);

    int activateRule(@Param("tenantId") long tenantId, @Param("ruleId") long ruleId,
                     @Param("version") long version, @Param("activatedAt") Instant activatedAt);

    int retireRule(@Param("tenantId") long tenantId, @Param("ruleId") long ruleId,
                   @Param("version") long version, @Param("retiredAt") Instant retiredAt);

    int deleteLevels(@Param("tenantId") long tenantId, @Param("ruleId") long ruleId);

    int insertLevel(CustomerTierLevel level);

    List<CustomerTierLevel> selectLevels(@Param("tenantId") long tenantId, @Param("ruleId") long ruleId);

    CustomerTierMetrics selectMetrics(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                                      @Param("startInclusive") Instant startInclusive,
                                      @Param("endExclusive") Instant endExclusive,
                                      @Param("archiveScope") String archiveScope);

    int insertSnapshot(CustomerTierMetricSnapshot snapshot);

    CustomerTierMetricSnapshot selectSnapshotByKey(@Param("tenantId") long tenantId,
                                                   @Param("recalculationKey") String recalculationKey);

    CustomerTierMetricSnapshot selectSnapshot(@Param("tenantId") long tenantId,
                                              @Param("snapshotId") long snapshotId);

    CustomerTierMetricSnapshot selectLatestSnapshot(@Param("tenantId") long tenantId,
                                                    @Param("customerId") long customerId);

    List<CustomerTierMetricSnapshot> selectSnapshots(@Param("tenantId") long tenantId,
                                                     @Param("customerId") long customerId,
                                                     @Param("limit") int limit);

    CustomerTierDowngradeReview selectReview(@Param("tenantId") long tenantId, @Param("reviewId") long reviewId);

    CustomerTierDowngradeReview selectReviewForUpdate(@Param("tenantId") long tenantId,
                                                      @Param("reviewId") long reviewId);

    CustomerTierDowngradeReview selectPendingForUpdate(@Param("tenantId") long tenantId,
                                                       @Param("customerId") long customerId);

    CustomerTierDowngradeReview selectPending(@Param("tenantId") long tenantId,
                                              @Param("customerId") long customerId);

    int insertReview(CustomerTierDowngradeReview review);

    int updateReviewStatus(@Param("tenantId") long tenantId, @Param("reviewId") long reviewId,
                           @Param("version") long version, @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus, @Param("reviewerId") Long reviewerId,
                           @Param("reason") String reason, @Param("reviewedAt") Instant reviewedAt,
                           @Param("effectiveAt") Instant effectiveAt);

    int markPendingStale(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                         @Param("reason") String reason, @Param("changedAt") Instant changedAt);

    int markAllPendingStale(@Param("tenantId") long tenantId, @Param("reason") String reason,
                            @Param("changedAt") Instant changedAt);

    List<CustomerTierDowngradeReview> searchReviews(@Param("tenantId") long tenantId,
            @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds,
            @Param("selfId") long selfId, @Param("status") String status,
            @Param("offset") int offset, @Param("size") int size);

    long countReviews(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                      @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                      @Param("status") String status);

    int insertHistory(CustomerTierHistory history);

    List<CustomerTierHistory> selectHistory(@Param("tenantId") long tenantId,
                                            @Param("customerId") long customerId, @Param("limit") int limit);

    int updateTier(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                   @Param("expectedVersion") long expectedVersion, @Param("tierCode") String tierCode,
                   @Param("locked") boolean locked, @Param("lockReason") String lockReason);

    List<Long> selectCustomerIdsAfter(@Param("tenantId") long tenantId, @Param("afterId") long afterId,
                                      @Param("limit") int limit);

    List<Long> selectScopedCustomerIds(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                                       @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                                       @Param("limit") int limit);
}
