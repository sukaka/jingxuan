package com.whale.crm.file.application;

import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.institution.application.InstitutionFileOperations;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class FileInstitutionOperations implements InstitutionFileOperations {
    private final FileApplicationService fileService;

    public FileInstitutionOperations(FileApplicationService fileService) {
        this.fileService = fileService;
    }

    @Override
    public void assertClean(long tenantId, long fileId) {
        FileMetadata metadata = fileService.requireCleanUsable(tenantId, SecuritySupport.requireCurrentUser(),
                fileId, (FileClassification) null, null);
        FileClassification classification = FileClassification.valueOf(metadata.classification());
        if (classification != FileClassification.CONTRACT && classification != FileClassification.QUALIFICATION
                && classification != FileClassification.CHANGE_EVIDENCE) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构文档类别不匹配");
        }
    }

    @Override
    public void assertMerchantResourceClean(long tenantId, long fileId, long institutionId) {
        fileService.requireCleanUsable(tenantId, SecuritySupport.requireCurrentUser(), fileId,
                FileClassification.MERCHANT_RESOURCE, institutionId);
    }
}
