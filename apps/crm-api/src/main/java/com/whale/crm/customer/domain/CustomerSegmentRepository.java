package com.whale.crm.customer.domain;

import com.whale.crm.customer.api.dto.CustomerSegmentCriteria;

import java.util.List;
import java.util.Optional;

public interface CustomerSegmentRepository {
    List<CustomerSegment> search(long tenantId, String keyword, int offset, int size);

    long count(long tenantId, String keyword);

    Optional<CustomerSegment> findActiveById(long tenantId, long id);

    Optional<CustomerSegment> findActiveByName(long tenantId, String name);

    void insert(CustomerSegment segment);

    int update(CustomerSegment segment);

    int softDelete(long tenantId, long id, long version);

    List<CustomerListRow> searchMembers(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                        CustomerSegmentCriteria filters, int offset, int size);

    long countMembers(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                      CustomerSegmentCriteria filters);

    void insertBatch(CustomerSegmentBatch batch);

    Optional<CustomerSegmentBatch> findBatchById(long tenantId, long id);

    Optional<CustomerSegmentBatch> findBatchByNo(long tenantId, String batchNo);

    void insertBatchMember(long tenantId, long batchId, long customerId);
}
