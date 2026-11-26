package com.whale.crm.annualreport.infrastructure.mapper;

import com.whale.crm.annualreport.domain.AnnualReport;
import com.whale.crm.annualreport.domain.AnnualReportCustomerFact;
import com.whale.crm.annualreport.domain.AnnualReportRepository;
import com.whale.crm.annualreport.domain.AnnualReportVisitEvent;
import com.whale.crm.annualreport.domain.AnnualReportVisitSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AnnualReportMapper extends AnnualReportRepository {

    @Override
    void insertReport(@Param("e") AnnualReport report);

    @Override
    Optional<AnnualReport> findById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<AnnualReport> findByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<AnnualReport> findByShareKey(@Param("shareKey") String shareKey);

    @Override
    int disableReport(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                      @Param("reason") String reason, @Param("disabledBy") long disabledBy,
                      @Param("disabledAt") Instant disabledAt);

    @Override
    int disableActiveForCustomerYear(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                                     @Param("reportYear") int reportYear, @Param("reason") String reason,
                                     @Param("disabledBy") long disabledBy, @Param("disabledAt") Instant disabledAt);

    @Override
    List<AnnualReportCustomerFact.ReportListRow> searchReports(@Param("tenantId") long tenantId,
                                                               @Param("scopeType") String scopeType,
                                                               @Param("teamIds") List<Long> teamIds,
                                                               @Param("selfId") long selfId,
                                                               @Param("reportYear") Integer reportYear,
                                                               @Param("customerId") Long customerId,
                                                               @Param("status") String status,
                                                               @Param("offset") int offset,
                                                               @Param("limit") int limit);

    @Override
    long countSearchReports(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                            @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                            @Param("reportYear") Integer reportYear, @Param("customerId") Long customerId,
                            @Param("status") String status);

    @Override
    AnnualReportCustomerFact.ReportSummaryRow summarizeReports(@Param("tenantId") long tenantId,
                                                               @Param("scopeType") String scopeType,
                                                               @Param("teamIds") List<Long> teamIds,
                                                               @Param("selfId") long selfId,
                                                               @Param("reportYear") int reportYear);

    @Override
    Optional<AnnualReportVisitSession> findSessionByKeyForUpdate(@Param("tenantId") long tenantId,
                                                                 @Param("reportId") long reportId,
                                                                 @Param("sessionKey") String sessionKey);

    @Override
    void insertSession(@Param("e") AnnualReportVisitSession session);

    @Override
    int updateSession(@Param("e") AnnualReportVisitSession session);

    @Override
    List<AnnualReportVisitSession> listSessions(@Param("tenantId") long tenantId, @Param("reportId") long reportId,
                                                @Param("limit") int limit);

    @Override
    void insertEvent(@Param("e") AnnualReportVisitEvent event);

    @Override
    List<AnnualReportCustomerFact.OrderRow> findYearPaidOrders(@Param("tenantId") long tenantId,
                                                               @Param("customerId") long customerId,
                                                               @Param("startInclusive") Instant startInclusive,
                                                               @Param("endExclusive") Instant endExclusive);

    @Override
    List<AnnualReportCustomerFact.OrderItemRow> findOrderItemsByOrderIds(@Param("tenantId") long tenantId,
                                                                         @Param("orderIds") List<Long> orderIds);

    @Override
    List<AnnualReportCustomerFact.EntitlementRow> findYearOrderEntitlements(@Param("tenantId") long tenantId,
                                                                            @Param("customerId") long customerId,
                                                                            @Param("orderIds") List<Long> orderIds);

    @Override
    List<AnnualReportCustomerFact.EntitlementRow> findAvailableEntitlements(@Param("tenantId") long tenantId,
                                                                            @Param("customerId") long customerId);

    @Override
    List<AnnualReportCustomerFact.RedemptionRow> findYearPostedRedemptions(@Param("tenantId") long tenantId,
                                                                           @Param("customerId") long customerId,
                                                                           @Param("startInclusive") Instant startInclusive,
                                                                           @Param("endExclusive") Instant endExclusive);

    @Override
    List<AnnualReportCustomerFact.FollowupRow> findYearFollowups(@Param("tenantId") long tenantId,
                                                                 @Param("customerId") long customerId,
                                                                 @Param("startInclusive") Instant startInclusive,
                                                                 @Param("endExclusive") Instant endExclusive);

    @Override
    List<String> findCustomerTagCodes(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    @Override
    Optional<String> findUserDisplayName(@Param("tenantId") long tenantId, @Param("userId") long userId);

    @Override
    Optional<Instant> findEarliestPaidOrderAt(@Param("tenantId") long tenantId, @Param("customerId") long customerId);
}
