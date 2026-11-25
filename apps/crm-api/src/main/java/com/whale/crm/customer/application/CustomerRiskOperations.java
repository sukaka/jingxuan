package com.whale.crm.customer.application;

import java.util.List;

/**
 * 客户合并与合并更正同步迁移/回滚风控台账客户归属的端口接口。
 */
public interface CustomerRiskOperations {
    CustomerRiskOperations NOOP = new CustomerRiskOperations() {
        @Override
        public List<Long> findRiskCaseIdsByCustomer(long tenantId, long customerId) {
            return List.of();
        }

        @Override
        public void reassignSpecific(long tenantId, List<Long> riskCaseIds, List<Long> orderIds,
                                     long fromCustomerId, long toCustomerId) {
        }
    };

    List<Long> findRiskCaseIdsByCustomer(long tenantId, long customerId);

    void reassignSpecific(long tenantId, List<Long> riskCaseIds, List<Long> orderIds,
                          long fromCustomerId, long toCustomerId);
}
