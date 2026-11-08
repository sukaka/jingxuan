package com.whale.crm.customer.domain;

import java.util.List;

/**
 * crm_customer_identity 存取。
 */
public interface CustomerIdentityRepository {

    List<CustomerIdentity> findByCustomerId(long tenantId, long customerId);

    void insert(CustomerIdentity identity);

    /**
     * 合并迁移：从客户的 identity 中删除与主客户 (identity_type,source_app,external_id) 完全重复的，
     * 其余改挂主客户。返回 (migrated, dropped) 计数。
     */
    int deleteById(long tenantId, long id);

    int reassignCustomer(long tenantId, long identityId, long newCustomerId);

    int reassignSpecific(long tenantId, List<Long> identityIds, long fromCustomerId, long toCustomerId);
}
