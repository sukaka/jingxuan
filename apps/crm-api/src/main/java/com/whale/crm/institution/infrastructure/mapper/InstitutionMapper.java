package com.whale.crm.institution.infrastructure.mapper;

import com.whale.crm.institution.domain.InstitutionContract;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionQualification;
import com.whale.crm.institution.domain.InstitutionScope;
import com.whale.crm.institution.domain.OrdinaryProfileValues;
import com.whale.crm.institution.domain.ProfileChangeRequest;
import com.whale.crm.institution.domain.ProfileChangeStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface InstitutionMapper {
    void insertProfile(@Param("tenantId") long tenantId, @Param("entity") InstitutionProfile entity);
    InstitutionProfile selectProfileByNo(@Param("tenantId") long tenantId, @Param("institutionNo") String institutionNo);
    InstitutionProfile selectProfile(@Param("tenantId") long tenantId, @Param("id") long id);
    InstitutionProfile selectProfileForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    List<InstitutionProfile> searchProfiles(@Param("tenantId") long tenantId, @Param("scope") InstitutionScope scope,
                                            @Param("keyword") String keyword, @Param("merchantKeyword") String merchantKeyword,
                                            @Param("status") String status, @Param("cityCode") String cityCode,
                                            @Param("offset") int offset, @Param("size") int size);
    long countProfiles(@Param("tenantId") long tenantId, @Param("scope") InstitutionScope scope,
                       @Param("keyword") String keyword, @Param("merchantKeyword") String merchantKeyword,
                       @Param("status") String status, @Param("cityCode") String cityCode);
    int updateProfileOrdinary(@Param("tenantId") long tenantId, @Param("id") long id,
                              @Param("version") long version, @Param("values") OrdinaryProfileValues values);
    int updateProfileStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                            @Param("version") long version, @Param("fromStatus") String fromStatus,
                            @Param("toStatus") String toStatus);
    int applyProfileField(@Param("tenantId") long tenantId, @Param("id") long id,
                          @Param("version") long version, @Param("fieldCode") String fieldCode,
                          @Param("value") String value);
    int touchInstitution(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version);

    void insertContract(@Param("tenantId") long tenantId, @Param("entity") InstitutionContract entity);
    InstitutionContract selectContractByKey(@Param("tenantId") long tenantId,
                                            @Param("contractNo") String contractNo, @Param("versionNo") int versionNo);
    InstitutionContract selectContract(@Param("tenantId") long tenantId, @Param("id") long id);
    InstitutionContract selectContractForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    List<InstitutionContract> searchContracts(@Param("tenantId") long tenantId, @Param("scope") InstitutionScope scope,
                                              @Param("institutionId") long institutionId, @Param("status") String status,
                                              @Param("offset") int offset, @Param("size") int size);
    long countContracts(@Param("tenantId") long tenantId, @Param("scope") InstitutionScope scope,
                        @Param("institutionId") long institutionId, @Param("status") String status);
    int updateContractStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                             @Param("version") long version, @Param("fromStatus") String fromStatus,
                             @Param("toStatus") String toStatus);

    void insertQualification(@Param("tenantId") long tenantId, @Param("entity") InstitutionQualification entity);
    InstitutionQualification selectQualification(@Param("tenantId") long tenantId, @Param("id") long id);
    InstitutionQualification selectQualificationForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    InstitutionQualification selectLatestQualification(@Param("tenantId") long tenantId,
                                                       @Param("institutionId") long institutionId,
                                                       @Param("fileId") long fileId, @Param("title") String title);
    List<InstitutionQualification> searchQualifications(@Param("tenantId") long tenantId,
                                                        @Param("scope") InstitutionScope scope,
                                                        @Param("institutionId") long institutionId,
                                                        @Param("status") String status,
                                                        @Param("offset") int offset, @Param("size") int size);
    long countQualifications(@Param("tenantId") long tenantId, @Param("scope") InstitutionScope scope,
                             @Param("institutionId") long institutionId, @Param("status") String status);
    int updateQualificationStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                                  @Param("version") long version, @Param("fromStatus") String fromStatus,
                                  @Param("toStatus") String toStatus,
                                  @Param("reviewerId") long reviewerId, @Param("reviewedAt") Instant reviewedAt);

    void insertChange(@Param("tenantId") long tenantId, @Param("entity") ProfileChangeRequest entity);
    ProfileChangeRequest selectChange(@Param("tenantId") long tenantId, @Param("id") long id);
    ProfileChangeRequest selectChangeForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    ProfileChangeRequest selectActiveChange(@Param("tenantId") long tenantId, @Param("activeTarget") String activeTarget);
    List<ProfileChangeRequest> searchChanges(@Param("tenantId") long tenantId, @Param("scope") InstitutionScope scope,
                                             @Param("institutionId") Long institutionId, @Param("status") String status,
                                             @Param("offset") int offset, @Param("size") int size);
    long countChanges(@Param("tenantId") long tenantId, @Param("scope") InstitutionScope scope,
                      @Param("institutionId") Long institutionId, @Param("status") String status);
    int updateChangeStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                           @Param("version") long version, @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus, @Param("currentStep") String currentStep,
                           @Param("completedAt") Instant completedAt, @Param("activeTarget") String activeTarget);
    int rebaseChange(@Param("tenantId") long tenantId, @Param("id") long id,
                     @Param("version") long version, @Param("baseVersion") long baseVersion,
                     @Param("targetVersion") long targetVersion, @Param("currentStep") String currentStep,
                     @Param("activeTarget") String activeTarget);
    void insertStep(@Param("tenantId") long tenantId, @Param("entity") ProfileChangeStep entity);
    List<ProfileChangeStep> selectSteps(@Param("tenantId") long tenantId, @Param("changeRequestId") long changeRequestId);
}
