package com.whale.crm.risk.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class RiskAccessService {
    public void require(CurrentUser user, String permission) {
        if (user == null || user.isInstitutionUser() || user.roles().contains("MANAGER")
                || user.roles().stream().anyMatch(role -> role.startsWith("INSTITUTION_"))
                || !user.permissions().contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "风控全局台账仅限授权员工角色访问");
        }
    }
}
