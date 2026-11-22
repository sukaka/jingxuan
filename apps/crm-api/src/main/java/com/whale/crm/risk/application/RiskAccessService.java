package com.whale.crm.risk.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class RiskAccessService {
    private final DataScopeService dataScopeService;

    public RiskAccessService(DataScopeService dataScopeService) {
        this.dataScopeService = dataScopeService;
    }

    public void require(CurrentUser user, String permission) {
        if (user == null || user.isInstitutionUser() || user.roles().contains("MANAGER")
                || user.roles().stream().anyMatch(role -> role.startsWith("INSTITUTION_"))
                || !user.permissions().contains(permission)
                || !isAllScope(dataScopeService.scopeFor(user, DataScopeService.RESOURCE_CUSTOMER))
                || !isAllScope(dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "风控全局台账仅限具备全量数据范围的授权员工访问");
        }
    }

    private boolean isAllScope(DataScope scope) {
        return scope != null && scope.type() == DataScope.ScopeType.ALL;
    }
}
