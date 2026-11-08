package com.whale.crm.material.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MaterialRepository {
    void insertMaterial(Material material);

    void insertRevision(MaterialRevision revision);

    Optional<Material> findInsertedMaterial(long tenantId, String materialNo, long createdBy);

    Optional<MaterialRevision> findInsertedRevision(long tenantId, long materialId, int revisionNo, long createdBy);

    List<MaterialListRow> search(long tenantId, MaterialScopeQuery scope, MaterialSearchCriteria criteria,
                                 int offset, int size);

    long count(long tenantId, MaterialScopeQuery scope, MaterialSearchCriteria criteria);

    Optional<Material> findScopedMaterial(long tenantId, long materialId, MaterialScopeQuery scope);

    Optional<Material> findScopedMaterialForUpdate(long tenantId, long materialId, MaterialScopeQuery scope);

    Optional<MaterialRevision> findScopedRevision(long tenantId, long materialId, long revisionId,
                                                  MaterialScopeQuery scope);

    Optional<MaterialRevision> findScopedRevisionForUpdate(long tenantId, long materialId, long revisionId,
                                                           MaterialScopeQuery scope);

    Optional<MaterialRevision> findLatestScopedRevisionForUpdate(long tenantId, long materialId,
                                                                 MaterialScopeQuery scope);

    List<MaterialRevision> findScopedRevisions(long tenantId, long materialId, MaterialScopeQuery scope);

    int updateDraft(MaterialRevision revision, MaterialScopeQuery scope);

    int updateMaterialContent(long tenantId, long materialId, long expectedVersion, MaterialRevision revision,
                              MaterialScopeQuery scope);

    int submitRevision(long tenantId, long revisionId, long materialId, long expectedVersion,
                       MaterialScopeQuery scope);

    int submitMaterial(long tenantId, long materialId, long expectedVersion, MaterialScopeQuery scope);

    int reviewRevision(long tenantId, long revisionId, long materialId, long expectedVersion, String decision,
                       long reviewerUserId, String reviewNote, Instant reviewedAt, MaterialScopeQuery scope);

    int reviewMaterial(long tenantId, long materialId, long expectedVersion, String decision, long reviewerUserId,
                       Long approvedRevisionId, MaterialScopeQuery scope);

    int offlineMaterial(long tenantId, long materialId, long expectedVersion, long offlineBy, String offlineReason,
                        Instant offlineAt, MaterialScopeQuery scope);
}
