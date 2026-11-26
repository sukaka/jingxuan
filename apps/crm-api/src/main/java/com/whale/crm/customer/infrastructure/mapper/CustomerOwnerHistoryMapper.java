package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.CustomerOwnerHistory;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface CustomerOwnerHistoryMapper {

    List<CustomerOwnerHistory> selectByCustomerId(@Param("tenantId") long tenantId,
                                                  @Param("customerId") long customerId);

    CustomerOwnerHistoryRepository.OwnerHistoryRow selectRowById(@Param("tenantId") long tenantId,
                                                                 @Param("id") long id);

    List<CustomerOwnerHistoryRepository.OwnerHistoryRow> selectRowsByApprovalRef(@Param("tenantId") long tenantId,
                                                                                 @Param("approvalRef") String approvalRef);

    List<CustomerOwnerHistoryRepository.OwnerHistoryRow> selectRowsByCustomerId(@Param("tenantId") long tenantId,
                                                                                @Param("customerId") long customerId);

    List<CustomerOwnerHistoryRepository.OwnerHistoryRow> selectTimelineRowsByCustomerId(
            @Param("tenantId") long tenantId, @Param("customerId") long customerId,
            @Param("cursorMode") String cursorMode, @Param("cursorOccurredAt") Instant cursorOccurredAt,
            @Param("cursorSourceId") Long cursorSourceId, @Param("limit") int limit);

    List<CustomerOwnerHistoryRepository.OwnerHistoryLogRow> searchLogs(
            @Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
            @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
            @Param("customerId") Long customerId, @Param("ownerId") Long ownerId,
            @Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    long countSearchLogs(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                         @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                         @Param("customerId") Long customerId, @Param("ownerId") Long ownerId,
                         @Param("keyword") String keyword);

    int insertHistory(CustomerOwnerHistory history);
}
