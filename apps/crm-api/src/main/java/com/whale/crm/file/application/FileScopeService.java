package com.whale.crm.file.application;

import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class FileScopeService {
    private final InstitutionAccessSupport institutionAccess;
    private final DataScopeService dataScopeService;

    public FileScopeService(InstitutionAccessSupport institutionAccess, DataScopeService dataScopeService) {
        this.institutionAccess = institutionAccess;
        this.dataScopeService = dataScopeService;
    }

    public void requireUpload(CurrentUser user, FileClassification classification, Long institutionId) {
        if (classification == FileClassification.EXPORT) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "导出文件只能由服务端导出任务生成");
        }
        requireAnyPermission(user, uploadPermission(classification));
        requireInstitutionScope(user, classification, institutionId, false);
    }

    public void requireDownload(CurrentUser user, FileClassification classification, Long institutionId) {
        if (classification == FileClassification.MATERIAL) {
            requireAnyPermission(user, "material:read", "material:write", "material:review");
        } else {
            requireAnyPermission(user, downloadPermission(classification));
        }
        requireInstitutionScope(user, classification, institutionId, true);
    }

    private String[] uploadPermission(FileClassification classification) {
        return switch (classification) {
            case CONTRACT, QUALIFICATION, CHANGE_EVIDENCE -> new String[]{"institution:write", "institution:change_request"};
            case PAYMENT -> new String[]{"order:confirm_payment"};
            case REDEMPTION -> new String[]{"redemption:post"};
            case MATERIAL -> new String[]{"material:write"};
            case MERCHANT_RESOURCE -> new String[]{"institution:write"};
            case EXPORT -> new String[0];
        };
    }

    private String downloadPermission(FileClassification classification) {
        return switch (classification) {
            case CONTRACT, QUALIFICATION, CHANGE_EVIDENCE -> "institution:read";
            case PAYMENT -> "order:read";
            case REDEMPTION -> "redemption:read";
            case MATERIAL -> "material:read";
            case MERCHANT_RESOURCE -> "institution:read";
            case EXPORT -> "export:read";
        };
    }

    private void requireAnyPermission(CurrentUser user, String... permissions) {
        for (String permission : permissions) {
            if (user.permissions().contains(permission)) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "没有该文件类别的操作权限");
    }

    private void requireAnyPermission(CurrentUser user, String permission) {
        if (!user.permissions().contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有该文件类别的读取权限");
        }
    }

    private void requireInstitutionScope(CurrentUser user, FileClassification classification,
                                         Long institutionId, boolean readOperation) {
        if (classification == FileClassification.PAYMENT) {
            requireAnyPermission(user, "order:confirm_payment");
            if (user.isInstitutionUser()
                    || dataScopeService.scopeFor(user, DataScopeService.RESOURCE_CUSTOMER).type()
                    != DataScope.ScopeType.ALL) {
                throw scopeDenied(readOperation);
            }
        }
        if (classification == FileClassification.EXPORT) {
            if (institutionId != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "导出文件不使用机构文件范围");
            }
            if (user.isInstitutionUser()) {
                throw scopeDenied(readOperation);
            }
            return;
        }
        if (institutionId == null) {
            if (classification == FileClassification.PAYMENT) {
                return;
            }
            if (classification != FileClassification.MATERIAL) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该文件类别必须指定机构");
            }
            if (user.isInstitutionUser()) {
                throw scopeDenied(readOperation);
            }
            DataScope scope = dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION);
            if (scope.type() != DataScope.ScopeType.ALL) {
                throw scopeDenied(readOperation);
            }
            return;
        }
        if (user.isInstitutionUser()) {
            if (!institutionAccess.canAccess(user.tenantId(), user, institutionId)) {
                throw scopeDenied(readOperation);
            }
            return;
        }
        DataScope scope = dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION);
        if (scope.type() != DataScope.ScopeType.ALL) {
            throw scopeDenied(readOperation);
        }
    }

    private BusinessException scopeDenied(boolean readOperation) {
        return readOperation ? new BusinessException(ErrorCode.NOT_FOUND, "文件不存在或不在数据范围内")
                : new BusinessException(ErrorCode.FORBIDDEN, "文件机构不在数据范围内");
    }
}
