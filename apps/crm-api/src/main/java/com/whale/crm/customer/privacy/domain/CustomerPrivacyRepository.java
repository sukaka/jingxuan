package com.whale.crm.customer.privacy.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 客户隐私查看申请与不可覆盖揭盲日志仓储。全部查询显式 tenantId 过滤。
 */
public interface CustomerPrivacyRepository {

    void insertRequest(CustomerPrivacyRequest request);

    int backfillRequestNo(long tenantId, long id, String requestNo);

    Optional<CustomerPrivacyRequest> findRequestById(long tenantId, long id);

    Optional<CustomerPrivacyRequest> findRequestByIdForUpdate(long tenantId, long id);

    Optional<RequestRow> findRequestRowById(long tenantId, long id);

    boolean existsPendingForApplicantAndCustomer(long tenantId, long applicantUserId, long customerId);

    Optional<RequestRow> findLatestPendingForApplicantAndCustomer(long tenantId, long applicantUserId, long customerId);

    Optional<CustomerPrivacyRequest> findLatestActiveGrant(long tenantId, long applicantUserId, long customerId,
                                                           Instant now);

    Optional<RequestRow> findLatestActiveGrantRow(long tenantId, long applicantUserId, long customerId, Instant now);

    int approveRequest(long tenantId, long id, long expectedVersion, String approvedFieldsJson,
                       int approvedDurationMinutes, long reviewedBy, Instant reviewedAt, String reviewReason,
                       Instant grantStartsAt, Instant grantExpiresAt);

    int rejectRequest(long tenantId, long id, long expectedVersion, long reviewedBy, Instant reviewedAt,
                      String reviewReason);

    int cancelRequest(long tenantId, long id, long expectedVersion);

    int revokeRequest(long tenantId, long id, long expectedVersion, long revokedBy, Instant revokedAt,
                      String revokeReason);

    int recordRevealHit(long tenantId, long id, Instant revealedAt);

    List<RequestRow> searchRequests(long tenantId, String viewMode, boolean adminAll, String scopeType,
                                    List<Long> teamIds, long actorUserId, String status, String keyword,
                                    Long customerId, int offset, int size);

    long countRequests(long tenantId, String viewMode, boolean adminAll, String scopeType,
                       List<Long> teamIds, long actorUserId, String status, String keyword, Long customerId);

    void insertAccessLog(CustomerPrivacyAccessLog accessLog);

    List<AccessLogRow> searchAccessLogs(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                        Long customerId, Long actorUserId, String accessMode, int offset, int size);

    long countAccessLogs(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                         Long customerId, Long actorUserId, String accessMode);

    record RequestRow(
            Long id,
            String requestNo,
            Long customerId,
            String customerNo,
            String customerName,
            Long applicantUserId,
            String applicantName,
            Long applicantTeamId,
            String requestedFieldsJson,
            String approvedFieldsJson,
            String reason,
            String status,
            Long assignedApproverId,
            String assignedApproverName,
            String approverSource,
            Integer requestedDurationMinutes,
            Integer approvedDurationMinutes,
            Long reviewedBy,
            String reviewedByName,
            Instant reviewedAt,
            String reviewReason,
            Instant grantStartsAt,
            Instant grantExpiresAt,
            Long revokedBy,
            String revokedByName,
            Instant revokedAt,
            String revokeReason,
            Integer revealCount,
            Instant lastRevealedAt,
            Instant createdAt,
            Long version) {
    }

    record AccessLogRow(
            Long id,
            Long customerId,
            String customerNo,
            String customerName,
            Long actorUserId,
            String actorUsername,
            String actorDisplayName,
            String actorRoleSnapshot,
            String accessMode,
            Long requestId,
            String requestNo,
            String revealedFieldsJson,
            String maskedValuesSnapshotJson,
            String reason,
            String clientIp,
            String userAgent,
            String traceId,
            Instant createdAt) {
    }
}
