package com.whale.crm.risk.infrastructure.mapper;

import com.whale.crm.risk.domain.RiskCase;
import com.whale.crm.risk.domain.RiskCaseHistory;
import com.whale.crm.risk.domain.RiskCaseQuery;
import com.whale.crm.risk.domain.RiskCaseRepository;
import com.whale.crm.risk.domain.RiskCustomerLink;
import com.whale.crm.risk.domain.RiskResolvedIds;
import com.whale.crm.risk.domain.RiskSourceCustomerLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface RiskCaseMapper extends RiskCaseRepository {
    @Override
    Optional<RiskCase> findById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<RiskCase> findByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<RiskCase> findBySource(@Param("tenantId") long tenantId, @Param("sourceType") String sourceType,
                                    @Param("sourceRef") String sourceRef, @Param("riskType") String riskType);

    @Override
    Optional<RiskCase> findBySourceForUpdate(@Param("tenantId") long tenantId,
                                             @Param("sourceType") String sourceType,
                                             @Param("sourceRef") String sourceRef,
                                             @Param("riskType") String riskType);

    @Override
    List<RiskCase> search(@Param("q") RiskCaseQuery query);

    @Override
    long countSearch(@Param("q") RiskCaseQuery query);

    @Override
    List<RiskCustomerLink> findResolvedCustomerLinks(@Param("tenantId") long tenantId,
                                                     @Param("riskCaseIds") List<Long> riskCaseIds);

    @Override
    List<RiskSourceCustomerLink> findDelayedOrderCustomerLinks(@Param("tenantId") long tenantId,
                                                               @Param("sourceIds") List<Long> sourceIds);

    @Override
    List<RiskSourceCustomerLink> findDelayedYouzanIssueCustomerLinks(@Param("tenantId") long tenantId,
                                                                     @Param("sourceIds") List<Long> sourceIds);

    @Override
    List<RiskSourceCustomerLink> findDelayedRefundCustomerLinks(@Param("tenantId") long tenantId,
                                                                @Param("sourceIds") List<Long> sourceIds);

    @Override
    List<RiskSourceCustomerLink> findDelayedEntitlementCustomerLinks(@Param("tenantId") long tenantId,
                                                                     @Param("sourceIds") List<Long> sourceIds);

    @Override
    List<RiskSourceCustomerLink> findDelayedRedemptionCustomerLinks(@Param("tenantId") long tenantId,
                                                                    @Param("sourceIds") List<Long> sourceIds);

    @Override
    Optional<RiskResolvedIds> resolveOrderLink(@Param("tenantId") long tenantId, @Param("sourceId") long sourceId);

    @Override
    Optional<RiskResolvedIds> resolveYouzanIssueLink(@Param("tenantId") long tenantId, @Param("sourceId") long sourceId);

    @Override
    Optional<RiskResolvedIds> resolveRefundLink(@Param("tenantId") long tenantId, @Param("sourceId") long sourceId);

    @Override
    Optional<RiskResolvedIds> resolveEntitlementLink(@Param("tenantId") long tenantId, @Param("sourceId") long sourceId);

    @Override
    Optional<RiskResolvedIds> resolveRedemptionLink(@Param("tenantId") long tenantId, @Param("sourceId") long sourceId);

    @Override
    void insert(@Param("e") RiskCase riskCase);

    @Override
    int backfillLinkIfAbsent(@Param("tenantId") long tenantId, @Param("id") long id,
                             @Param("customerId") Long customerId, @Param("orderId") Long orderId);

    @Override
    int linkCustomerToYouzanIssues(@Param("tenantId") long tenantId,
                                   @Param("sourceRefs") List<String> sourceRefs,
                                   @Param("customerId") long customerId);

    @Override
    int linkOrderToYouzanIssues(@Param("tenantId") long tenantId,
                                @Param("sourceRefs") List<String> sourceRefs,
                                @Param("orderId") long orderId,
                                @Param("customerId") long customerId);

    @Override
    List<Long> findIdsByCustomerId(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    @Override
    int reassignSpecific(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids,
                         @Param("fromCustomerId") long fromCustomerId, @Param("toCustomerId") long toCustomerId);

    @Override
    int reassignSpecificByOrderIds(@Param("tenantId") long tenantId, @Param("orderIds") List<Long> orderIds,
                                   @Param("fromCustomerId") long fromCustomerId,
                                   @Param("toCustomerId") long toCustomerId);

    @Override
    int updateStatus(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                     @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                     @Param("ownerUserId") Long ownerUserId, @Param("resolution") String resolution,
                     @Param("resolvedAt") Instant resolvedAt);

    @Override
    void insertHistory(@Param("e") RiskCaseHistory history);

    @Override
    List<RiskCaseHistory> findHistory(@Param("tenantId") long tenantId, @Param("riskCaseId") long riskCaseId);
}
