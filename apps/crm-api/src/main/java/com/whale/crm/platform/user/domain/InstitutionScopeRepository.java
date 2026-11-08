package com.whale.crm.platform.user.domain;

import java.util.List;

/**
 * crm_institution_user 存取：机构账号 → 可见机构集合。
 */
public interface InstitutionScopeRepository {

    List<Long> findActiveInstitutionIds(long tenantId, long userId);

    List<InstitutionUserScope> findActiveScopes(long tenantId, long userId);
}
