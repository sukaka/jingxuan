package com.whale.crm.customer.privacy.infrastructure.mapper;

import com.whale.crm.customer.privacy.domain.CustomerPrivacyAccessLog;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyRepository;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface CustomerPrivacyMapper {

    int insertRequest(CustomerPrivacyRequest request);

    int backfillRequestNo(@Param("tenantId") long tenantId, @Param("id") long id,
                          @Param("requestNo") String requestNo);

    CustomerPrivacyRequest selectRequestById(@Param("tenantId") long tenantId, @Param("id") long id);

    CustomerPrivacyRequest selectRequestByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    CustomerPrivacyRepository.RequestRow selectRequestRowById(@Param("tenantId") long tenantId, @Param("id") long id);

    int countPendingForApplicantAndCustomer(@Param("tenantId") long tenantId,
                                            @Param("applicantUserId") long applicantUserId,
                                            @Param("customerId") long customerId);

    CustomerPrivacyRepository.RequestRow selectLatestPendingForApplicantAndCustomer(
            @Param("tenantId") long tenantId,
            @Param("applicantUserId") long applicantUserId,
            @Param("customerId") long customerId);

    CustomerPrivacyRequest selectLatestActiveGrant(@Param("tenantId") long tenantId,
                                                   @Param("applicantUserId") long applicantUserId,
                                                   @Param("customerId") long customerId,
                                                   @Param("now") Instant now);

    CustomerPrivacyRepository.RequestRow selectLatestActiveGrantRow(@Param("tenantId") long tenantId,
                                                                    @Param("applicantUserId") long applicantUserId,
                                                                    @Param("customerId") long customerId,
                                                                    @Param("now") Instant now);

    int approveRequest(@Param("tenantId") long tenantId, @Param("id") long id,
                       @Param("expectedVersion") long expectedVersion,
                       @Param("approvedFieldsJson") String approvedFieldsJson,
                       @Param("approvedDurationMinutes") int approvedDurationMinutes,
                       @Param("reviewedBy") long reviewedBy,
                       @Param("reviewedAt") Instant reviewedAt,
                       @Param("reviewReason") String reviewReason,
                       @Param("grantStartsAt") Instant grantStartsAt,
                       @Param("grantExpiresAt") Instant grantExpiresAt);

    int rejectRequest(@Param("tenantId") long tenantId, @Param("id") long id,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("reviewedBy") long reviewedBy,
                      @Param("reviewedAt") Instant reviewedAt,
                      @Param("reviewReason") String reviewReason);

    int cancelRequest(@Param("tenantId") long tenantId, @Param("id") long id,
                      @Param("expectedVersion") long expectedVersion);

    int revokeRequest(@Param("tenantId") long tenantId, @Param("id") long id,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("revokedBy") long revokedBy,
                      @Param("revokedAt") Instant revokedAt,
                      @Param("revokeReason") String revokeReason);

    int recordRevealHit(@Param("tenantId") long tenantId, @Param("id") long id,
                        @Param("revealedAt") Instant revealedAt);

    List<CustomerPrivacyRepository.RequestRow> searchRequests(
            @Param("tenantId") long tenantId,
            @Param("viewMode") String viewMode,
            @Param("adminAll") boolean adminAll,
            @Param("scopeType") String scopeType,
            @Param("teamIds") List<Long> teamIds,
            @Param("actorUserId") long actorUserId,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("customerId") Long customerId,
            @Param("offset") int offset,
            @Param("size") int size);

    long countRequests(
            @Param("tenantId") long tenantId,
            @Param("viewMode") String viewMode,
            @Param("adminAll") boolean adminAll,
            @Param("scopeType") String scopeType,
            @Param("teamIds") List<Long> teamIds,
            @Param("actorUserId") long actorUserId,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("customerId") Long customerId);

    int insertAccessLog(CustomerPrivacyAccessLog accessLog);

    List<CustomerPrivacyRepository.AccessLogRow> searchAccessLogs(
            @Param("tenantId") long tenantId,
            @Param("scopeType") String scopeType,
            @Param("teamIds") List<Long> teamIds,
            @Param("selfId") long selfId,
            @Param("customerId") Long customerId,
            @Param("actorUserId") Long actorUserId,
            @Param("accessMode") String accessMode,
            @Param("offset") int offset,
            @Param("size") int size);

    long countAccessLogs(
            @Param("tenantId") long tenantId,
            @Param("scopeType") String scopeType,
            @Param("teamIds") List<Long> teamIds,
            @Param("selfId") long selfId,
            @Param("customerId") Long customerId,
            @Param("actorUserId") Long actorUserId,
            @Param("accessMode") String accessMode);
}
