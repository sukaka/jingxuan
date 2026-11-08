package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.CustomerOwnerHistory;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerOwnerHistoryMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisCustomerOwnerHistoryRepository implements CustomerOwnerHistoryRepository {

    private final CustomerOwnerHistoryMapper mapper;

    public MybatisCustomerOwnerHistoryRepository(CustomerOwnerHistoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<CustomerOwnerHistory> findByCustomerId(long tenantId, long customerId) {
        return mapper.selectByCustomerId(tenantId, customerId);
    }

    @Override
    public void insert(CustomerOwnerHistory history) {
        mapper.insertHistory(history);
    }

    @Override
    public List<OwnerHistoryRow> findRowsByCustomerId(long tenantId, long customerId) {
        return mapper.selectRowsByCustomerId(tenantId, customerId);
    }
}
