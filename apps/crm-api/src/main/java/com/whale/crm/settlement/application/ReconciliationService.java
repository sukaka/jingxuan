package com.whale.crm.settlement.application;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.settlement.api.dto.ReconciliationResponse;
import com.whale.crm.settlement.domain.ReconciliationIssue;
import com.whale.crm.settlement.domain.SettlementRepository;
import com.whale.crm.settlement.domain.SettlementScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReconciliationService {
    private final SettlementRepository repository;
    private final InstitutionAccessSupport institutionAccess;

    public ReconciliationService(SettlementRepository repository, InstitutionAccessSupport institutionAccess) {
        this.repository = repository;
        this.institutionAccess = institutionAccess;
    }

    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile(long tenantId, CurrentUser user, Long institutionId) {
        SettlementScope scope = SettlementAccess.scope(tenantId, user, institutionId, institutionAccess);
        List<ReconciliationIssue> issues = new ArrayList<>();
        if (!user.isInstitutionUser()) {
            issues.addAll(repository.entitlementLedgerIssues(tenantId));
            issues.addAll(repository.orderCashIssues(tenantId));
            issues.addAll(repository.refundAllocationIssues(tenantId));
        }
        issues.addAll(repository.settlementPaymentIssues(tenantId, scope, institutionId));
        issues.addAll(repository.unbatchedRedemptionIssues(tenantId, scope, institutionId));
        List<ReconciliationIssue> immutableIssues = List.copyOf(issues);
        return new ReconciliationResponse(Instant.now(), immutableIssues.size(), immutableIssues);
    }
}
