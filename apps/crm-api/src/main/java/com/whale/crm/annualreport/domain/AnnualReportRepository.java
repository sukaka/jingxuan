package com.whale.crm.annualreport.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 年终购买轨迹 H5 仓储接口（显式租户隔离与数据范围过滤）。
 */
public interface AnnualReportRepository {

    void insertReport(AnnualReport report);

    Optional<AnnualReport> findById(long tenantId, long id);

    Optional<AnnualReport> findByIdForUpdate(long tenantId, long id);

    Optional<AnnualReport> findByShareKey(String shareKey);

    int disableReport(long tenantId, long id, long version, String reason, long disabledBy, Instant disabledAt);

    int disableActiveForCustomerYear(long tenantId, long customerId, int reportYear, String reason,
                                     long disabledBy, Instant disabledAt);

    List<AnnualReportCustomerFact.ReportListRow> searchReports(long tenantId, String scopeType, List<Long> teamIds,
                                                               long selfId, Integer reportYear, Long customerId,
                                                               String status, int offset, int limit);

    long countSearchReports(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                            Integer reportYear, Long customerId, String status);

    AnnualReportCustomerFact.ReportSummaryRow summarizeReports(long tenantId, String scopeType, List<Long> teamIds,
                                                               long selfId, int reportYear);

    Optional<AnnualReportVisitSession> findSessionByKeyForUpdate(long tenantId, long reportId, String sessionKey);

    void insertSession(AnnualReportVisitSession session);

    int updateSession(AnnualReportVisitSession session);

    List<AnnualReportVisitSession> listSessions(long tenantId, long reportId, int limit);

    void insertEvent(AnnualReportVisitEvent event);

    // ---- 真实 CRM 跨表只读聚合查询（按租户 + 客户 ID 精确收敛） ----

    List<AnnualReportCustomerFact.OrderRow> findYearPaidOrders(long tenantId, long customerId,
                                                               Instant startInclusive, Instant endExclusive);

    List<AnnualReportCustomerFact.OrderItemRow> findOrderItemsByOrderIds(long tenantId, List<Long> orderIds);

    List<AnnualReportCustomerFact.EntitlementRow> findYearOrderEntitlements(long tenantId, long customerId,
                                                                            List<Long> orderIds);

    List<AnnualReportCustomerFact.EntitlementRow> findAvailableEntitlements(long tenantId, long customerId);

    List<AnnualReportCustomerFact.RedemptionRow> findYearPostedRedemptions(long tenantId, long customerId,
                                                                           Instant startInclusive,
                                                                           Instant endExclusive);

    List<AnnualReportCustomerFact.FollowupRow> findYearFollowups(long tenantId, long customerId,
                                                                 Instant startInclusive, Instant endExclusive);

    List<String> findCustomerTagCodes(long tenantId, long customerId);

    Optional<String> findUserDisplayName(long tenantId, long userId);

    Optional<Instant> findEarliestPaidOrderAt(long tenantId, long customerId);
}
