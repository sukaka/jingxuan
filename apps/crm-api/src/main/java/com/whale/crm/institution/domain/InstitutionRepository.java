package com.whale.crm.institution.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InstitutionRepository {
    void insertProfile(InstitutionProfile profile);
    Optional<InstitutionProfile> findProfileByNo(long tenantId, String institutionNo);
    Optional<InstitutionProfile> findProfile(long tenantId, long id);
    Optional<InstitutionProfile> findProfileForUpdate(long tenantId, long id);
    List<InstitutionProfile> searchProfiles(long tenantId, InstitutionScope scope, String keyword,
                                            String merchantKeyword, String status, String cityCode, int offset, int size);
    long countProfiles(long tenantId, InstitutionScope scope, String keyword,
                       String merchantKeyword, String status, String cityCode);
    int updateProfileOrdinary(long tenantId, long id, long version, OrdinaryProfileValues values);
    int updateProfileStatus(long tenantId, long id, long version, String fromStatus, String toStatus);
    int applyProfileField(long tenantId, long id, long version, String fieldCode, String value);
    int touchInstitution(long tenantId, long id, long version);

    void insertContract(InstitutionContract contract);
    Optional<InstitutionContract> findContractByKey(long tenantId, String contractNo, int versionNo);
    Optional<InstitutionContract> findContract(long tenantId, long id);
    Optional<InstitutionContract> findContractForUpdate(long tenantId, long id);
    List<InstitutionContract> searchContracts(long tenantId, InstitutionScope scope, long institutionId,
                                              String status, int offset, int size);
    long countContracts(long tenantId, InstitutionScope scope, long institutionId, String status);
    int updateContractStatus(long tenantId, long id, long version, String fromStatus, String toStatus);

    void insertQualification(InstitutionQualification qualification);
    Optional<InstitutionQualification> findQualification(long tenantId, long id);
    Optional<InstitutionQualification> findQualificationForUpdate(long tenantId, long id);
    Optional<InstitutionQualification> findLatestQualification(long tenantId, long institutionId, long fileId, String title);
    List<InstitutionQualification> searchQualifications(long tenantId, InstitutionScope scope, long institutionId,
                                                        String status, int offset, int size);
    long countQualifications(long tenantId, InstitutionScope scope, long institutionId, String status);
    int updateQualificationStatus(long tenantId, long id, long version, String fromStatus, String toStatus,
                                  long reviewerId, Instant reviewedAt);

    void insertChange(ProfileChangeRequest request);
    Optional<ProfileChangeRequest> findChange(long tenantId, long id);
    Optional<ProfileChangeRequest> findChangeForUpdate(long tenantId, long id);
    Optional<ProfileChangeRequest> findActiveChange(long tenantId, String activeTarget);
    List<ProfileChangeRequest> searchChanges(long tenantId, InstitutionScope scope, Long institutionId,
                                             String status, int offset, int size);
    long countChanges(long tenantId, InstitutionScope scope, Long institutionId, String status);
    int updateChangeStatus(long tenantId, long id, long version, String fromStatus, String toStatus,
                           String currentStep, Instant completedAt, String activeTarget);
    int rebaseChange(long tenantId, long id, long version, long baseVersion, long targetVersion,
                     String currentStep, String activeTarget);
    void insertStep(ProfileChangeStep step);
    List<ProfileChangeStep> findSteps(long tenantId, long changeRequestId);
}
