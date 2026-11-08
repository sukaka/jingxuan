package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.CustomerLegacy;
import com.whale.crm.customer.domain.CustomerLegacyRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerLegacyMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MybatisCustomerLegacyRepository implements CustomerLegacyRepository {

    private final CustomerLegacyMapper mapper;

    public MybatisCustomerLegacyRepository(CustomerLegacyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<CustomerLegacy> findByCustomerId(long tenantId, long customerId) {
        return Optional.ofNullable(mapper.selectByCustomerId(tenantId, customerId));
    }
}
