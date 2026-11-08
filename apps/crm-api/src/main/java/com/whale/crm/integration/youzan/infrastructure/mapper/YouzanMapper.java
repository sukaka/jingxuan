package com.whale.crm.integration.youzan.infrastructure.mapper;

import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanOrderMap;
import com.whale.crm.integration.youzan.domain.YouzanSkuMapping;
import com.whale.crm.integration.youzan.domain.YouzanSyncCursor;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface YouzanMapper {
    void insertEvent(@Param("tenantId") long tenantId, @Param("entity") YouzanEvent entity);
    int updateEventPayload(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                           @Param("externalOrderNo") String externalOrderNo, @Param("eventType") String eventType,
                           @Param("masked") String masked, @Param("encrypted") String encrypted,
                           @Param("providerMasked") String providerMasked,
                           @Param("providerEncrypted") String providerEncrypted);
    YouzanEvent selectEventByKey(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                                 @Param("eventId") String eventId);
    YouzanEvent selectEvent(@Param("tenantId") long tenantId, @Param("id") long id);
    YouzanEvent selectEventForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    List<YouzanEvent> selectPendingEvents(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                                          @Param("retryBefore") Instant retryBefore, @Param("limit") int limit);
    long countEvents(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    long countTodayEvents(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    long countCampaignOrders(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    List<YouzanEvent> selectLatestOrderEvents(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                                              @Param("linkedOnly") boolean linkedOnly,
                                              @Param("offset") int offset, @Param("size") int size);
    long countDistinctExternalOrders(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                                     @Param("linkedOnly") boolean linkedOnly);
    int updateEventStatus(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                          @Param("status") String status, @Param("lastError") String lastError,
                          @Param("nextRetryAt") Instant nextRetryAt, @Param("processedAt") Instant processedAt,
                          @Param("orderId") Long orderId);

    void insertOrderMap(@Param("tenantId") long tenantId, @Param("entity") YouzanOrderMap entity);
    YouzanOrderMap selectOrderMap(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                                  @Param("externalOrderNo") String externalOrderNo);
    YouzanOrderMap selectOrderMapForUpdate(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                                           @Param("externalOrderNo") String externalOrderNo);
    List<YouzanOrderMap> selectOrderMaps(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                                         @Param("offset") int offset, @Param("size") int size);
    long countOrderMaps(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    int updateOrderMapTime(@Param("tenantId") long tenantId, @Param("id") long id,
                           @Param("version") long version, @Param("externalUpdatedAt") Instant externalUpdatedAt);

    YouzanSkuMapping selectSkuMapping(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                                      @Param("externalSkuId") String externalSkuId);
    YouzanSkuMapping selectSkuMappingForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    List<YouzanSkuMapping> selectSkuMappings(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    void insertSkuMapping(@Param("tenantId") long tenantId, @Param("entity") YouzanSkuMapping entity);
    int updateSkuMapping(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("version") long version, @Param("crmSkuId") long crmSkuId, @Param("status") String status);

    YouzanCustomerLink selectCustomerLink(@Param("tenantId") long tenantId, @Param("shopId") String shopId,
                                          @Param("externalCustomerId") String externalCustomerId);
    YouzanCustomerLink selectCustomerLinkForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    List<YouzanCustomerLink> selectCustomerLinks(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    void insertCustomerLink(@Param("tenantId") long tenantId, @Param("entity") YouzanCustomerLink entity);
    int updateCustomerLink(@Param("tenantId") long tenantId, @Param("id") long id,
                           @Param("version") long version, @Param("customerId") long customerId,
                           @Param("reason") String reason);

    YouzanSyncIssue selectIssue(@Param("tenantId") long tenantId, @Param("id") long id);
    YouzanSyncIssue selectIssueForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    YouzanSyncIssue selectIssueByEventAndType(@Param("tenantId") long tenantId, @Param("eventId") long eventId,
                                              @Param("issueType") String issueType);
    List<YouzanSyncIssue> selectIssues(@Param("tenantId") long tenantId, @Param("status") String status,
                                       @Param("offset") int offset, @Param("size") int size);
    long countIssues(@Param("tenantId") long tenantId, @Param("status") String status);
    void insertIssue(@Param("tenantId") long tenantId, @Param("entity") YouzanSyncIssue entity);
    int updateIssueStatus(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                          @Param("status") String status, @Param("attempts") int attempts,
                          @Param("lastError") String lastError, @Param("nextRetryAt") Instant nextRetryAt,
                          @Param("resolvedAt") Instant resolvedAt, @Param("resolvedBy") Long resolvedBy);

    YouzanSyncCursor selectCursor(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    YouzanSyncCursor selectCursorForUpdate(@Param("tenantId") long tenantId, @Param("shopId") String shopId);
    void insertCursor(@Param("tenantId") long tenantId, @Param("entity") YouzanSyncCursor entity);
    int updateCursor(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                     @Param("cursorValue") String cursorValue, @Param("lastSyncAt") Instant lastSyncAt,
                     @Param("lastExternalUpdatedAt") Instant lastExternalUpdatedAt);
}
