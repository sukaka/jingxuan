package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerTagMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisCustomerTagRepository implements CustomerTagRepository {

    private final CustomerTagMapper customerTagMapper;

    public MybatisCustomerTagRepository(CustomerTagMapper customerTagMapper) {
        this.customerTagMapper = customerTagMapper;
    }

    @Override
    public List<String> findTagCodes(long tenantId, long customerId) {
        return customerTagMapper.selectTagCodes(tenantId, customerId);
    }

    @Override
    public void insert(long tenantId, long customerId, String tagCode, Long createdBy) {
        customerTagMapper.insertTag(tenantId, customerId, tagCode, createdBy);
    }

    @Override
    public int delete(long tenantId, long customerId, String tagCode) {
        return customerTagMapper.deleteTag(tenantId, customerId, tagCode);
    }

    @Override
    public int reassignCustomer(long tenantId, long fromCustomerId, long toCustomerId) {
        return customerTagMapper.reassignCustomer(tenantId, fromCustomerId, toCustomerId);
    }

    @Override
    public int deleteDuplicatesOf(long tenantId, long fromCustomerId, long toCustomerId) {
        return customerTagMapper.deleteDuplicatesOf(tenantId, fromCustomerId, toCustomerId);
    }

    @Override
    public int reassignSpecificCodes(long tenantId, List<String> tagCodes, long fromCustomerId, long toCustomerId) {
        return customerTagMapper.reassignSpecificCodes(tenantId, tagCodes, fromCustomerId, toCustomerId);
    }
}
