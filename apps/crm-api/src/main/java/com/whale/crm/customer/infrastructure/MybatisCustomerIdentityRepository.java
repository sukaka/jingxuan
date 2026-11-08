package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.CustomerIdentity;
import com.whale.crm.customer.domain.CustomerIdentityRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerIdentityMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisCustomerIdentityRepository implements CustomerIdentityRepository {

    private final CustomerIdentityMapper customerIdentityMapper;

    public MybatisCustomerIdentityRepository(CustomerIdentityMapper customerIdentityMapper) {
        this.customerIdentityMapper = customerIdentityMapper;
    }

    @Override
    public List<CustomerIdentity> findByCustomerId(long tenantId, long customerId) {
        return customerIdentityMapper.selectByCustomerId(tenantId, customerId);
    }

    @Override
    public void insert(CustomerIdentity identity) {
        customerIdentityMapper.insertIdentity(identity);
    }

    @Override
    public int deleteById(long tenantId, long id) {
        return customerIdentityMapper.deleteById(tenantId, id);
    }

    @Override
    public int reassignCustomer(long tenantId, long identityId, long newCustomerId) {
        return customerIdentityMapper.reassignCustomer(tenantId, identityId, newCustomerId);
    }

    @Override
    public int reassignSpecific(long tenantId, List<Long> identityIds, long fromCustomerId, long toCustomerId) {
        return customerIdentityMapper.reassignSpecific(tenantId, identityIds, fromCustomerId, toCustomerId);
    }
}
