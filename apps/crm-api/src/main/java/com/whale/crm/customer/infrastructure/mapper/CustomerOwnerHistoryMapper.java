package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.CustomerOwnerHistory;
import com.whale.crm.customer.domain.CustomerOwnerHistoryRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CustomerOwnerHistoryMapper {

    List<CustomerOwnerHistory> selectByCustomerId(@Param("tenantId") long tenantId,
                                                  @Param("customerId") long customerId);

    List<CustomerOwnerHistoryRepository.OwnerHistoryRow> selectRowsByCustomerId(@Param("tenantId") long tenantId,
                                                                                @Param("customerId") long customerId);

    int insertHistory(CustomerOwnerHistory history);
}
