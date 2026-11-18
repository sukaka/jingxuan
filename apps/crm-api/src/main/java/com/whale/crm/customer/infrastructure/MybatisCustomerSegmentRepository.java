package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.api.dto.CustomerSegmentCriteria;
import com.whale.crm.customer.domain.CustomerListRow;
import com.whale.crm.customer.domain.CustomerSegment;
import com.whale.crm.customer.domain.CustomerSegmentBatch;
import com.whale.crm.customer.domain.CustomerSegmentRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerSegmentMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisCustomerSegmentRepository implements CustomerSegmentRepository {
    private final CustomerSegmentMapper mapper;

    public MybatisCustomerSegmentRepository(CustomerSegmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<CustomerSegment> search(long tenantId, String keyword, int offset, int size) {
        return mapper.search(tenantId, keyword, offset, size);
    }

    @Override
    public long count(long tenantId, String keyword) {
        return mapper.count(tenantId, keyword);
    }

    @Override
    public Optional<CustomerSegment> findActiveById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectActiveById(tenantId, id));
    }

    @Override
    public Optional<CustomerSegment> findActiveByName(long tenantId, String name) {
        return Optional.ofNullable(mapper.selectActiveByName(tenantId, name));
    }

    @Override
    public void insert(CustomerSegment segment) {
        mapper.insert(segment);
    }

    @Override
    public int update(CustomerSegment segment) {
        return mapper.update(segment);
    }

    @Override
    public int softDelete(long tenantId, long id, long version) {
        return mapper.softDelete(tenantId, id, version);
    }

    @Override
    public List<CustomerListRow> searchMembers(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                               CustomerSegmentCriteria filters, int offset, int size) {
        return mapper.searchMembers(tenantId, scopeType, teamIds, selfId, filters, offset, size);
    }

    @Override
    public long countMembers(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                             CustomerSegmentCriteria filters) {
        return mapper.countMembers(tenantId, scopeType, teamIds, selfId, filters);
    }

    @Override
    public void insertBatch(CustomerSegmentBatch batch) {
        mapper.insertBatch(batch);
    }

    @Override
    public Optional<CustomerSegmentBatch> findBatchById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectBatchById(tenantId, id));
    }

    @Override
    public Optional<CustomerSegmentBatch> findBatchByNo(long tenantId, String batchNo) {
        return Optional.ofNullable(mapper.selectBatchByNo(tenantId, batchNo));
    }

    @Override
    public void insertBatchMember(long tenantId, long batchId, long customerId) {
        mapper.insertBatchMember(tenantId, batchId, customerId);
    }
}
