package com.whale.crm.integration.youzan.domain;

import com.whale.crm.customer.application.CustomerScopeSupport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface YouzanRepository {
    void insertEvent(long tenantId, YouzanEvent event);
    int updateEventPayload(long tenantId, long id, long version, String externalOrderNo,
                           String externalCustomerId, Long campaignId, String eventType,
                           String masked, String encrypted, String providerMasked, String providerEncrypted);
    void upsertOrderLatest(long tenantId, String shopId, String externalOrderNo, long latestEventId);
    Long findMaxEventIdByExternalOrder(long tenantId, String shopId, String externalOrderNo);
    int setOrderLatest(long tenantId, String shopId, String externalOrderNo, long latestEventId);
    int deleteOrderLatest(long tenantId, String shopId, String externalOrderNo, long expectedEventId);
    Optional<YouzanEvent> findEventByKey(long tenantId, String shopId, String eventId);
    Optional<YouzanEvent> findEvent(long tenantId, long id);
    Optional<YouzanEvent> findEventForUpdate(long tenantId, long id);
    List<YouzanEvent> findPendingEvents(long tenantId, String shopId, Instant retryBefore, int limit);
    int updateEventStatus(long tenantId, long id, long version, String status, String lastError,
                          Instant nextRetryAt, Instant processedAt, Long orderId);
    long countEvents(long tenantId, String shopId);
    long countScopedEvents(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope);
    long countTodayEvents(long tenantId, String shopId, Instant todayStartUtc, Instant todayEndUtcExclusive);
    long countScopedTodayEvents(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope,
                                Instant todayStartUtc, Instant todayEndUtcExclusive);
    long countCampaignOrders(long tenantId, String shopId);
    long countScopedCampaignOrders(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope);
    List<YouzanEvent> findLatestOrderEvents(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope,
                                            int offset, int size);
    long countDistinctExternalOrders(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope);

    void insertOrderMap(long tenantId, YouzanOrderMap map);
    Optional<YouzanOrderMap> findOrderMap(long tenantId, String shopId, String externalOrderNo);
    Optional<YouzanOrderMap> findOrderMapForUpdate(long tenantId, String shopId, String externalOrderNo);
    List<YouzanOrderMap> findOrderMaps(long tenantId, String shopId, int offset, int size);
    long countOrderMaps(long tenantId, String shopId);
    long countScopedOrderMaps(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope);
    int updateOrderMapTime(long tenantId, long id, long version, Instant externalUpdatedAt);

    Optional<YouzanSkuMapping> findSkuMapping(long tenantId, String shopId, String externalSkuId);
    Optional<YouzanSkuMapping> findSkuMappingForUpdate(long tenantId, long id);
    List<YouzanSkuMapping> findSkuMappings(long tenantId, String shopId);
    void insertSkuMapping(long tenantId, YouzanSkuMapping mapping);
    int updateSkuMapping(long tenantId, long id, long version, long crmSkuId, String status);

    Optional<YouzanCustomerLink> findCustomerLink(long tenantId, String shopId, String externalCustomerId);
    Optional<YouzanCustomerLink> findCustomerLinkForUpdate(long tenantId, long id);
    List<YouzanCustomerLink> findCustomerLinks(long tenantId, String shopId);
    List<YouzanCustomerLink> findScopedCustomerLinks(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope);
    void insertCustomerLink(long tenantId, YouzanCustomerLink link);
    int updateCustomerLink(long tenantId, long id, long version, long customerId, String reason);

    Optional<YouzanSyncIssue> findIssue(long tenantId, long id);
    Optional<YouzanSyncIssue> findIssueForUpdate(long tenantId, long id);
    Optional<YouzanSyncIssue> findIssueByEventAndType(long tenantId, long eventId, String issueType);
    List<Long> findIssueIdsWithoutOrderByExternalCustomer(long tenantId, String shopId, String externalCustomerId);
    List<Long> findIssueIdsByEventOrOrder(long tenantId, String shopId, String externalOrderNo, long eventId);
    List<YouzanSyncIssue> findOpenUnpaidIssuesByExternalOrderForUpdate(long tenantId, String shopId,
                                                                       String externalOrderNo, long excludeEventId);
    List<YouzanSyncIssue> findIssues(long tenantId, String status, int offset, int size);
    List<YouzanSyncIssue> findScopedIssues(long tenantId, String status, CustomerScopeSupport.ScopeQuery scope,
                                           int offset, int size);
    long countIssues(long tenantId, String status);
    long countScopedIssues(long tenantId, String status, CustomerScopeSupport.ScopeQuery scope);
    void insertIssue(long tenantId, YouzanSyncIssue issue);
    int updateIssueStatus(long tenantId, long id, long version, String status, int attempts,
                          String lastError, Instant nextRetryAt, Instant resolvedAt, Long resolvedBy);

    Optional<YouzanSyncCursor> findCursor(long tenantId, String shopId);
    Optional<YouzanSyncCursor> findCursorForUpdate(long tenantId, String shopId);
    void insertCursor(long tenantId, YouzanSyncCursor cursor);
    int updateCursor(long tenantId, long id, long version, String cursorValue,
                     Instant windowStart, Instant windowEnd,
                     Instant lastSyncAt, Instant lastExternalUpdatedAt);
    default int updateCursor(long tenantId, long id, long version, String cursorValue,
                             Instant lastSyncAt, Instant lastExternalUpdatedAt) {
        return updateCursor(tenantId, id, version, cursorValue, null, null, lastSyncAt, lastExternalUpdatedAt);
    }
}
