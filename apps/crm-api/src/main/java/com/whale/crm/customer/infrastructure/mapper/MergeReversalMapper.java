package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.MergeReversal;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface MergeReversalMapper {
    String COLUMNS = "id,tenant_id AS tenantId,merge_case_id AS mergeCaseId,status,reason,review_reason AS reviewReason,primary_version AS primaryVersion,secondary_version AS secondaryVersion,requested_by AS requestedBy,requested_at AS requestedAt,reviewed_by AS reviewedBy,reviewed_at AS reviewedAt,executed_at AS executedAt,warning_message AS warningMessage,error_masked AS errorMasked,version";

    @Insert("INSERT INTO crm_merge_reversal (tenant_id,merge_case_id,status,reason,review_reason,primary_version,secondary_version,requested_by,requested_at,reviewed_by,reviewed_at,executed_at,warning_message,error_masked) "
            + "VALUES (#{tenantId},#{p.mergeCaseId},#{p.status},#{p.reason},#{p.reviewReason},#{p.primaryVersion},#{p.secondaryVersion},#{p.requestedBy},#{p.requestedAt},#{p.reviewedBy},#{p.reviewedAt},#{p.executedAt},#{p.warningMessage},#{p.errorMasked})")
    void insert(@Param("tenantId") long tenantId, @Param("p") MergeReversal reversal);

    @Select("SELECT " + COLUMNS + " FROM crm_merge_reversal WHERE tenant_id=#{tenantId} AND id=#{id}")
    MergeReversal select(@Param("tenantId") long tenantId, @Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM crm_merge_reversal WHERE tenant_id=#{tenantId} AND id=#{id} FOR UPDATE")
    MergeReversal selectForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM crm_merge_reversal WHERE tenant_id=#{tenantId} AND merge_case_id=#{mergeCaseId}")
    MergeReversal selectByMergeCaseId(@Param("tenantId") long tenantId, @Param("mergeCaseId") long mergeCaseId);

    @Update("UPDATE crm_merge_reversal SET status=#{status},review_reason=#{reviewReason},reviewed_by=#{reviewedBy},reviewed_at=#{reviewedAt},executed_at=#{executedAt},warning_message=#{warningMessage},error_masked=#{errorMasked},version=version+1 "
            + "WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version}")
    int updateStatus(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                     @Param("status") String status, @Param("reviewReason") String reviewReason,
                     @Param("reviewedBy") long reviewedBy, @Param("reviewedAt") Instant reviewedAt,
                     @Param("executedAt") Instant executedAt, @Param("warningMessage") String warningMessage,
                     @Param("errorMasked") String errorMasked);
}
