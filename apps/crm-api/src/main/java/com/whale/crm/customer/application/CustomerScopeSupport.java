package com.whale.crm.customer.application;

import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.DataScopeService;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 客户资源的数据范围判定（@PreAuthorize 之外的行级双保险，docs/12）。
 * ALL 全量；TEAM 本组+授权团队（含本人持有）；SELF 仅 owner=本人；
 * NONE/INSTITUTION 一律不可见（机构账号无客户权限）。越权一律 404，不泄露存在性。
 */
@Component
public class CustomerScopeSupport {

    private final DataScopeService dataScopeService;
    private final CustomerRepository customerRepository;

    public CustomerScopeSupport(DataScopeService dataScopeService, CustomerRepository customerRepository) {
        this.dataScopeService = dataScopeService;
        this.customerRepository = customerRepository;
    }

    public DataScope scope(CurrentUser user) {
        return dataScopeService.scopeFor(user, DataScopeService.RESOURCE_CUSTOMER);
    }

    /**
     * 空范围（无授权/机构账号）：列表返回空页，单条返回 404。
     */
    public boolean isEmptyScope(DataScope scope) {
        return scope.type() == DataScope.ScopeType.NONE || scope.type() == DataScope.ScopeType.INSTITUTION;
    }

    public boolean isVisible(DataScope scope, CurrentUser user, Customer customer) {
        return switch (scope.type()) {
            case ALL -> true;
            case TEAM -> customer.getOwnerStaffId() == user.userId()
                    || (customer.getTeamId() != null && scope.teamIds().contains(customer.getTeamId()));
            case SELF -> customer.getOwnerStaffId() == user.userId();
            default -> false;
        };
    }

    /**
     * 读取并校验可见性；不存在或越权一律 404。
     */
    public Customer requireVisible(long tenantId, long customerId, CurrentUser user) {
        return requireVisible(customerRepository.findById(tenantId, customerId), user);
    }

    /**
     * 在事务内锁定客户行并校验锁定后的归属，序列化同客户积分记账。
     */
    public Customer requireVisibleForUpdate(long tenantId, long customerId, CurrentUser user) {
        return requireVisible(customerRepository.findByIdForUpdate(tenantId, customerId), user);
    }

    private Customer requireVisible(Optional<Customer> candidate, CurrentUser user) {
        Customer customer = candidate
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
        if (customer.isDeleted() || !isVisible(scope(user), user, customer)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见");
        }
        return customer;
    }

    /**
     * 供 SQL 查询的范围参数。
     */
    public record ScopeQuery(String scopeType, List<Long> teamIds, long selfId) {
        public ScopeQuery {
            teamIds = teamIds == null ? null : List.copyOf(teamIds);
        }
    }

    public ScopeQuery queryOf(DataScope scope, CurrentUser user) {
        return new ScopeQuery(scope.type().name(), List.copyOf(scope.teamIds()), user.userId());
    }
}
