package com.whale.crm.material.infrastructure;

import com.whale.crm.material.domain.Material;
import com.whale.crm.material.domain.MaterialListRow;
import com.whale.crm.material.domain.MaterialRepository;
import com.whale.crm.material.domain.MaterialRevision;
import com.whale.crm.material.domain.MaterialScopeQuery;
import com.whale.crm.material.domain.MaterialSearchCriteria;
import com.whale.crm.material.infrastructure.mapper.MaterialMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisMaterialRepository implements MaterialRepository {
    private final MaterialMapper mapper;

    public MybatisMaterialRepository(MaterialMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertMaterial(Material material) {
        mapper.insertMaterial(material);
    }

    @Override
    public void insertRevision(MaterialRevision revision) {
        mapper.insertRevision(revision);
    }

    @Override
    public Optional<Material> findInsertedMaterial(long tenantId, String materialNo, long createdBy) {
        return Optional.ofNullable(mapper.selectInsertedMaterial(tenantId, materialNo, createdBy));
    }

    @Override
    public Optional<MaterialRevision> findInsertedRevision(long tenantId, long materialId, int revisionNo,
                                                           long createdBy) {
        return Optional.ofNullable(mapper.selectInsertedRevision(tenantId, materialId, revisionNo, createdBy));
    }

    @Override
    public List<MaterialListRow> search(long tenantId, MaterialScopeQuery scope, MaterialSearchCriteria criteria,
                                        int offset, int size) {
        return mapper.search(tenantId, scope, criteria, offset, size);
    }

    @Override
    public long count(long tenantId, MaterialScopeQuery scope, MaterialSearchCriteria criteria) {
        return mapper.count(tenantId, scope, criteria);
    }

    @Override
    public Optional<Material> findScopedMaterial(long tenantId, long materialId, MaterialScopeQuery scope) {
        return Optional.ofNullable(mapper.selectScopedMaterial(tenantId, materialId, scope));
    }

    @Override
    public Optional<Material> findScopedMaterialForUpdate(long tenantId, long materialId, MaterialScopeQuery scope) {
        return Optional.ofNullable(mapper.selectScopedMaterialForUpdate(tenantId, materialId, scope));
    }

    @Override
    public Optional<MaterialRevision> findScopedRevision(long tenantId, long materialId, long revisionId,
                                                         MaterialScopeQuery scope) {
        return Optional.ofNullable(mapper.selectScopedRevision(tenantId, materialId, revisionId, scope));
    }

    @Override
    public Optional<MaterialRevision> findScopedRevisionForUpdate(long tenantId, long materialId, long revisionId,
                                                                  MaterialScopeQuery scope) {
        return Optional.ofNullable(mapper.selectScopedRevisionForUpdate(tenantId, materialId, revisionId, scope));
    }

    @Override
    public Optional<MaterialRevision> findLatestScopedRevisionForUpdate(long tenantId, long materialId,
                                                                        MaterialScopeQuery scope) {
        return Optional.ofNullable(mapper.selectLatestScopedRevisionForUpdate(tenantId, materialId, scope));
    }

    @Override
    public List<MaterialRevision> findScopedRevisions(long tenantId, long materialId, MaterialScopeQuery scope) {
        return mapper.selectScopedRevisions(tenantId, materialId, scope);
    }

    @Override
    public int updateDraft(MaterialRevision revision, MaterialScopeQuery scope) {
        return mapper.updateDraft(revision, scope);
    }

    @Override
    public int updateMaterialContent(long tenantId, long materialId, long expectedVersion, MaterialRevision revision,
                                     MaterialScopeQuery scope) {
        return mapper.updateMaterialContent(tenantId, materialId, expectedVersion, revision, scope);
    }

    @Override
    public int submitRevision(long tenantId, long revisionId, long materialId, long expectedVersion,
                              MaterialScopeQuery scope) {
        return mapper.submitRevision(tenantId, revisionId, materialId, expectedVersion, scope);
    }

    @Override
    public int submitMaterial(long tenantId, long materialId, long expectedVersion, MaterialScopeQuery scope) {
        return mapper.submitMaterial(tenantId, materialId, expectedVersion, scope);
    }

    @Override
    public int reviewRevision(long tenantId, long revisionId, long materialId, long expectedVersion, String decision,
                              long reviewerUserId, String reviewNote, Instant reviewedAt, MaterialScopeQuery scope) {
        return mapper.reviewRevision(tenantId, revisionId, materialId, expectedVersion, decision, reviewerUserId,
                reviewNote, reviewedAt, scope);
    }

    @Override
    public int reviewMaterial(long tenantId, long materialId, long expectedVersion, String decision,
                              long reviewerUserId, Long approvedRevisionId, MaterialScopeQuery scope) {
        return mapper.reviewMaterial(tenantId, materialId, expectedVersion, decision, reviewerUserId,
                approvedRevisionId, scope);
    }

    @Override
    public int offlineMaterial(long tenantId, long materialId, long expectedVersion, long offlineBy,
                               String offlineReason, Instant offlineAt, MaterialScopeQuery scope) {
        return mapper.offlineMaterial(tenantId, materialId, expectedVersion, offlineBy, offlineReason, offlineAt, scope);
    }
}
