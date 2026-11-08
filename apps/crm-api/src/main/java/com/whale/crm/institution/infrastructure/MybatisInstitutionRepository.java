package com.whale.crm.institution.infrastructure;

import com.whale.crm.institution.domain.InstitutionContract;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionQualification;
import com.whale.crm.institution.domain.InstitutionRepository;
import com.whale.crm.institution.domain.InstitutionScope;
import com.whale.crm.institution.domain.OrdinaryProfileValues;
import com.whale.crm.institution.domain.ProfileChangeRequest;
import com.whale.crm.institution.domain.ProfileChangeStep;
import com.whale.crm.institution.infrastructure.mapper.InstitutionMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisInstitutionRepository implements InstitutionRepository {
    private final InstitutionMapper mapper;

    public MybatisInstitutionRepository(InstitutionMapper mapper) {
        this.mapper = mapper;
    }

    public void insertProfile(InstitutionProfile e) { mapper.insertProfile(com.whale.crm.platform.context.TenantContext.currentTenantId(), e); }
    public Optional<InstitutionProfile> findProfileByNo(long tenantId, String no) { return Optional.ofNullable(mapper.selectProfileByNo(tenantId, no)); }
    public Optional<InstitutionProfile> findProfile(long tenantId, long id) { return Optional.ofNullable(mapper.selectProfile(tenantId, id)); }
    public Optional<InstitutionProfile> findProfileForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectProfileForUpdate(tenantId, id)); }
    public List<InstitutionProfile> searchProfiles(long tenantId, InstitutionScope scope, String keyword,
                                                   String merchantKeyword, String status, String cityCode,
                                                   int offset, int size) {
        return mapper.searchProfiles(tenantId, scope, keyword, merchantKeyword, status, cityCode, offset, size);
    }
    public long countProfiles(long tenantId, InstitutionScope scope, String keyword,
                              String merchantKeyword, String status, String cityCode) {
        return mapper.countProfiles(tenantId, scope, keyword, merchantKeyword, status, cityCode);
    }
    public int updateProfileOrdinary(long tenantId, long id, long version, OrdinaryProfileValues values) { return mapper.updateProfileOrdinary(tenantId, id, version, values); }
    public int updateProfileStatus(long tenantId, long id, long version, String from, String to) { return mapper.updateProfileStatus(tenantId, id, version, from, to); }
    public int applyProfileField(long tenantId, long id, long version, String fieldCode, String value) { return mapper.applyProfileField(tenantId, id, version, fieldCode, value); }
    public int touchInstitution(long tenantId, long id, long version) { return mapper.touchInstitution(tenantId, id, version); }

    public void insertContract(InstitutionContract e) { mapper.insertContract(com.whale.crm.platform.context.TenantContext.currentTenantId(), e); }
    public Optional<InstitutionContract> findContractByKey(long tenantId, String no, int version) { return Optional.ofNullable(mapper.selectContractByKey(tenantId, no, version)); }
    public Optional<InstitutionContract> findContract(long tenantId, long id) { return Optional.ofNullable(mapper.selectContract(tenantId, id)); }
    public Optional<InstitutionContract> findContractForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectContractForUpdate(tenantId, id)); }
    public List<InstitutionContract> searchContracts(long tenantId, InstitutionScope scope, long institutionId,
                                                     String status, int offset, int size) {
        return mapper.searchContracts(tenantId, scope, institutionId, status, offset, size);
    }
    public long countContracts(long tenantId, InstitutionScope scope, long institutionId, String status) { return mapper.countContracts(tenantId, scope, institutionId, status); }
    public int updateContractStatus(long tenantId, long id, long version, String from, String to) { return mapper.updateContractStatus(tenantId, id, version, from, to); }

    public void insertQualification(InstitutionQualification e) { mapper.insertQualification(com.whale.crm.platform.context.TenantContext.currentTenantId(), e); }
    public Optional<InstitutionQualification> findQualification(long tenantId, long id) { return Optional.ofNullable(mapper.selectQualification(tenantId, id)); }
    public Optional<InstitutionQualification> findQualificationForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectQualificationForUpdate(tenantId, id)); }
    public Optional<InstitutionQualification> findLatestQualification(long tenantId, long institutionId, long fileId, String title) {
        return Optional.ofNullable(mapper.selectLatestQualification(tenantId, institutionId, fileId, title));
    }
    public List<InstitutionQualification> searchQualifications(long tenantId, InstitutionScope scope, long institutionId,
                                                               String status, int offset, int size) {
        return mapper.searchQualifications(tenantId, scope, institutionId, status, offset, size);
    }
    public long countQualifications(long tenantId, InstitutionScope scope, long institutionId, String status) { return mapper.countQualifications(tenantId, scope, institutionId, status); }
    public int updateQualificationStatus(long tenantId, long id, long version, String from, String to,
                                         long reviewerId, Instant reviewedAt) {
        return mapper.updateQualificationStatus(tenantId, id, version, from, to, reviewerId, reviewedAt);
    }

    public void insertChange(ProfileChangeRequest e) { mapper.insertChange(com.whale.crm.platform.context.TenantContext.currentTenantId(), e); }
    public Optional<ProfileChangeRequest> findChange(long tenantId, long id) { return Optional.ofNullable(mapper.selectChange(tenantId, id)); }
    public Optional<ProfileChangeRequest> findChangeForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectChangeForUpdate(tenantId, id)); }
    public Optional<ProfileChangeRequest> findActiveChange(long tenantId, String target) { return Optional.ofNullable(mapper.selectActiveChange(tenantId, target)); }
    public List<ProfileChangeRequest> searchChanges(long tenantId, InstitutionScope scope, Long institutionId,
                                                    String status, int offset, int size) {
        return mapper.searchChanges(tenantId, scope, institutionId, status, offset, size);
    }
    public long countChanges(long tenantId, InstitutionScope scope, Long institutionId, String status) { return mapper.countChanges(tenantId, scope, institutionId, status); }
    public int updateChangeStatus(long tenantId, long id, long version, String from, String to,
                                  String currentStep, Instant completedAt, String activeTarget) {
        return mapper.updateChangeStatus(tenantId, id, version, from, to, currentStep, completedAt, activeTarget);
    }
    public int rebaseChange(long tenantId, long id, long version, long baseVersion, long targetVersion,
                            String currentStep, String activeTarget) {
        return mapper.rebaseChange(tenantId, id, version, baseVersion, targetVersion, currentStep, activeTarget);
    }
    public void insertStep(ProfileChangeStep e) { mapper.insertStep(com.whale.crm.platform.context.TenantContext.currentTenantId(), e); }
    public List<ProfileChangeStep> findSteps(long tenantId, long id) { return mapper.selectSteps(tenantId, id); }
}
