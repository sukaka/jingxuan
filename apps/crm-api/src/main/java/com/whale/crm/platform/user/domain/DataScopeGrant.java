package com.whale.crm.platform.user.domain;

/**
 * 写入 sys_data_scope 的单条数据范围授权值对象。
 */
public record DataScopeGrant(String resource, String scopeType, Long scopeRefId) {
}
