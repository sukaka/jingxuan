package com.whale.crm.institution.application;

import com.whale.crm.file.domain.FileClassification;

public interface InstitutionFileOperations {
    void requireClean(long tenantId, long institutionId, long fileId, FileClassification expectedClassification);

    default void assertMerchantResourceClean(long tenantId, long fileId, long institutionId) {
        requireClean(tenantId, institutionId, fileId, FileClassification.MERCHANT_RESOURCE);
    }
}
