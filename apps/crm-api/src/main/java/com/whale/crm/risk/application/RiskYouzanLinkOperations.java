package com.whale.crm.risk.application;

import com.whale.crm.integration.youzan.application.YouzanRiskLinkOperations;
import com.whale.crm.risk.domain.RiskCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RiskYouzanLinkOperations implements YouzanRiskLinkOperations {
    private final RiskCaseRepository repository;

    public RiskYouzanLinkOperations(RiskCaseRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void linkCustomerToYouzanIssues(long tenantId, List<String> issueSourceRefs, long customerId) {
        if (issueSourceRefs == null || issueSourceRefs.isEmpty()) {
            return;
        }
        repository.linkCustomerToYouzanIssues(tenantId, issueSourceRefs, customerId);
    }

    @Override
    @Transactional
    public void linkOrderToYouzanIssues(long tenantId, List<String> issueSourceRefs, long orderId, long customerId) {
        if (issueSourceRefs == null || issueSourceRefs.isEmpty()) {
            return;
        }
        repository.linkOrderToYouzanIssues(tenantId, issueSourceRefs, orderId, customerId);
    }
}
