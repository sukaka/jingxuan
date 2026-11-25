package com.whale.crm.integration.youzan.infrastructure;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanOrderMap;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanSkuMapping;
import com.whale.crm.integration.youzan.domain.YouzanSyncCursor;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import com.whale.crm.integration.youzan.infrastructure.mapper.YouzanMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisYouzanRepository implements YouzanRepository {
    private final YouzanMapper mapper;

    public MybatisYouzanRepository(YouzanMapper mapper) {
        this.mapper = mapper;
    }

    public void insertEvent(long tenantId, YouzanEvent e) { mapper.insertEvent(tenantId, e); }
    public int updateEventPayload(long tenantId, long id, long version, String externalOrderNo,
                                  String externalCustomerId, Long campaignId, String eventType,
                                  String masked, String encrypted, String providerMasked, String providerEncrypted) {
        return mapper.updateEventPayload(tenantId, id, version, externalOrderNo, externalCustomerId, campaignId,
                eventType, masked, encrypted, providerMasked, providerEncrypted);
    }
    public void upsertOrderLatest(long tenantId, String shopId, String externalOrderNo, long latestEventId) {
        mapper.upsertOrderLatest(tenantId, shopId, externalOrderNo, latestEventId);
    }
    public Long findMaxEventIdByExternalOrder(long tenantId, String shopId, String externalOrderNo) {
        return mapper.selectMaxEventIdByExternalOrder(tenantId, shopId, externalOrderNo);
    }
    public int setOrderLatest(long tenantId, String shopId, String externalOrderNo, long latestEventId) {
        return mapper.setOrderLatest(tenantId, shopId, externalOrderNo, latestEventId);
    }
    public int deleteOrderLatest(long tenantId, String shopId, String externalOrderNo, long expectedEventId) {
        return mapper.deleteOrderLatest(tenantId, shopId, externalOrderNo, expectedEventId);
    }
    public Optional<YouzanEvent> findEventByKey(long tenantId, String shopId, String eventId) { return Optional.ofNullable(mapper.selectEventByKey(tenantId, shopId, eventId)); }
    public Optional<YouzanEvent> findEvent(long tenantId, long id) { return Optional.ofNullable(mapper.selectEvent(tenantId, id)); }
    public Optional<YouzanEvent> findEventForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectEventForUpdate(tenantId, id)); }
    public List<YouzanEvent> findPendingEvents(long tenantId, String shopId, Instant retryBefore, int limit) { return mapper.selectPendingEvents(tenantId, shopId, retryBefore, limit); }
    public long countEvents(long tenantId, String shopId) { return mapper.countEvents(tenantId, shopId); }
    public long countScopedEvents(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope) { return mapper.countScopedEvents(tenantId, shopId, scope); }
    public long countTodayEvents(long tenantId, String shopId, Instant todayStartUtc, Instant todayEndUtcExclusive) {
        return mapper.countTodayEvents(tenantId, shopId, todayStartUtc, todayEndUtcExclusive);
    }
    public long countScopedTodayEvents(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope,
                                       Instant todayStartUtc, Instant todayEndUtcExclusive) {
        return mapper.countScopedTodayEvents(tenantId, shopId, scope, todayStartUtc, todayEndUtcExclusive);
    }
    public long countCampaignOrders(long tenantId, String shopId) { return mapper.countCampaignOrders(tenantId, shopId); }
    public long countScopedCampaignOrders(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope) { return mapper.countScopedCampaignOrders(tenantId, shopId, scope); }
    public List<YouzanEvent> findLatestOrderEvents(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope, int offset, int size) { return mapper.selectLatestOrderEvents(tenantId, shopId, scope, offset, size); }
    public long countDistinctExternalOrders(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope) { return mapper.countDistinctExternalOrders(tenantId, shopId, scope); }
    public int updateEventStatus(long tenantId, long id, long version, String status, String lastError,
                                 Instant nextRetryAt, Instant processedAt, Long orderId) {
        return mapper.updateEventStatus(tenantId, id, version, status, lastError, nextRetryAt, processedAt, orderId);
    }

    public void insertOrderMap(long tenantId, YouzanOrderMap e) { mapper.insertOrderMap(tenantId, e); }
    public Optional<YouzanOrderMap> findOrderMap(long tenantId, String shopId, String externalOrderNo) { return Optional.ofNullable(mapper.selectOrderMap(tenantId, shopId, externalOrderNo)); }
    public Optional<YouzanOrderMap> findOrderMapForUpdate(long tenantId, String shopId, String externalOrderNo) { return Optional.ofNullable(mapper.selectOrderMapForUpdate(tenantId, shopId, externalOrderNo)); }
    public List<YouzanOrderMap> findOrderMaps(long tenantId, String shopId, int offset, int size) { return mapper.selectOrderMaps(tenantId, shopId, offset, size); }
    public long countOrderMaps(long tenantId, String shopId) { return mapper.countOrderMaps(tenantId, shopId); }
    public long countScopedOrderMaps(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope) { return mapper.countScopedOrderMaps(tenantId, shopId, scope); }
    public int updateOrderMapTime(long tenantId, long id, long version, Instant externalUpdatedAt) { return mapper.updateOrderMapTime(tenantId, id, version, externalUpdatedAt); }

    public Optional<YouzanSkuMapping> findSkuMapping(long tenantId, String shopId, String externalSkuId) { return Optional.ofNullable(mapper.selectSkuMapping(tenantId, shopId, externalSkuId)); }
    public Optional<YouzanSkuMapping> findSkuMappingForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectSkuMappingForUpdate(tenantId, id)); }
    public List<YouzanSkuMapping> findSkuMappings(long tenantId, String shopId) { return mapper.selectSkuMappings(tenantId, shopId); }
    public void insertSkuMapping(long tenantId, YouzanSkuMapping e) { mapper.insertSkuMapping(tenantId, e); }
    public int updateSkuMapping(long tenantId, long id, long version, long crmSkuId, String status) { return mapper.updateSkuMapping(tenantId, id, version, crmSkuId, status); }

    public Optional<YouzanCustomerLink> findCustomerLink(long tenantId, String shopId, String externalCustomerId) { return Optional.ofNullable(mapper.selectCustomerLink(tenantId, shopId, externalCustomerId)); }
    public Optional<YouzanCustomerLink> findCustomerLinkForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectCustomerLinkForUpdate(tenantId, id)); }
    public List<YouzanCustomerLink> findCustomerLinks(long tenantId, String shopId) { return mapper.selectCustomerLinks(tenantId, shopId); }
    public List<YouzanCustomerLink> findScopedCustomerLinks(long tenantId, String shopId, CustomerScopeSupport.ScopeQuery scope) { return mapper.selectScopedCustomerLinks(tenantId, shopId, scope); }
    public void insertCustomerLink(long tenantId, YouzanCustomerLink e) { mapper.insertCustomerLink(tenantId, e); }
    public int updateCustomerLink(long tenantId, long id, long version, long customerId, String reason) { return mapper.updateCustomerLink(tenantId, id, version, customerId, reason); }

    public Optional<YouzanSyncIssue> findIssue(long tenantId, long id) { return Optional.ofNullable(mapper.selectIssue(tenantId, id)); }
    public Optional<YouzanSyncIssue> findIssueForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectIssueForUpdate(tenantId, id)); }
    public Optional<YouzanSyncIssue> findIssueByEventAndType(long tenantId, long eventId, String issueType) { return Optional.ofNullable(mapper.selectIssueByEventAndType(tenantId, eventId, issueType)); }
    public List<Long> findIssueIdsWithoutOrderByExternalCustomer(long tenantId, String shopId, String externalCustomerId) {
        return mapper.selectIssueIdsWithoutOrderByExternalCustomer(tenantId, shopId, externalCustomerId);
    }
    public List<Long> findIssueIdsByEventOrOrder(long tenantId, String shopId, String externalOrderNo, long eventId) {
        return mapper.selectIssueIdsByEventOrOrder(tenantId, shopId, externalOrderNo, eventId);
    }
    public List<YouzanSyncIssue> findOpenUnpaidIssuesByExternalOrderForUpdate(long tenantId, String shopId, String externalOrderNo, long excludeEventId) {
        return mapper.selectOpenUnpaidIssuesByExternalOrderForUpdate(tenantId, shopId, externalOrderNo, excludeEventId);
    }
    public List<YouzanSyncIssue> findIssues(long tenantId, String status, int offset, int size) { return mapper.selectIssues(tenantId, status, offset, size); }
    public List<YouzanSyncIssue> findScopedIssues(long tenantId, String status, CustomerScopeSupport.ScopeQuery scope, int offset, int size) { return mapper.selectScopedIssues(tenantId, status, scope, offset, size); }
    public long countIssues(long tenantId, String status) { return mapper.countIssues(tenantId, status); }
    public long countScopedIssues(long tenantId, String status, CustomerScopeSupport.ScopeQuery scope) { return mapper.countScopedIssues(tenantId, status, scope); }
    public void insertIssue(long tenantId, YouzanSyncIssue e) { mapper.insertIssue(tenantId, e); }
    public int updateIssueStatus(long tenantId, long id, long version, String status, int attempts,
                                 String lastError, Instant nextRetryAt, Instant resolvedAt, Long resolvedBy) {
        return mapper.updateIssueStatus(tenantId, id, version, status, attempts, lastError, nextRetryAt, resolvedAt, resolvedBy);
    }

    public Optional<YouzanSyncCursor> findCursor(long tenantId, String shopId) { return Optional.ofNullable(mapper.selectCursor(tenantId, shopId)); }
    public Optional<YouzanSyncCursor> findCursorForUpdate(long tenantId, String shopId) { return Optional.ofNullable(mapper.selectCursorForUpdate(tenantId, shopId)); }
    public void insertCursor(long tenantId, YouzanSyncCursor e) { mapper.insertCursor(tenantId, e); }
    public int updateCursor(long tenantId, long id, long version, String cursorValue,
                            Instant windowStart, Instant windowEnd,
                            Instant lastSyncAt, Instant lastExternalUpdatedAt) {
        return mapper.updateCursor(tenantId, id, version, cursorValue, windowStart, windowEnd,
                lastSyncAt, lastExternalUpdatedAt);
    }
}
