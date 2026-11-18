package com.whale.crm.settlement.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.settlement.domain.SettlementBatch;
import com.whale.crm.settlement.domain.SettlementScope;

final class SettlementAccess {
    private SettlementAccess() {
    }

    static void requireEmployeeWrite(long tenantId, CurrentUser user, DataScopeService dataScopeService) {
        if (user.isInstitutionUser()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号只能查看结算与对账，不能执行写操作");
        }
        if (!employeeHasAll(tenantId, user, dataScopeService)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号没有机构结算数据范围");
        }
    }

    static void requireVisible(long tenantId, CurrentUser user, SettlementBatch batch,
                               InstitutionAccessSupport institutionAccess, DataScopeService dataScopeService) {
        if (user.isInstitutionUser()) {
            if (!institutionAccess.canAccess(tenantId, user, batch.institutionId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "结算批次不存在或不可见");
            }
            return;
        }
        if (!employeeHasAll(tenantId, user, dataScopeService)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "结算批次不存在或不可见");
        }
    }

    static SettlementScope scope(long tenantId, CurrentUser user, Long institutionId,
                                 InstitutionAccessSupport institutionAccess, DataScopeService dataScopeService) {
        if (!user.isInstitutionUser()) {
            return employeeHasAll(tenantId, user, dataScopeService)
                    ? SettlementScope.all() : SettlementScope.restricted(java.util.List.of());
        }
        if (institutionId != null && !institutionAccess.canAccess(tenantId, user, institutionId)) {
            return SettlementScope.restricted(java.util.List.of());
        }
        return SettlementScope.restricted(institutionAccess.visibleInstitutionIds(tenantId, user));
    }

    private static boolean employeeHasAll(long tenantId, CurrentUser user, DataScopeService dataScopeService) {
        return user.tenantId() == tenantId && dataScopeService.scopeFor(user, DataScopeService.RESOURCE_INSTITUTION)
                .type() == DataScope.ScopeType.ALL;
    }
}
