package com.whale.crm.integration.youzan.application;

import java.util.List;

/**
 * 有赞模块向风控台账回填客户/订单关联的端口接口（避免 integration/youzan 与 risk 循环依赖及跨模块 Mapper 直查）。
 */
public interface YouzanRiskLinkOperations {
    YouzanRiskLinkOperations NOOP = new YouzanRiskLinkOperations() {
        @Override
        public void linkCustomerToYouzanIssues(long tenantId, List<String> issueSourceRefs, long customerId) {
        }

        @Override
        public void linkOrderToYouzanIssues(long tenantId, List<String> issueSourceRefs, long orderId, long customerId) {
        }
    };

    void linkCustomerToYouzanIssues(long tenantId, List<String> issueSourceRefs, long customerId);

    void linkOrderToYouzanIssues(long tenantId, List<String> issueSourceRefs, long orderId, long customerId);
}
