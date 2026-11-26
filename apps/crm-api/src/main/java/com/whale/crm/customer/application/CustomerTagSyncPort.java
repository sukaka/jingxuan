package com.whale.crm.customer.application;

import java.util.List;

/**
 * 客户标签变更向企微同步的解耦端口（由 integration.wecom.tag 实现）。
 * 在本地客户标签写入事务内调用时仅入队推送任务，不得因外部通道不可用而回滚本地 CRM 标签写入。
 */
public interface CustomerTagSyncPort {

    void onCustomerTagsChanged(long tenantId, long customerId, List<String> beforeTagCodes,
                               List<String> afterTagCodes, Long actorUserId, String triggerSource);
}
