package com.whale.crm.settlement.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.settlement.domain.SettlementBatch;
import com.whale.crm.settlement.domain.SettlementScope;

final class SettlementAccess {
    private SettlementAccess() {
    }

    static void requireEmployeeWrite(CurrentUser user) {
        if (user.isInstitutionUser()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "机构账号只能查看结算与对账，不能执行写操作");
        }
    }

    static void requireVisible(long tenantId, CurrentUser user, SettlementBatch batch,
                               InstitutionAccessSupport institutionAccess) {
        if (user.isInstitutionUser() && !institutionAccess.canAccess(tenantId, user, batch.institutionId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "结算批次不存在或不可见");
        }
    }

    static SettlementScope scope(long tenantId, CurrentUser user, Long institutionId,
                                 InstitutionAccessSupport institutionAccess) {
        if (!user.isInstitutionUser()) {
            return SettlementScope.all();
        }
        if (institutionId != null && !institutionAccess.canAccess(tenantId, user, institutionId)) {
            return SettlementScope.restricted(java.util.List.of());
        }
        return SettlementScope.restricted(institutionAccess.visibleInstitutionIds(tenantId, user));
    }
}
