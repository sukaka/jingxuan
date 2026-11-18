package com.whale.crm.complaint.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ComplaintRepository {
    Optional<ComplaintPolicy> findPolicy(long tenantId);

    Optional<ComplaintPolicy> findPolicyForUpdate(long tenantId);

    int updatePolicy(long tenantId, long version, boolean enabled, Long fallbackRecipientUserId, String empathyScript);

    List<ComplaintKeyword> findKeywords(long tenantId);

    List<ComplaintKeyword> findActiveKeywords(long tenantId);

    Optional<ComplaintKeyword> findKeywordById(long tenantId, long id);

    Optional<ComplaintKeyword> findKeywordByIdForUpdate(long tenantId, long id);

    Optional<ComplaintKeyword> findKeywordByText(long tenantId, String keyword);

    void insertKeyword(ComplaintKeyword keyword);

    int updateKeyword(long tenantId, long id, long version, String keyword);

    int disableKeyword(long tenantId, long id, long version);

    Optional<ComplaintHit> findHitById(long tenantId, long id);

    Optional<ComplaintHit> findHitByIdForUpdate(long tenantId, long id);

    Optional<ComplaintHit> findHitBySource(long tenantId, String sourceType, String sourceId, long keywordId);

    List<ComplaintHit> findHitsBySource(long tenantId, String sourceType, String sourceId);

    List<ComplaintHit> searchHits(ComplaintHitQuery query);

    long countHits(ComplaintHitQuery query);

    int insertHit(ComplaintHit hit);

    int updateHitReview(long tenantId, long id, long version, String targetStatus, Long caseId,
                        String reasonMasked, long actorUserId, Instant reviewedAt);

    void insertHitHistory(ComplaintHitHistory history);

    List<ComplaintHitHistory> findHitHistory(long tenantId, long hitId);

    Optional<ComplaintCase> findCaseById(long tenantId, long id);

    Optional<ComplaintCase> findCaseByIdForUpdate(long tenantId, long id);

    Optional<ComplaintCase> findCaseByHitId(long tenantId, long hitId);

    List<ComplaintCase> searchCases(ComplaintCaseQuery query);

    long countCases(ComplaintCaseQuery query);

    void insertCase(ComplaintCase complaintCase);

    int updateCaseStatus(long tenantId, long id, long version, String expectedStatus, String targetStatus,
                         Long assigneeUserId, String resolutionMasked, Instant resolvedAt, Instant closedAt,
                         String dismissReasonMasked);

    void insertCaseHistory(ComplaintCaseHistory history);

    List<ComplaintCaseHistory> findCaseHistory(long tenantId, long caseId);

    Long findActiveTeamLeaderId(long tenantId, long teamId);
}
