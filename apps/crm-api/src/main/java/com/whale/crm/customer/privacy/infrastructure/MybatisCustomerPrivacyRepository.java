package com.whale.crm.customer.privacy.infrastructure;

import com.whale.crm.customer.privacy.domain.CustomerPrivacyAccessLog;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyRepository;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyRequest;
import com.whale.crm.customer.privacy.infrastructure.mapper.CustomerPrivacyMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisCustomerPrivacyRepository implements CustomerPrivacyRepository {

    private final CustomerPrivacyMapper mapper;

    public MybatisCustomerPrivacyRepository(CustomerPrivacyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertRequest(CustomerPrivacyRequest request) {
        mapper.insertRequest(request);
    }

    @Override
    public int backfillRequestNo(long tenantId, long id, String requestNo) {
        return mapper.backfillRequestNo(tenantId, id, requestNo);
    }

    @Override
    public Optional<CustomerPrivacyRequest> findRequestById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectRequestById(tenantId, id));
    }

    @Override
    public Optional<CustomerPrivacyRequest> findRequestByIdForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectRequestByIdForUpdate(tenantId, id));
    }

    @Override
    public Optional<RequestRow> findRequestRowById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectRequestRowById(tenantId, id));
    }

    @Override
    public boolean existsPendingForApplicantAndCustomer(long tenantId, long applicantUserId, long customerId) {
        return mapper.countPendingForApplicantAndCustomer(tenantId, applicantUserId, customerId) > 0;
    }

    @Override
    public Optional<RequestRow> findLatestPendingForApplicantAndCustomer(long tenantId, long applicantUserId,
                                                                         long customerId) {
        return Optional.ofNullable(
                mapper.selectLatestPendingForApplicantAndCustomer(tenantId, applicantUserId, customerId));
    }

    @Override
    public Optional<CustomerPrivacyRequest> findLatestActiveGrant(long tenantId, long applicantUserId,
                                                                  long customerId, Instant now) {
        return Optional.ofNullable(mapper.selectLatestActiveGrant(tenantId, applicantUserId, customerId, now));
    }

    @Override
    public Optional<RequestRow> findLatestActiveGrantRow(long tenantId, long applicantUserId,
                                                         long customerId, Instant now) {
        return Optional.ofNullable(mapper.selectLatestActiveGrantRow(tenantId, applicantUserId, customerId, now));
    }

    @Override
    public int approveRequest(long tenantId, long id, long expectedVersion, String approvedFieldsJson,
                              int approvedDurationMinutes, long reviewedBy, Instant reviewedAt, String reviewReason,
                              Instant grantStartsAt, Instant grantExpiresAt) {
        return mapper.approveRequest(tenantId, id, expectedVersion, approvedFieldsJson, approvedDurationMinutes,
                reviewedBy, reviewedAt, reviewReason, grantStartsAt, grantExpiresAt);
    }

    @Override
    public int rejectRequest(long tenantId, long id, long expectedVersion, long reviewedBy, Instant reviewedAt,
                             String reviewReason) {
        return mapper.rejectRequest(tenantId, id, expectedVersion, reviewedBy, reviewedAt, reviewReason);
    }

    @Override
    public int cancelRequest(long tenantId, long id, long expectedVersion) {
        return mapper.cancelRequest(tenantId, id, expectedVersion);
    }

    @Override
    public int revokeRequest(long tenantId, long id, long expectedVersion, long revokedBy, Instant revokedAt,
                             String revokeReason) {
        return mapper.revokeRequest(tenantId, id, expectedVersion, revokedBy, revokedAt, revokeReason);
    }

    @Override
    public int recordRevealHit(long tenantId, long id, Instant revealedAt) {
        return mapper.recordRevealHit(tenantId, id, revealedAt);
    }

    @Override
    public List<RequestRow> searchRequests(long tenantId, String viewMode, boolean adminAll, String scopeType,
                                           List<Long> teamIds, long actorUserId, String status, String keyword,
                                           Long customerId, int offset, int size) {
        return mapper.searchRequests(tenantId, viewMode, adminAll, scopeType, teamIds, actorUserId,
                status, keyword, customerId, offset, size);
    }

    @Override
    public long countRequests(long tenantId, String viewMode, boolean adminAll, String scopeType,
                              List<Long> teamIds, long actorUserId, String status, String keyword, Long customerId) {
        return mapper.countRequests(tenantId, viewMode, adminAll, scopeType, teamIds, actorUserId,
                status, keyword, customerId);
    }

    @Override
    public void insertAccessLog(CustomerPrivacyAccessLog accessLog) {
        mapper.insertAccessLog(accessLog);
    }

    @Override
    public List<AccessLogRow> searchAccessLogs(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                               Long customerId, Long actorUserId, String accessMode,
                                               int offset, int size) {
        return mapper.searchAccessLogs(tenantId, scopeType, teamIds, selfId, customerId, actorUserId,
                accessMode, offset, size);
    }

    @Override
    public long countAccessLogs(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                Long customerId, Long actorUserId, String accessMode) {
        return mapper.countAccessLogs(tenantId, scopeType, teamIds, selfId, customerId, actorUserId, accessMode);
    }
}
