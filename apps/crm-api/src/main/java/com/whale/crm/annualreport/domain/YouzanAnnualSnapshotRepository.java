package com.whale.crm.annualreport.domain;

import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 有赞历史订单只读快照与 C 端统一自动登录仓储接口。
 */
public interface YouzanAnnualSnapshotRepository {

    int upsertProfile(@Param("profile") YouzanAnnualProfile profile);

    int upsertOrderSnapshot(@Param("order") YouzanAnnualOrderSnapshot order);

    int refreshProfileStats(@Param("tenantId") long tenantId,
                            @Param("shopId") String shopId,
                            @Param("yzOpenId") String yzOpenId,
                            @Param("syncedAt") Instant syncedAt);

    Optional<YouzanAnnualProfile> findProfileByKey(@Param("tenantId") long tenantId,
                                                   @Param("shopId") String shopId,
                                                   @Param("yzOpenId") String yzOpenId);

    Optional<YouzanAnnualProfile> findProfileByUnionIdHash(@Param("tenantId") long tenantId,
                                                           @Param("unionIdHash") String unionIdHash,
                                                           @Param("shopId") String shopId);

    Optional<YouzanAnnualProfile> findProfileByOpenIdHash(@Param("tenantId") long tenantId,
                                                          @Param("openIdHash") String openIdHash,
                                                          @Param("shopId") String shopId);

    Optional<YouzanAnnualProfile> findProfileByMobileHash(@Param("tenantId") long tenantId,
                                                          @Param("mobileHash") String mobileHash,
                                                          @Param("shopId") String shopId);

    Optional<YouzanAnnualProfile> findProfileByCustomerId(@Param("tenantId") long tenantId,
                                                          @Param("customerId") long customerId);

    List<YouzanAnnualProfile> searchProfiles(@Param("tenantId") long tenantId,
                                             @Param("shopId") String shopId,
                                             @Param("keyword") String keyword,
                                             @Param("offset") int offset,
                                             @Param("limit") int limit);

    long countProfiles(@Param("tenantId") long tenantId,
                       @Param("shopId") String shopId,
                       @Param("keyword") String keyword);

    SnapshotSummaryRow summarizeSnapshots(@Param("tenantId") long tenantId,
                                          @Param("shopId") String shopId);

    List<YouzanAnnualOrderSnapshot> findPaidOrdersByBuyerYear(@Param("tenantId") long tenantId,
                                                              @Param("shopId") String shopId,
                                                              @Param("yzOpenId") String yzOpenId,
                                                              @Param("yearStart") Instant yearStart,
                                                              @Param("yearEnd") Instant yearEnd);

    List<YouzanAnnualOrderSnapshot> findPaidOrdersByCustomerYear(@Param("tenantId") long tenantId,
                                                                 @Param("customerId") long customerId,
                                                                 @Param("yearStart") Instant yearStart,
                                                                 @Param("yearEnd") Instant yearEnd);

    Optional<Long> findLinkedCustomerIdByYzOpenId(@Param("tenantId") long tenantId,
                                                  @Param("shopId") String shopId,
                                                  @Param("yzOpenId") String yzOpenId);

    List<Long> findCustomerIdsByMobileHash(@Param("tenantId") long tenantId,
                                           @Param("mobileHash") String mobileHash);

    Optional<String> findActiveReportShareKeyByCustomerYear(@Param("tenantId") long tenantId,
                                                            @Param("customerId") long customerId,
                                                            @Param("reportYear") int reportYear);

    int upsertIdentityBinding(@Param("binding") H5ClientIdentityBinding binding);

    Optional<H5ClientIdentityBinding> findIdentityBinding(@Param("tenantId") long tenantId,
                                                          @Param("channelType") String channelType,
                                                          @Param("openidHash") String openidHash);

    Optional<H5ClientIdentityBinding> findIdentityBindingByUnionId(@Param("tenantId") long tenantId,
                                                                   @Param("unionidHash") String unionidHash);

    int insertSmsVerification(@Param("sms") H5SmsVerification sms);

    Optional<H5SmsVerification> findLatestSmsVerification(@Param("tenantId") long tenantId,
                                                          @Param("mobileHash") String mobileHash);

    Optional<H5SmsVerification> findLatestSmsVerificationForUpdate(@Param("tenantId") long tenantId,
                                                                   @Param("mobileHash") String mobileHash);

    int incrementSmsAttempt(@Param("tenantId") long tenantId, @Param("id") long id);

    int consumeSmsVerification(@Param("tenantId") long tenantId,
                               @Param("id") long id,
                               @Param("consumedAt") Instant consumedAt);

    Optional<H5UnifiedVisitSession> findUnifiedVisitForUpdate(@Param("tenantId") long tenantId,
                                                              @Param("reportYear") int reportYear,
                                                              @Param("yzOpenId") String yzOpenId,
                                                              @Param("sessionKey") String sessionKey);

    int insertUnifiedVisit(@Param("session") H5UnifiedVisitSession session);

    int updateUnifiedVisit(@Param("session") H5UnifiedVisitSession session);

    record SnapshotSummaryRow(
            long shopCount,
            long profileCount,
            long linkedCustomerCount,
            long openIdBoundCount,
            long unionIdBoundCount,
            long mobileBoundCount,
            long paidOrderCount,
            long totalPaymentFen,
            long unifiedVisitSessions,
            Instant lastSyncedAt
    ) {
    }
}
