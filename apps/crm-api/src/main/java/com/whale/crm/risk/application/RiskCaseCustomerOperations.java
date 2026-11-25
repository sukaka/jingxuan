package com.whale.crm.risk.application;

import com.whale.crm.customer.application.CustomerRiskOperations;
import com.whale.crm.risk.domain.RiskCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RiskCaseCustomerOperations implements CustomerRiskOperations {
    private final RiskCaseRepository repository;

    public RiskCaseCustomerOperations(RiskCaseRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findRiskCaseIdsByCustomer(long tenantId, long customerId) {
        return repository.findIdsByCustomerId(tenantId, customerId);
    }

    @Override
    @Transactional
    public void reassignSpecific(long tenantId, List<Long> riskCaseIds, List<Long> orderIds,
                                 long fromCustomerId, long toCustomerId) {
        if (riskCaseIds != null && !riskCaseIds.isEmpty()) {
            repository.reassignSpecific(tenantId, riskCaseIds, fromCustomerId, toCustomerId);
        }
        if (orderIds != null && !orderIds.isEmpty()) {
            repository.reassignSpecificByOrderIds(tenantId, orderIds, fromCustomerId, toCustomerId);
        }
    }
}
