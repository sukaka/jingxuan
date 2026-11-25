package com.whale.crm.complaint.infrastructure.mapper;

import com.whale.crm.complaint.domain.ComplaintCase;
import com.whale.crm.complaint.domain.ComplaintCaseHistory;
import com.whale.crm.complaint.domain.ComplaintCaseQuery;
import com.whale.crm.complaint.domain.ComplaintHit;
import com.whale.crm.complaint.domain.ComplaintHitHistory;
import com.whale.crm.complaint.domain.ComplaintHitQuery;
import com.whale.crm.complaint.domain.ComplaintKeyword;
import com.whale.crm.complaint.domain.ComplaintPolicy;
import com.whale.crm.complaint.domain.ComplaintRepository;
import com.whale.crm.complaint.domain.ComplaintTimelineQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ComplaintMapper extends ComplaintRepository {
    @Override
    Optional<ComplaintPolicy> findPolicy(@Param("tenantId") long tenantId);

    @Override
    Optional<ComplaintPolicy> findPolicyForUpdate(@Param("tenantId") long tenantId);

    @Override
    int updatePolicy(@Param("tenantId") long tenantId, @Param("version") long version,
                     @Param("enabled") boolean enabled, @Param("fallbackRecipientUserId") Long fallbackRecipientUserId,
                     @Param("empathyScript") String empathyScript);

    @Override
    List<ComplaintKeyword> findKeywords(@Param("tenantId") long tenantId);

    @Override
    List<ComplaintKeyword> findActiveKeywords(@Param("tenantId") long tenantId);

    @Override
    Optional<ComplaintKeyword> findKeywordById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<ComplaintKeyword> findKeywordByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<ComplaintKeyword> findKeywordByText(@Param("tenantId") long tenantId, @Param("keyword") String keyword);

    @Override
    void insertKeyword(@Param("e") ComplaintKeyword keyword);

    @Override
    int updateKeyword(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                      @Param("keyword") String keyword);

    @Override
    int disableKeyword(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version);

    @Override
    Optional<ComplaintHit> findHitById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<ComplaintHit> findHitByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<ComplaintHit> findHitBySource(@Param("tenantId") long tenantId, @Param("sourceType") String sourceType,
                                           @Param("sourceId") String sourceId, @Param("keywordId") long keywordId);

    @Override
    List<ComplaintHit> findHitsBySource(@Param("tenantId") long tenantId, @Param("sourceType") String sourceType,
                                        @Param("sourceId") String sourceId);

    @Override
    List<ComplaintHit> searchHits(@Param("q") ComplaintHitQuery query);

    @Override
    long countHits(@Param("q") ComplaintHitQuery query);

    @Override
    int insertHit(@Param("e") ComplaintHit hit);

    @Override
    int updateHitReview(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                        @Param("targetStatus") String targetStatus, @Param("caseId") Long caseId,
                        @Param("reasonMasked") String reasonMasked, @Param("actorUserId") long actorUserId,
                        @Param("reviewedAt") Instant reviewedAt);

    @Override
    void insertHitHistory(@Param("e") ComplaintHitHistory history);

    @Override
    List<ComplaintHitHistory> findHitHistory(@Param("tenantId") long tenantId, @Param("hitId") long hitId);

    @Override
    Optional<ComplaintCase> findCaseById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<ComplaintCase> findCaseByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<ComplaintCase> findCaseByHitId(@Param("tenantId") long tenantId, @Param("hitId") long hitId);

    @Override
    List<ComplaintCase> searchCases(@Param("q") ComplaintCaseQuery query);

    @Override
    long countCases(@Param("q") ComplaintCaseQuery query);

    @Override
    List<ComplaintCase> findTimelineCases(@Param("q") ComplaintTimelineQuery query);

    @Override
    void insertCase(@Param("e") ComplaintCase complaintCase);

    @Override
    int updateCaseStatus(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                         @Param("expectedStatus") String expectedStatus, @Param("targetStatus") String targetStatus,
                         @Param("assigneeUserId") Long assigneeUserId,
                         @Param("resolutionMasked") String resolutionMasked, @Param("resolvedAt") Instant resolvedAt,
                         @Param("closedAt") Instant closedAt, @Param("dismissReasonMasked") String dismissReasonMasked);

    @Override
    void insertCaseHistory(@Param("e") ComplaintCaseHistory history);

    @Override
    List<ComplaintCaseHistory> findCaseHistory(@Param("tenantId") long tenantId, @Param("caseId") long caseId);

    @Override
    Long findActiveTeamLeaderId(@Param("tenantId") long tenantId, @Param("teamId") long teamId);
}
