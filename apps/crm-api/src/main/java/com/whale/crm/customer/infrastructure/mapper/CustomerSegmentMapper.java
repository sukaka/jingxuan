package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.api.dto.CustomerSegmentCriteria;
import com.whale.crm.customer.domain.CustomerListRow;
import com.whale.crm.customer.domain.CustomerSegment;
import com.whale.crm.customer.domain.CustomerSegmentBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CustomerSegmentMapper {
    List<CustomerSegment> search(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                                 @Param("offset") int offset, @Param("size") int size);

    long count(@Param("tenantId") long tenantId, @Param("keyword") String keyword);

    CustomerSegment selectActiveById(@Param("tenantId") long tenantId, @Param("id") long id);

    CustomerSegment selectActiveByName(@Param("tenantId") long tenantId, @Param("name") String name);

    int insert(CustomerSegment segment);

    int update(CustomerSegment segment);

    int softDelete(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version);

    List<CustomerListRow> searchMembers(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                                        @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                                        @Param("filters") CustomerSegmentCriteria filters,
                                        @Param("offset") int offset, @Param("size") int size);

    long countMembers(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                      @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                      @Param("filters") CustomerSegmentCriteria filters);

    int insertBatch(CustomerSegmentBatch batch);

    CustomerSegmentBatch selectBatchById(@Param("tenantId") long tenantId, @Param("id") long id);

    CustomerSegmentBatch selectBatchByNo(@Param("tenantId") long tenantId, @Param("batchNo") String batchNo);

    int insertBatchMember(@Param("tenantId") long tenantId, @Param("batchId") long batchId,
                          @Param("customerId") long customerId);
}
