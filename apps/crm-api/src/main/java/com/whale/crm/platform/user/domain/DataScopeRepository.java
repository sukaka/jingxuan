package com.whale.crm.platform.user.domain;

import java.util.List;

/**
 * sys_data_scope 存取（行级数据范围授权）。
 */
public interface DataScopeRepository {

    /**
     * 用户在某资源上的有效授权（未到期）。
     */
    List<DataScopeRow> findActiveScopes(long tenantId, long userId, String resource);
}
