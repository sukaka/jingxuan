package com.whale.crm.integration.youzan.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.whale.crm.platform.context.TenantContext;

public interface YouzanRepository {
    void insertEvent(long tenantId, YouzanEvent event);
    default void insertEvent(YouzanEvent event) { insertEvent(TenantContext.currentTenantId(), event); }
    int updateEventPayload(long tenantId, long id, long version, String externalOrderNo, String eventType,
                           String masked, String encrypted, String providerMasked, String providerEncrypted);
    Optional<YouzanEvent> findEventByKey(long tenantId, String shopId, String eventId);
    Optional<YouzanEvent> findEvent(long tenantId, long id);
    Optional<YouzanEvent> findEventForUpdate(long tenantId, long id);
    List<YouzanEvent> findPendingEvents(long tenantId, String shopId, Instant retryBefore, int limit);
    int updateEventStatus(long tenantId, long id, long version, String status, String lastError,
                          Instant nextRetryAt, Instant processedAt, Long orderId);
    long countEvents(long tenantId, String shopId);
    long countTodayEvents(long tenantId, String shopId);
    long countCampaignOrders(long tenantId, String shopId);
    List<YouzanEvent> findLatestOrderEvents(long tenantId, String shopId, boolean linkedOnly, int offset, int size);
    long countDistinctExternalOrders(long tenantId, String shopId, boolean linkedOnly);

    void insertOrderMap(long tenantId, YouzanOrderMap map);
    default void insertOrderMap(YouzanOrderMap map) { insertOrderMap(TenantContext.currentTenantId(), map); }
    Optional<YouzanOrderMap> findOrderMap(long tenantId, String shopId, String externalOrderNo);
    Optional<YouzanOrderMap> findOrderMapForUpdate(long tenantId, String shopId, String externalOrderNo);
    List<YouzanOrderMap> findOrderMaps(long tenantId, String shopId, int offset, int size);
    long countOrderMaps(long tenantId, String shopId);
    int updateOrderMapTime(long tenantId, long id, long version, Instant externalUpdatedAt);

    Optional<YouzanSkuMapping> findSkuMapping(long tenantId, String shopId, String externalSkuId);
    Optional<YouzanSkuMapping> findSkuMappingForUpdate(long tenantId, long id);
    List<YouzanSkuMapping> findSkuMappings(long tenantId, String shopId);
    void insertSkuMapping(YouzanSkuMapping mapping);
    int updateSkuMapping(long tenantId, long id, long version, long crmSkuId, String status);

    Optional<YouzanCustomerLink> findCustomerLink(long tenantId, String shopId, String externalCustomerId);
    Optional<YouzanCustomerLink> findCustomerLinkForUpdate(long tenantId, long id);
    List<YouzanCustomerLink> findCustomerLinks(long tenantId, String shopId);
    void insertCustomerLink(YouzanCustomerLink link);
    int updateCustomerLink(long tenantId, long id, long version, long customerId, String reason);

    Optional<YouzanSyncIssue> findIssue(long tenantId, long id);
    Optional<YouzanSyncIssue> findIssueForUpdate(long tenantId, long id);
    Optional<YouzanSyncIssue> findIssueByEventAndType(long tenantId, long eventId, String issueType);
    List<YouzanSyncIssue> findIssues(long tenantId, String status, int offset, int size);
    long countIssues(long tenantId, String status);
    void insertIssue(long tenantId, YouzanSyncIssue issue);
    default void insertIssue(YouzanSyncIssue issue) { insertIssue(TenantContext.currentTenantId(), issue); }
    int updateIssueStatus(long tenantId, long id, long version, String status, int attempts,
                          String lastError, Instant nextRetryAt, Instant resolvedAt, Long resolvedBy);

    Optional<YouzanSyncCursor> findCursor(long tenantId, String shopId);
    Optional<YouzanSyncCursor> findCursorForUpdate(long tenantId, String shopId);
    void insertCursor(long tenantId, YouzanSyncCursor cursor);
    default void insertCursor(YouzanSyncCursor cursor) { insertCursor(TenantContext.currentTenantId(), cursor); }
    int updateCursor(long tenantId, long id, long version, String cursorValue,
                     Instant lastSyncAt, Instant lastExternalUpdatedAt);
}
