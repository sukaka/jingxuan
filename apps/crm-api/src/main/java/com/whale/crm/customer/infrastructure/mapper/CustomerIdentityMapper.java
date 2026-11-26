package com.whale.crm.customer.infrastructure.mapper;

import com.whale.crm.customer.domain.CustomerIdentity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CustomerIdentityMapper {

    List<CustomerIdentity> selectByCustomerId(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    List<CustomerIdentity> selectByCustomerIds(@Param("tenantId") long tenantId,
                                               @Param("customerIds") List<Long> customerIds);

    List<CustomerIdentity> selectByExternalIds(@Param("tenantId") long tenantId,
                                               @Param("externalIds") List<String> externalIds);

    CustomerIdentity selectByIdentity(@Param("tenantId") long tenantId,
                                      @Param("identityType") String identityType,
                                      @Param("sourceApp") String sourceApp,
                                      @Param("externalId") String externalId);

    int insertIdentity(CustomerIdentity identity);

    int deleteById(@Param("tenantId") long tenantId, @Param("id") long id);

    int reassignCustomer(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("customerId") long customerId);

    @org.apache.ibatis.annotations.Update({"<script>UPDATE crm_customer_identity SET customer_id=#{toCustomerId} WHERE tenant_id=#{tenantId} AND customer_id=#{fromCustomerId} AND id IN <foreach item='id' collection='ids' open='(' close=')' separator=','>#{id}</foreach></script>"})
    int reassignSpecific(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids,
                         @Param("fromCustomerId") long fromCustomerId, @Param("toCustomerId") long toCustomerId);
}
