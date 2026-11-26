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

    /**
     * 按资源列表原子替换用户的数据范围授权（先删除指定资源下的旧行，再写入新授权）。
     */
    void replaceResourceScopes(long tenantId, long userId, List<String> resources, List<DataScopeGrant> grants);
}
