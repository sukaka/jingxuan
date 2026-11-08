package com.whale.crm.exporting.application;

import com.whale.crm.exporting.api.dto.ExportScopeRequest;
import com.whale.crm.exporting.domain.ExportRestriction;
import com.whale.crm.exporting.domain.ExportScope;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.exporting.domain.StoredExportScope;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ExportScopeService {
    private final DataScopeService dataScopeService;

    public ExportScopeService(DataScopeService dataScopeService) {
        this.dataScopeService = dataScopeService;
    }

    public StoredExportScope requestScope(CurrentUser user, ExportType type, ExportScopeRequest request) {
        ExportScope requested = baseScope(user, type);
        ExportRestriction restriction = restriction(request);
        validateRestriction(type, requested, restriction);
        return new StoredExportScope(requested, null, restriction);
    }

    public ExportScope executionScope(CurrentUser user, ExportType type, StoredExportScope stored) {
        ExportScope current = baseScope(user, type);
        ExportScope effective = stored.requested().intersect(current);
        validateRestriction(type, effective, stored.restriction());
        if (effective.kind() == ExportScope.Kind.NONE) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号已没有该导出任务的数据范围");
        }
        return effective;
    }

    public String visibilityScope(CurrentUser user) {
        DataScope scope = dataScopeService.scopeFor(user, DataScopeService.RESOURCE_CUSTOMER);
        return scope.type() == DataScope.ScopeType.ALL ? "ALL" : "SELF";
    }

    private ExportScope baseScope(CurrentUser user, ExportType type) {
        DataScope scope = dataScopeService.scopeFor(user, DataScopeService.RESOURCE_CUSTOMER);
        if (user.isInstitutionUser() && type != ExportType.REDEMPTION) {
            return ExportScope.none(user.userId());
        }
        ExportScope result = ExportScope.from(scope, user.userId());
        if (result.kind() == ExportScope.Kind.NONE
                || (result.kind() == ExportScope.Kind.INSTITUTION && type != ExportType.REDEMPTION)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有该数据集的数据范围");
        }
        return result;
    }

    private ExportRestriction restriction(ExportScopeRequest request) {
        if (request == null) {
            return ExportRestriction.none();
        }
        return new ExportRestriction(request.teamIds(), request.ownerId(), request.institutionIds());
    }

    private void validateRestriction(ExportType type, ExportScope scope, ExportRestriction restriction) {
        if (restriction == null) {
            return;
        }
        List<Long> teamIds = restriction.teamIds();
        if (teamIds != null && !teamIds.isEmpty()) {
            if (scope.kind() == ExportScope.Kind.TEAM) {
                if (!scope.teamIds().containsAll(teamIds)) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "团队筛选超出当前数据范围");
                }
            } else if (scope.kind() != ExportScope.Kind.ALL) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "当前范围不能按团队筛选");
            }
        }
        if (restriction.institutionIds() != null) {
            if (type != ExportType.REDEMPTION) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅核销导出支持机构范围筛选");
            }
            if (scope.kind() == ExportScope.Kind.INSTITUTION
                    && !scope.institutionIds().containsAll(restriction.institutionIds())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "机构筛选超出当前数据范围");
            }
        }
        if (restriction.ownerId() != null) {
            if (scope.kind() == ExportScope.Kind.INSTITUTION) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号不能按管家筛选");
            }
            if (scope.kind() == ExportScope.Kind.SELF && restriction.ownerId() != scope.selfId()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "管家筛选超出本人范围");
            }
        }
    }
}
