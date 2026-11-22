package com.whale.crm.file.application;

import com.whale.crm.file.domain.FileClassification;
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
    public void requireClean(long tenantId, long institutionId, long fileId,
                             FileClassification expectedClassification) {
        if (institutionId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "机构ID无效");
        }
        if (expectedClassification != FileClassification.CONTRACT
                && expectedClassification != FileClassification.QUALIFICATION
                && expectedClassification != FileClassification.CHANGE_EVIDENCE
                && expectedClassification != FileClassification.MERCHANT_RESOURCE) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构文档类别不匹配");
        }
        fileService.requireCleanUsable(tenantId, SecuritySupport.requireCurrentUser(), fileId,
                expectedClassification, institutionId);
    }
}
