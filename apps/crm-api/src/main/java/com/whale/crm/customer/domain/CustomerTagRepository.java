package com.whale.crm.customer.domain;

import java.util.List;

/**
 * crm_customer_tag 存取。标签为 (tenant_id, customer_id, tag_code) 唯一；全量替换由应用层 diff。
 */
public interface CustomerTagRepository {

    List<String> findTagCodes(long tenantId, long customerId);

    void insert(long tenantId, long customerId, String tagCode, Long createdBy);

    int delete(long tenantId, long customerId, String tagCode);

    /**
     * 合并迁移：把从客户有而主客户没有的标签改挂主客户；重复标签删除。返回 (migrated, dropped)。
     */
    int reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId);

    int deleteDuplicatesOf(long tenantId, long fromCustomerId, long toCustomerId);

    int reassignSpecificCodes(long tenantId, List<String> tagCodes, long fromCustomerId, long toCustomerId);
}
