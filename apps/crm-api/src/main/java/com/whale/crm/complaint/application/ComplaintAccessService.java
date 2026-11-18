package com.whale.crm.complaint.application;

import com.whale.crm.complaint.domain.ComplaintCase;
import com.whale.crm.complaint.domain.ComplaintHit;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class ComplaintAccessService {
    private final CustomerScopeSupport scopeSupport;
    private final CustomerRepository customerRepository;

    public ComplaintAccessService(CustomerScopeSupport scopeSupport, CustomerRepository customerRepository) {
        this.scopeSupport = scopeSupport;
        this.customerRepository = customerRepository;
    }

    public void require(CurrentUser user, String permission) {
        if (user == null || user.isInstitutionUser() || !user.permissions().contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有客诉资源访问权限");
        }
    }

    public DataScope scope(CurrentUser user) {
        return scopeSupport.scope(user);
    }

    public boolean isEmpty(DataScope scope) {
        return scopeSupport.isEmptyScope(scope);
    }

    public CustomerScopeSupport.ScopeQuery query(DataScope scope, CurrentUser user) {
        return scopeSupport.queryOf(scope, user);
    }

    public Customer requireCustomerVisible(CurrentUser user, long customerId) {
        require(user, "complaint:read");
        return scopeSupport.requireVisible(user.tenantId(), customerId, user);
    }

    public ComplaintHit requireHitVisible(CurrentUser user, ComplaintHit hit) {
        if (hit == null || hit.tenantId() != user.tenantId()) {
            throw notFound("客诉命中不存在或不可见");
        }
        requireCustomerVisible(user, hit.customerId());
        return hit;
    }

    public ComplaintCase requireCaseVisible(CurrentUser user, ComplaintCase complaintCase) {
        if (complaintCase == null || complaintCase.tenantId() != user.tenantId()) {
            throw notFound("客诉单不存在或不可见");
        }
        requireCustomerVisible(user, complaintCase.customerId());
        return complaintCase;
    }

    public Customer requireCustomer(CurrentUser user, long customerId) {
        return customerRepository.findById(user.tenantId(), customerId)
                .filter(customer -> !customer.isDeleted())
                .orElseThrow(() -> notFound("客户不存在或不可见"));
    }

    public BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }
}
