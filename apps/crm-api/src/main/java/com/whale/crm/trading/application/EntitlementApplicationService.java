package com.whale.crm.trading.application;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementDetail;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EntitlementApplicationService {
    private final TradingRepository repository;
    private final CustomerScopeSupport scopeSupport;

    public EntitlementApplicationService(TradingRepository repository, CustomerScopeSupport scopeSupport) {
        this.repository = repository;
        this.scopeSupport = scopeSupport;
    }

    @Transactional(readOnly = true)
    public PageResponse<Entitlement> search(long tenantId, CurrentUser user, Long customerId, String status,
                                            int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchEntitlements(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                        customerId, status, offset, size),
                repository.countEntitlements(tenantId, query.scopeType(), query.teamIds(), query.selfId(), customerId, status),
                page, size);
    }

    @Transactional(readOnly = true)
    public com.whale.crm.trading.domain.EntitlementSummary summary(long tenantId, CurrentUser user, Long customerId) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return com.whale.crm.trading.domain.EntitlementSummary.empty();
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        return repository.summarizeEntitlements(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                customerId, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
    }

    @Transactional(readOnly = true)
    public EntitlementDetail detail(long tenantId, CurrentUser user, long entitlementId) {
        Entitlement entitlement = repository.findEntitlement(tenantId, entitlementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "权益不存在或不在数据范围内"));
        scopeSupport.requireVisible(tenantId, entitlement.customerId(), user);
        return new EntitlementDetail(entitlement, repository.sumLedger(tenantId, entitlementId),
                repository.findLedgerByEntitlement(tenantId, entitlementId));
    }

}
