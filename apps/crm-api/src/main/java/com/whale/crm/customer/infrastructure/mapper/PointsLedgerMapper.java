package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.PointsLedgerEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PointsLedgerMapper {

    List<PointsLedgerEntry> selectByCustomerId(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    Long sumDelta(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    int insertEntry(PointsLedgerEntry entry);

    int reassignCustomer(@Param("tenantId") long tenantId, @Param("fromCustomerId") long fromCustomerId,
                         @Param("toCustomerId") long toCustomerId);

    @org.apache.ibatis.annotations.Update({"<script>UPDATE crm_points_ledger SET customer_id=#{toCustomerId} WHERE tenant_id=#{tenantId} AND customer_id=#{fromCustomerId} AND id IN <foreach item='id' collection='ids' open='(' close=')' separator=','>#{id}</foreach></script>"})
    int reassignSpecific(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids,
                         @Param("fromCustomerId") long fromCustomerId, @Param("toCustomerId") long toCustomerId);
}
