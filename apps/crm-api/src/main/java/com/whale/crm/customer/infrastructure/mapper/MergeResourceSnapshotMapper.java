package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.MergeResourceSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MergeResourceSnapshotMapper {
    @Insert("INSERT INTO crm_merge_resource_snapshot (tenant_id,merge_case_id,resource_type,resource_key) VALUES (#{tenantId},#{p.mergeCaseId},#{p.resourceType},#{p.resourceKey})")
    void insert(@Param("tenantId") long tenantId, @Param("p") MergeResourceSnapshot snapshot);

    @Select("SELECT id,merge_case_id AS mergeCaseId,resource_type AS resourceType,resource_key AS resourceKey FROM crm_merge_resource_snapshot WHERE tenant_id=#{tenantId} AND merge_case_id=#{mergeCaseId} ORDER BY resource_type,resource_key")
    List<MergeResourceSnapshot> selectByMergeCaseId(@Param("tenantId") long tenantId, @Param("mergeCaseId") long mergeCaseId);
}
