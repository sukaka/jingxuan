package com.whale.crm.material.infrastructure.mapper;

import com.whale.crm.material.domain.Material;
import com.whale.crm.material.domain.MaterialListRow;
import com.whale.crm.material.domain.MaterialRevision;
import com.whale.crm.material.domain.MaterialScopeQuery;
import com.whale.crm.material.domain.MaterialSearchCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface MaterialMapper {
    void insertMaterial(@Param("entity") Material entity);

    void insertRevision(@Param("entity") MaterialRevision entity);

    Material selectInsertedMaterial(@Param("tenantId") long tenantId, @Param("materialNo") String materialNo,
                                    @Param("createdBy") long createdBy);

    MaterialRevision selectInsertedRevision(@Param("tenantId") long tenantId, @Param("materialId") long materialId,
                                            @Param("revisionNo") int revisionNo, @Param("createdBy") long createdBy);

    List<MaterialListRow> search(@Param("tenantId") long tenantId, @Param("scope") MaterialScopeQuery scope,
                                 @Param("criteria") MaterialSearchCriteria criteria,
                                 @Param("offset") int offset, @Param("size") int size);

    long count(@Param("tenantId") long tenantId, @Param("scope") MaterialScopeQuery scope,
               @Param("criteria") MaterialSearchCriteria criteria);

    Material selectScopedMaterial(@Param("tenantId") long tenantId, @Param("materialId") long materialId,
                                  @Param("scope") MaterialScopeQuery scope);

    Material selectScopedMaterialForUpdate(@Param("tenantId") long tenantId, @Param("materialId") long materialId,
                                           @Param("scope") MaterialScopeQuery scope);

    MaterialRevision selectScopedRevision(@Param("tenantId") long tenantId, @Param("materialId") long materialId,
                                          @Param("revisionId") long revisionId, @Param("scope") MaterialScopeQuery scope);

    MaterialRevision selectScopedRevisionForUpdate(@Param("tenantId") long tenantId,
                                                   @Param("materialId") long materialId,
                                                   @Param("revisionId") long revisionId,
                                                   @Param("scope") MaterialScopeQuery scope);

    MaterialRevision selectLatestScopedRevisionForUpdate(@Param("tenantId") long tenantId,
                                                         @Param("materialId") long materialId,
                                                         @Param("scope") MaterialScopeQuery scope);

    List<MaterialRevision> selectScopedRevisions(@Param("tenantId") long tenantId,
                                                 @Param("materialId") long materialId,
                                                 @Param("scope") MaterialScopeQuery scope);

    int updateDraft(@Param("entity") MaterialRevision entity, @Param("scope") MaterialScopeQuery scope);

    int updateMaterialContent(@Param("tenantId") long tenantId, @Param("materialId") long materialId,
                              @Param("expectedVersion") long expectedVersion,
                              @Param("entity") MaterialRevision entity, @Param("scope") MaterialScopeQuery scope);

    int submitRevision(@Param("tenantId") long tenantId, @Param("revisionId") long revisionId,
                       @Param("materialId") long materialId, @Param("expectedVersion") long expectedVersion,
                       @Param("scope") MaterialScopeQuery scope);

    int submitMaterial(@Param("tenantId") long tenantId, @Param("materialId") long materialId,
                       @Param("expectedVersion") long expectedVersion, @Param("scope") MaterialScopeQuery scope);

    int reviewRevision(@Param("tenantId") long tenantId, @Param("revisionId") long revisionId,
                       @Param("materialId") long materialId, @Param("expectedVersion") long expectedVersion,
                       @Param("decision") String decision, @Param("reviewerUserId") long reviewerUserId,
                       @Param("reviewNote") String reviewNote, @Param("reviewedAt") Instant reviewedAt,
                       @Param("scope") MaterialScopeQuery scope);

    int reviewMaterial(@Param("tenantId") long tenantId, @Param("materialId") long materialId,
                       @Param("expectedVersion") long expectedVersion, @Param("decision") String decision,
                       @Param("reviewerUserId") long reviewerUserId,
                       @Param("approvedRevisionId") Long approvedRevisionId,
                       @Param("scope") MaterialScopeQuery scope);

    int offlineMaterial(@Param("tenantId") long tenantId, @Param("materialId") long materialId,
                        @Param("expectedVersion") long expectedVersion, @Param("offlineBy") long offlineBy,
                        @Param("offlineReason") String offlineReason, @Param("offlineAt") Instant offlineAt,
                        @Param("scope") MaterialScopeQuery scope);
}
