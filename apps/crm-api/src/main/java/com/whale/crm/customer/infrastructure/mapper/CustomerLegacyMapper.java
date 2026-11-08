package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.CustomerLegacy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerLegacyMapper {

    CustomerLegacy selectByCustomerId(@Param("tenantId") long tenantId, @Param("customerId") long customerId);
}
