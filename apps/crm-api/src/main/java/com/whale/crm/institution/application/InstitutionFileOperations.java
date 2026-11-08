package com.whale.crm.institution.application;

public interface InstitutionFileOperations {
    void assertClean(long tenantId, long fileId);
    default void assertMerchantResourceClean(long tenantId, long fileId, long institutionId) {
        throw new com.whale.crm.platform.web.BusinessException(
                com.whale.crm.platform.web.ErrorCode.PENDING_BUSINESS_RULE, "机构展示附件校验尚未就绪");
    }
}
