package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.CustomerTransfer;
import com.whale.crm.customer.domain.CustomerTransferRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface CustomerTransferMapper {

    CustomerTransfer selectById(@Param("tenantId") long tenantId, @Param("id") long id);

    List<CustomerTransfer> selectByCustomerId(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    int countPendingForCustomer(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    int insertTransfer(CustomerTransfer transfer);

    int updateStatus(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                     @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                     @Param("reviewedBy") Long reviewedBy, @Param("reviewedAt") Instant reviewedAt,
                     @Param("reviewReason") String reviewReason, @Param("effectiveAt") Instant effectiveAt);

    List<CustomerTransferRepository.TransferRow> selectRowsByCustomerId(@Param("tenantId") long tenantId,
                                                                        @Param("customerId") long customerId);
}
