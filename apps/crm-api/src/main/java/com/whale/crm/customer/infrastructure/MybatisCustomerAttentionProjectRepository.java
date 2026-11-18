package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.CustomerAttentionProjectRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerAttentionProjectMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisCustomerAttentionProjectRepository implements CustomerAttentionProjectRepository {
    private final CustomerAttentionProjectMapper mapper;

    public MybatisCustomerAttentionProjectRepository(CustomerAttentionProjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Long> findProjectIds(long tenantId, long customerId) {
        return mapper.selectProjectIds(tenantId, customerId);
    }

    @Override
    public void deleteByCustomerId(long tenantId, long customerId) {
        mapper.deleteByCustomerId(tenantId, customerId);
    }

    @Override
    public void insert(long tenantId, long customerId, long projectId, Long actorUserId) {
        mapper.insert(tenantId, customerId, projectId, actorUserId);
    }
}
