package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.domain.CustomerOwnerHistory;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerOwnerHistoryMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
    public Optional<OwnerHistoryRow> findRowById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectRowById(tenantId, id));
    }

    @Override
    public List<OwnerHistoryRow> findRowsByApprovalRef(long tenantId, String approvalRef) {
        return mapper.selectRowsByApprovalRef(tenantId, approvalRef);
    }

    @Override
    public List<OwnerHistoryRow> findRowsByCustomerId(long tenantId, long customerId) {
        return mapper.selectRowsByCustomerId(tenantId, customerId);
    }

    @Override
    public List<OwnerHistoryRow> findTimelineRowsByCustomerId(long tenantId, long customerId, String cursorMode,
                                                              Instant cursorOccurredAt, Long cursorSourceId,
                                                              int limit) {
        return mapper.selectTimelineRowsByCustomerId(tenantId, customerId, cursorMode, cursorOccurredAt,
                cursorSourceId, limit);
    }

    @Override
    public List<OwnerHistoryLogRow> searchLogs(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                               Long customerId, Long ownerId, String keyword, int offset, int size) {
        return mapper.searchLogs(tenantId, scopeType, teamIds, selfId, customerId, ownerId, keyword, offset, size);
    }

    @Override
    public long countSearchLogs(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                Long customerId, Long ownerId, String keyword) {
        return mapper.countSearchLogs(tenantId, scopeType, teamIds, selfId, customerId, ownerId, keyword);
    }
}
